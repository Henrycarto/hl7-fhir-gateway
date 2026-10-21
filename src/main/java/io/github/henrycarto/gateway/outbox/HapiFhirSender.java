package io.github.henrycarto.gateway.outbox;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.DataFormatException;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.exceptions.FhirClientConnectionException;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import io.github.henrycarto.gateway.config.GatewayProperties;
import org.hl7.fhir.instance.model.api.IBaseOperationOutcome;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.stereotype.Component;

@Component
public class HapiFhirSender implements FhirSender {

    private final FhirContext fhir;
    private final IGenericClient client;

    public HapiFhirSender(FhirContext fhir, GatewayProperties properties) {
        this.fhir = fhir;
        this.client = fhir.newRestfulGenericClient(properties.fhirBaseUrl());
    }

    @Override
    public void send(String bundleJson) throws FhirDeliveryException {
        Bundle bundle;
        try {
            bundle = fhir.newJsonParser().parseResource(Bundle.class, bundleJson);
        } catch (DataFormatException e) {
            throw new FhirDeliveryException(false, 0, "Stored bundle is not valid FHIR JSON: " + e.getMessage(), e);
        }

        try {
            client.transaction().withBundle(bundle).execute();
        } catch (FhirClientConnectionException e) {
            throw new FhirDeliveryException(true, 0, "FHIR server unreachable: " + e.getMessage(), e);
        } catch (BaseServerResponseException e) {
            int status = e.getStatusCode();
            boolean retryable = status >= 500 || status == 429 || status == 408;
            throw new FhirDeliveryException(retryable, status, "HTTP " + status + ": " + describe(e), e);
        }
    }

    private String describe(BaseServerResponseException e) {
        IBaseOperationOutcome outcome = e.getOperationOutcome();
        if (outcome != null) {
            return fhir.newJsonParser().encodeResourceToString(outcome);
        }
        return e.getMessage();
    }
}
