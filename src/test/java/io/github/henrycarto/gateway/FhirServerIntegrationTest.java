package io.github.henrycarto.gateway;

import static io.github.henrycarto.gateway.TestSupport.message;
import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.hl7.Hl7Parser;
import io.github.henrycarto.gateway.hl7.MessageHeader;
import io.github.henrycarto.gateway.mapping.AdtMapper;
import io.github.henrycarto.gateway.mapping.OruMapper;
import io.github.henrycarto.gateway.mapping.PatientMapper;
import io.github.henrycarto.gateway.outbox.HapiFhirSender;
import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import java.time.Duration;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Runs the mapped bundles against a real HAPI FHIR JPA server in Docker.
 * Proves the conditional PUT / conditional create design holds on an actual server:
 * replaying messages does not create duplicates, and a thin PID in an ORU does not
 * overwrite the demographics from ADT.
 *
 * mvn verify -Pintegration
 */
@Tag("integration")
@Testcontainers
class FhirServerIntegrationTest {

    @Container
    static final GenericContainer<?> FHIR = new GenericContainer<>("hapiproject/hapi:v7.4.0")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/fhir/metadata").forStatusCode(200)
                    .withStartupTimeout(Duration.ofMinutes(4)));

    @Test
    void replaysAreIdempotentAndOruDoesNotClobberAdtDemographics() throws Exception {
        String base = "http://" + FHIR.getHost() + ":" + FHIR.getMappedPort(8080) + "/fhir";
        GatewayProperties defaults = TestSupport.properties();
        GatewayProperties props = new GatewayProperties(base, defaults.mrnSystem(), defaults.visitSystem(),
                defaults.observationSystem(), defaults.localCodeSystem(), defaults.defaultTimezone(),
                defaults.mllp(), defaults.delivery());

        FhirContext fhir = FhirContext.forR4();
        HapiFhirSender sender = new HapiFhirSender(fhir, props);
        PatientMapper patients = new PatientMapper(props);
        AdtMapper adt = new AdtMapper(props, patients);
        OruMapper oru = new OruMapper(props, patients);

        try (HapiContext hl7 = new DefaultHapiContext()) {
            hl7.setModelClassFactory(new CanonicalModelClassFactory("2.5"));
            hl7.setValidationContext(ValidationContextFactory.noValidation());
            Hl7Parser parser = new Hl7Parser(hl7);

            for (int replay = 0; replay < 2; replay++) {
                for (String file : new String[] {"adt_a01.hl7", "oru_r01.hl7", "adt_a03.hl7"}) {
                    Message m = parser.parse(message(file));
                    MessageHeader h = MessageHeader.of(m);
                    Bundle b = "ORU".equals(h.messageCode()) ? oru.map(m, h.triggerEvent()) : adt.map(m, h.triggerEvent());
                    sender.send(fhir.newJsonParser().encodeResourceToString(b));
                }
            }
        }

        IGenericClient client = fhir.newRestfulGenericClient(base);
        Bundle patientSearch = client.search().forResource(Patient.class).returnBundle(Bundle.class).execute();
        Bundle encounterSearch = client.search().forResource(Encounter.class).returnBundle(Bundle.class).execute();
        Bundle observationSearch = client.search().forResource(Observation.class).returnBundle(Bundle.class).execute();

        assertThat(patientSearch.getTotal()).isEqualTo(1);
        assertThat(encounterSearch.getTotal()).isEqualTo(1);
        assertThat(observationSearch.getTotal()).isEqualTo(4);

        Patient patient = (Patient) patientSearch.getEntryFirstRep().getResource();
        assertThat(patient.getAddress()).isNotEmpty(); // from ADT, not wiped by the thinner ORU PID
        Encounter encounter = (Encounter) encounterSearch.getEntryFirstRep().getResource();
        assertThat(encounter.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);
    }
}
