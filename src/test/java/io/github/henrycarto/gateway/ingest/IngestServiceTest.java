package io.github.henrycarto.gateway.ingest;

import static io.github.henrycarto.gateway.TestSupport.message;
import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.hl7v2.AcknowledgmentCode;
import io.github.henrycarto.gateway.TestSupport;
import io.github.henrycarto.gateway.outbox.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
@Import(TestSupport.Config.class)
class IngestServiceTest {

    @Autowired
    IngestService ingest;
    @Autowired
    InboundMessageRepository inbound;
    @Autowired
    OutboxRepository outbox;

    @BeforeEach
    void clean() {
        outbox.deleteAll();
        inbound.deleteAll();
    }

    @Test
    void acceptedMessageIsStoredAndQueued() {
        IngestResult result = ingest.ingest(message("adt_a01.hl7"));

        assertThat(result.code()).isEqualTo(AcknowledgmentCode.AA);
        assertThat(result.duplicate()).isFalse();
        assertThat(result.ackText()).contains("MSA|AA|MSG00001");
        assertThat(inbound.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
        assertThat(outbox.findAll().get(0).getBundleJson()).contains("\"resourceType\":\"Bundle\"");
    }

    @Test
    void resendOfSameControlIdIsAcknowledgedButNotQueuedTwice() {
        ingest.ingest(message("adt_a01.hl7"));
        IngestResult again = ingest.ingest(message("adt_a01.hl7"));

        assertThat(again.code()).isEqualTo(AcknowledgmentCode.AA);
        assertThat(again.duplicate()).isTrue();
        assertThat(inbound.count()).isEqualTo(1);
        assertThat(outbox.count()).isEqualTo(1);
    }

    @Test
    void unsupportedTypeIsRejectedWithAr() {
        IngestResult result = ingest.ingest(message("adt_a40_unsupported.hl7"));

        assertThat(result.code()).isEqualTo(AcknowledgmentCode.AR);
        assertThat(result.ackText()).contains("MSA|AR|MSG00005");
        assertThat(outbox.count()).isZero();
    }

    @Test
    void missingRequiredFieldIsAnApplicationError() {
        IngestResult result = ingest.ingest(message("adt_a01_missing_pid3.hl7"));

        assertThat(result.code()).isEqualTo(AcknowledgmentCode.AE);
        assertThat(result.detail()).contains("PID-3");
        assertThat(inbound.count()).isZero();
        assertThat(outbox.count()).isZero();
    }

    @Test
    void rejectedMessageCanBeResentOnceFixed() {
        // An AE must not burn the control id: the sender fixes the data and resends with the same MSH-10.
        ingest.ingest(message("adt_a01_missing_pid3.hl7"));
        String fixed = message("adt_a01.hl7").replace("MSG00001", "MSG00006");
        assertThat(ingest.ingest(fixed).code()).isEqualTo(AcknowledgmentCode.AA);
    }

    @Test
    void garbageGetsAHandBuiltNak() {
        IngestResult result = ingest.ingest("this is not hl7");

        assertThat(result.code()).isEqualTo(AcknowledgmentCode.AR);
        assertThat(result.ack()).isNull();
        assertThat(result.ackText()).startsWith("MSH|^~\\&|").contains("MSA|AR|");
    }

    @Test
    void lineFeedsInsteadOfCarriageReturnsAreAccepted() {
        String withCrLf = message("oru_r01.hl7").replace("\n", "\r\n");
        assertThat(ingest.ingest(withCrLf).code()).isEqualTo(AcknowledgmentCode.AA);
    }
}
