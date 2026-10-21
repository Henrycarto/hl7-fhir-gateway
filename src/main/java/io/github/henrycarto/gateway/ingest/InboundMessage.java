package io.github.henrycarto.gateway.ingest;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;

/**
 * Every accepted HL7 message, kept verbatim. The unique key is what makes resends safe:
 * interface engines resend on timeout, and the same MSH-10 from the same sender
 * must never produce a second set of writes.
 */
@Entity
@Table(name = "inbound_message",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_inbound_sender_control_id",
                columnNames = {"sending_application", "sending_facility", "control_id"}))
public class InboundMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sending_application", nullable = false, length = 180)
    private String sendingApplication;

    @Column(name = "sending_facility", nullable = false, length = 180)
    private String sendingFacility;

    @Column(name = "control_id", nullable = false, length = 199)
    private String controlId;

    @Column(name = "message_type", nullable = false, length = 20)
    private String messageType;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "raw_message", nullable = false, length = 1_000_000)
    private String rawMessage;

    protected InboundMessage() {
    }

    public InboundMessage(String sendingApplication, String sendingFacility, String controlId,
                          String messageType, Instant receivedAt, String rawMessage) {
        this.sendingApplication = sendingApplication;
        this.sendingFacility = sendingFacility;
        this.controlId = controlId;
        this.messageType = messageType;
        this.receivedAt = receivedAt;
        this.rawMessage = rawMessage;
    }

    public Long getId() {
        return id;
    }

    public String getSendingApplication() {
        return sendingApplication;
    }

    public String getSendingFacility() {
        return sendingFacility;
    }

    public String getControlId() {
        return controlId;
    }

    public String getMessageType() {
        return messageType;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public String getRawMessage() {
        return rawMessage;
    }
}
