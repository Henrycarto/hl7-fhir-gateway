package io.github.henrycarto.gateway.outbox;

import io.github.henrycarto.gateway.config.GatewayProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the outbox in arrival order.
 *
 * Order matters in clinical feeds: a discharge (A03) applied before its admit (A01)
 * leaves the encounter in the wrong state. So when the head of the queue fails with
 * a retryable error, the worker stops and waits instead of skipping ahead.
 * A non-retryable failure (the server rejected the content) goes to DEAD and the
 * queue moves on, because retrying it would block every message behind it forever.
 */
@Component
public class DeliveryWorker {

    private static final Logger log = LoggerFactory.getLogger(DeliveryWorker.class);

    private final OutboxRepository outbox;
    private final FhirSender sender;
    private final Clock clock;
    private final BackoffPolicy backoff;
    private final int maxAttempts;

    public DeliveryWorker(OutboxRepository outbox, FhirSender sender, Clock clock, GatewayProperties properties) {
        this.outbox = outbox;
        this.sender = sender;
        this.clock = clock;
        this.backoff = new BackoffPolicy(
                Duration.ofSeconds(properties.delivery().initialBackoffSeconds()),
                Duration.ofSeconds(properties.delivery().maxBackoffSeconds()));
        this.maxAttempts = properties.delivery().maxAttempts();
    }

    @Scheduled(fixedDelayString = "${gateway.delivery.poll-interval-ms}",
            initialDelayString = "${gateway.delivery.poll-interval-ms}")
    public void poll() {
        runOnce();
    }

    /** @return number of entries delivered in this pass */
    public synchronized int runOnce() {
        List<OutboxEntry> batch = outbox.findTop50ByStatusOrderByIdAsc(OutboxStatus.PENDING);
        int delivered = 0;
        for (OutboxEntry entry : batch) {
            Instant now = clock.instant();
            if (entry.getNextAttemptAt().isAfter(now)) {
                break; // head of the queue is backing off; keep order
            }
            try {
                sender.send(entry.getBundleJson());
                entry.markSent(clock.instant());
                outbox.save(entry);
                delivered++;
            } catch (FhirDeliveryException e) {
                if (e.isRetryable() && entry.getAttempts() + 1 < maxAttempts) {
                    Duration wait = backoff.delayAfter(entry.getAttempts() + 1);
                    entry.markRetry(e.getMessage(), now.plus(wait));
                    outbox.save(entry);
                    log.warn("Outbox {} ({} {}) attempt {} failed, retry in {}s: {}", entry.getId(),
                            entry.getMessageType(), entry.getMessageControlId(), entry.getAttempts(),
                            wait.getSeconds(), e.getMessage());
                    break;
                }
                entry.markDead(e.getMessage());
                outbox.save(entry);
                log.error("Outbox {} ({} {}) moved to dead letter after {} attempts: {}", entry.getId(),
                        entry.getMessageType(), entry.getMessageControlId(), entry.getAttempts(), e.getMessage());
            }
        }
        return delivered;
    }
}
