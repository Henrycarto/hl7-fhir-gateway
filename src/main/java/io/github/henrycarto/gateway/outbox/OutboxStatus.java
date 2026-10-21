package io.github.henrycarto.gateway.outbox;

public enum OutboxStatus {
    /** Waiting for its first or next delivery attempt. */
    PENDING,
    /** Accepted by the FHIR server. */
    SENT,
    /** Rejected by the FHIR server, or out of retries. Needs a human. */
    DEAD
}
