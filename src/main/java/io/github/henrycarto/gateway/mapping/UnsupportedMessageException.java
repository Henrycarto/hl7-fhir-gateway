package io.github.henrycarto.gateway.mapping;

/** The gateway does not handle this message type or trigger event. Answered with AR. */
public class UnsupportedMessageException extends RuntimeException {

    public UnsupportedMessageException(String message) {
        super(message);
    }
}
