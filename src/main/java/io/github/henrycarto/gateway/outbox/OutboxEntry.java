package io.github.henrycarto.gateway.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;

/** One FHIR transaction bundle waiting to be delivered, written in the same DB transaction as the inbound message. */
@Entity
@Table(name = "outbox_entry", indexes = @Index(name = "ix_outbox_status_id", columnList = "status, id"))
public class OutboxEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "message_control_id", nullable = false, length = 199)
    private String messageControlId;

    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType;

    @Column(name = "entry_count", nullable = false)
    private int entryCount;

    @Column(name = "bundle_json", nullable = false, length = 1_000_000)
    private String bundleJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private OutboxStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error", length = 4000)
    private String lastError;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    protected OutboxEntry() {
    }

    public OutboxEntry(String messageControlId, String messageType, int entryCount, String bundleJson, Instant now) {
        this.messageControlId = messageControlId;
        this.messageType = messageType;
        this.entryCount = entryCount;
        this.bundleJson = bundleJson;
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    public void markSent(Instant now) {
        this.attempts++;
        this.status = OutboxStatus.SENT;
        this.sentAt = now;
        this.lastError = null;
    }

    public void markRetry(String error, Instant nextAttemptAt) {
        this.attempts++;
        this.lastError = truncate(error);
        this.nextAttemptAt = nextAttemptAt;
    }

    public void markDead(String error) {
        this.attempts++;
        this.status = OutboxStatus.DEAD;
        this.lastError = truncate(error);
    }

    /** Operator action after fixing the cause: put a dead entry back in the queue. */
    public void requeue(Instant now) {
        this.status = OutboxStatus.PENDING;
        this.attempts = 0;
        this.nextAttemptAt = now;
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 4000 ? s : s.substring(0, 4000);
    }

    public Long getId() {
        return id;
    }

    public String getMessageControlId() {
        return messageControlId;
    }

    public String getMessageType() {
        return messageType;
    }

    public int getEntryCount() {
        return entryCount;
    }

    public String getBundleJson() {
        return bundleJson;
    }

    public OutboxStatus getStatus() {
        return status;
    }

    public int getAttempts() {
        return attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getSentAt() {
        return sentAt;
    }
}
