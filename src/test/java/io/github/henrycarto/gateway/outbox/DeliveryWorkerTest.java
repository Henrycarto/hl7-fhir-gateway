package io.github.henrycarto.gateway.outbox;

import static io.github.henrycarto.gateway.TestSupport.message;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.henrycarto.gateway.TestSupport;
import io.github.henrycarto.gateway.TestSupport.MutableClock;
import io.github.henrycarto.gateway.TestSupport.ScriptedFhirSender;
import io.github.henrycarto.gateway.ingest.InboundMessageRepository;
import io.github.henrycarto.gateway.ingest.IngestService;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSupport.Config.class)
class DeliveryWorkerTest {

    @Autowired
    DeliveryWorker worker;
    @Autowired
    IngestService ingest;
    @Autowired
    OutboxRepository outbox;
    @Autowired
    InboundMessageRepository inbound;
    @Autowired
    ScriptedFhirSender sender;
    @Autowired
    MutableClock clock;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        inbound.deleteAll();
        sender.reset();
    }

    private static FhirDeliveryException serverDown() {
        return new FhirDeliveryException(true, 503, "HTTP 503: Service Unavailable");
    }

    private static FhirDeliveryException rejected() {
        return new FhirDeliveryException(false, 400, "HTTP 400: {\"resourceType\":\"OperationOutcome\"}");
    }

    @Test
    void deliversPendingEntries() {
        ingest.ingest(message("adt_a01.hl7"));

        assertThat(worker.runOnce()).isEqualTo(1);
        OutboxEntry entry = outbox.findAll().get(0);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(sender.delivered).hasSize(1);
    }

    @Test
    void serverErrorIsRetriedAfterBackoff() {
        ingest.ingest(message("adt_a01.hl7"));
        sender.then(serverDown());

        assertThat(worker.runOnce()).isZero();
        OutboxEntry entry = outbox.findAll().get(0);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(entry.getAttempts()).isEqualTo(1);
        assertThat(entry.getLastError()).contains("503");

        // Not due yet: nothing happens.
        assertThat(worker.runOnce()).isZero();
        assertThat(sender.delivered).isEmpty();

        clock.advance(Duration.ofSeconds(6));
        assertThat(worker.runOnce()).isEqualTo(1);
        assertThat(outbox.findAll().get(0).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void contentRejectionGoesStraightToDeadLetterAndQueueMovesOn() {
        ingest.ingest(message("adt_a01.hl7"));
        ingest.ingest(message("oru_r01.hl7"));
        sender.then(rejected());

        assertThat(worker.runOnce()).isEqualTo(1);
        List<OutboxEntry> entries = outbox.findAll();
        assertThat(entries.get(0).getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(entries.get(0).getLastError()).contains("OperationOutcome");
        assertThat(entries.get(1).getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void retryableFailureBlocksLaterMessagesToKeepOrder() {
        ingest.ingest(message("adt_a01.hl7"));
        ingest.ingest(message("adt_a03.hl7"));
        sender.then(serverDown());

        worker.runOnce();

        List<OutboxEntry> entries = outbox.findAll();
        assertThat(entries).extracting(OutboxEntry::getStatus)
                .containsExactly(OutboxStatus.PENDING, OutboxStatus.PENDING);
        assertThat(entries.get(1).getAttempts()).isZero(); // the discharge was never tried ahead of the admit
    }

    @Test
    void givesUpAfterMaxAttempts() {
        ingest.ingest(message("adt_a01.hl7"));
        // test profile: max-attempts 3
        sender.then(serverDown());
        sender.then(serverDown());
        sender.then(serverDown());

        for (int i = 0; i < 3; i++) {
            worker.runOnce();
            clock.advance(Duration.ofMinutes(15));
        }
        OutboxEntry entry = outbox.findAll().get(0);
        assertThat(entry.getStatus()).isEqualTo(OutboxStatus.DEAD);
        assertThat(entry.getAttempts()).isEqualTo(3);
    }
}
