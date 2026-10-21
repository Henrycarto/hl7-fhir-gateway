package io.github.henrycarto.gateway.api;

import io.github.henrycarto.gateway.outbox.OutboxEntry;
import io.github.henrycarto.gateway.outbox.OutboxRepository;
import io.github.henrycarto.gateway.outbox.OutboxStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** What an integration engineer on call needs: how much is stuck, why, and a way to replay it. */
@RestController
@RequestMapping("/api/outbox")
public class OutboxController {

    private final OutboxRepository outbox;
    private final Clock clock;

    public OutboxController(OutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    @GetMapping("/summary")
    public Map<String, Long> summary() {
        return Map.of(
                "pending", outbox.countByStatus(OutboxStatus.PENDING),
                "sent", outbox.countByStatus(OutboxStatus.SENT),
                "dead", outbox.countByStatus(OutboxStatus.DEAD));
    }

    @GetMapping("/dead")
    public List<DeadEntry> dead() {
        return outbox.findTop100ByStatusOrderByIdDesc(OutboxStatus.DEAD).stream()
                .map(DeadEntry::of)
                .toList();
    }

    @PostMapping("/{id}/retry")
    @Transactional
    public ResponseEntity<DeadEntry> retry(@PathVariable long id) {
        return outbox.findById(id)
                .filter(e -> e.getStatus() == OutboxStatus.DEAD)
                .map(e -> {
                    e.requeue(clock.instant());
                    return ResponseEntity.ok(DeadEntry.of(outbox.save(e)));
                })
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    public record DeadEntry(long id, String messageType, String messageControlId, String status,
                            int attempts, String lastError, Instant createdAt) {
        static DeadEntry of(OutboxEntry e) {
            return new DeadEntry(e.getId(), e.getMessageType(), e.getMessageControlId(), e.getStatus().name(),
                    e.getAttempts(), e.getLastError(), e.getCreatedAt());
        }
    }
}
