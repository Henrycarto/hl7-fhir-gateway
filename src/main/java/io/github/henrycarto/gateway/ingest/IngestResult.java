package io.github.henrycarto.gateway.ingest;

import ca.uhn.hl7v2.AcknowledgmentCode;
import ca.uhn.hl7v2.model.Message;

/**
 * @param code      AA accepted (stored and queued), AE error in content, AR rejected
 * @param ack       the ACK as a HAPI message, null only when the input could not be parsed at all
 * @param ackText   the ACK encoded as ER7, always present
 * @param duplicate true when this control id was already accepted and nothing new was queued
 * @param detail    human-readable reason for AE/AR
 */
public record IngestResult(
        AcknowledgmentCode code,
        Message ack,
        String ackText,
        boolean duplicate,
        String detail) {
}
