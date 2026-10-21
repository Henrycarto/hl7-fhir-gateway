package io.github.henrycarto.gateway.mapping;

/** The message is a supported type but is missing data the mapping needs. Answered with AE. */
public class MappingException extends RuntimeException {

    public MappingException(String message) {
        super(message);
    }

    public MappingException(String message, Throwable cause) {
        super(message, cause);
    }
}
