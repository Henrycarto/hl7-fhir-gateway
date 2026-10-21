package io.github.henrycarto.gateway.outbox;

/**
 * @see FhirSender
 */
public class FhirDeliveryException extends Exception {

    private final boolean retryable;
    private final int statusCode;

    public FhirDeliveryException(boolean retryable, int statusCode, String message, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.statusCode = statusCode;
    }

    public FhirDeliveryException(boolean retryable, int statusCode, String message) {
        this(retryable, statusCode, message, null);
    }

    /** True for outages (connection refused, timeouts, 5xx, 429). False when the server rejected the content. */
    public boolean isRetryable() {
        return retryable;
    }

    /** HTTP status, or 0 when no response was received. */
    public int getStatusCode() {
        return statusCode;
    }
}
