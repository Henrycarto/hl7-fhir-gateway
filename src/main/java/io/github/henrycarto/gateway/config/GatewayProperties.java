package io.github.henrycarto.gateway.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Everything that differs between hospitals lives here, not in code:
 * the FHIR endpoint, the identifier systems and the retry policy.
 */
@Validated
@ConfigurationProperties(prefix = "gateway")
public record GatewayProperties(
        @NotBlank String fhirBaseUrl,
        @NotBlank String mrnSystem,
        @NotBlank String visitSystem,
        @NotBlank String observationSystem,
        @NotBlank String localCodeSystem,
        @NotBlank String defaultTimezone,
        Mllp mllp,
        Delivery delivery) {

    public record Mllp(boolean enabled, @Min(1) int port) {
    }

    public record Delivery(
            @Min(100) long pollIntervalMs,
            @Min(1) long initialBackoffSeconds,
            @Min(1) long maxBackoffSeconds,
            @Min(1) int maxAttempts,
            @Min(100) int requestTimeoutMs) {
    }
}
