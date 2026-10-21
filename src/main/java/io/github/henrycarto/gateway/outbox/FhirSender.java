package io.github.henrycarto.gateway.outbox;

/** Posts one transaction bundle to the FHIR server. A seam so delivery logic is testable without a server. */
public interface FhirSender {

    void send(String bundleJson) throws FhirDeliveryException;
}
