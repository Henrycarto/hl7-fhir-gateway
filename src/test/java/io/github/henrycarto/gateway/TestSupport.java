package io.github.henrycarto.gateway;

import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.outbox.FhirDeliveryException;
import io.github.henrycarto.gateway.outbox.FhirSender;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

public final class TestSupport {

    private TestSupport() {
    }

    public static String message(String name) {
        try (InputStream in = TestSupport.class.getResourceAsStream("/messages/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("No test message " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static GatewayProperties properties() {
        return new GatewayProperties(
                "http://localhost:1/fhir",
                "https://example.org/fhir/sid/mrn",
                "https://example.org/fhir/sid/visit",
                "https://example.org/fhir/sid/hl7v2-observation",
                "https://example.org/fhir/CodeSystem/local-lab",
                "Europe/Vienna",
                new GatewayProperties.Mllp(false, 2575),
                new GatewayProperties.Delivery(3_600_000, 5, 600, 3, 15_000));
    }

    /** A clock tests can move forward to get past a backoff. */
    public static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T12:00:00Z");

        public void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Plays back scripted outcomes: null means success, an exception is thrown as is. */
    public static class ScriptedFhirSender implements FhirSender {
        private final Deque<FhirDeliveryException> script = new ArrayDeque<>();
        public final List<String> delivered = new ArrayList<>();

        public void then(FhirDeliveryException outcome) {
            script.addLast(outcome);
        }

        public void thenSucceed() {
            script.addLast(new FhirDeliveryException(false, 200, "__ok__"));
        }

        public void reset() {
            script.clear();
            delivered.clear();
        }

        @Override
        public void send(String bundleJson) throws FhirDeliveryException {
            FhirDeliveryException next = script.pollFirst();
            if (next == null || next.getStatusCode() == 200) {
                delivered.add(bundleJson);
                return;
            }
            throw next;
        }
    }

    @TestConfiguration
    public static class Config {
        @Bean
        @Primary
        public MutableClock testClock() {
            return new MutableClock();
        }

        @Bean
        @Primary
        public ScriptedFhirSender scriptedFhirSender() {
            return new ScriptedFhirSender();
        }
    }
}
