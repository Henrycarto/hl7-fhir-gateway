package io.github.henrycarto.gateway.outbox;

import java.time.Duration;

/** Exponential backoff: initial, 2x, 4x ... capped at max. Attempt numbers start at 1. */
public record BackoffPolicy(Duration initial, Duration max) {

    public Duration delayAfter(int attempt) {
        if (attempt < 1) {
            return initial;
        }
        int shift = Math.min(attempt - 1, 30);
        long seconds = initial.getSeconds() << shift;
        if (seconds <= 0 || seconds > max.getSeconds()) {
            return max;
        }
        return Duration.ofSeconds(seconds);
    }
}
