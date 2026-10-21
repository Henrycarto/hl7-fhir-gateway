package io.github.henrycarto.gateway.mapping;

import static io.github.henrycarto.gateway.TestSupport.message;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import io.github.henrycarto.gateway.TestSupport;
import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.hl7.Hl7Parser;
import io.github.henrycarto.gateway.hl7.MessageHeader;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AdtMapperTest {

    private static HapiContext context;
    private static Hl7Parser parser;
    private static final GatewayProperties props = TestSupport.properties();
    private final AdtMapper mapper = new AdtMapper(props, new PatientMapper(props));

    @BeforeAll
    static void setUp() {
        context = new DefaultHapiContext();
        context.setModelClassFactory(new CanonicalModelClassFactory("2.5"));
        context.setValidationContext(ValidationContextFactory.noValidation());
        parser = new Hl7Parser(context);
    }

    @AfterAll
    static void tearDown() throws Exception {
        context.close();
    }

    private Bundle map(String file) throws Exception {
        Message m = parser.parse(message(file));
        return mapper.map(m, MessageHeader.of(m).triggerEvent());
    }

    @Test
    void admitCreatesPatientAndInProgressInpatientEncounter() throws Exception {
        Bundle bundle = map("adt_a01.hl7");

        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTION);
        assertThat(bundle.getEntry()).hasSize(2);

        Bundle.BundleEntryComponent patientEntry = bundle.getEntry().get(0);
        Patient patient = (Patient) patientEntry.getResource();
        assertThat(patientEntry.getRequest().getMethod()).isEqualTo(Bundle.HTTPVerb.PUT);
        assertThat(patientEntry.getRequest().getUrl())
                .startsWith("Patient?identifier=")
                .contains("4711");
        assertThat(patient.getIdentifierFirstRep().getSystem()).isEqualTo(props.mrnSystem());
        assertThat(patient.getIdentifierFirstRep().getValue()).isEqualTo("4711");
        assertThat(patient.getNameFirstRep().getFamily()).isEqualTo("Huber");
        assertThat(patient.getNameFirstRep().getGiven()).extracting(Object::toString)
                .containsExactly("Margarete", "Anna");
        assertThat(patient.getBirthDateElement().getValueAsString()).isEqualTo("1958-03-14");
        assertThat(patient.getGender()).isEqualTo(AdministrativeGender.FEMALE);
        assertThat(patient.getAddressFirstRep().getCity()).isEqualTo("Wien");
        assertThat(patient.getAddressFirstRep().getPostalCode()).isEqualTo("1060");

        Encounter encounter = (Encounter) bundle.getEntry().get(1).getResource();
        assertThat(encounter.getIdentifierFirstRep().getValue()).isEqualTo("V0001");
        assertThat(encounter.getStatus()).isEqualTo(Encounter.EncounterStatus.INPROGRESS);
        assertThat(encounter.getClass_().getCode()).isEqualTo("IMP");
        assertThat(encounter.getSubject().getReference()).isEqualTo(patientEntry.getFullUrl());
        assertThat(encounter.getPeriod().getStartElement().getValueAsString()).startsWith("2026-10-20T14:25:00");
        assertThat(encounter.getLocationFirstRep().getLocation().getDisplay()).isEqualTo("INT / 204 / B");
    }

    @Test
    void dischargeFinishesTheSameEncounter() throws Exception {
        Bundle admit = map("adt_a01.hl7");
        Bundle discharge = map("adt_a03.hl7");

        Encounter encounter = (Encounter) discharge.getEntry().get(1).getResource();
        assertThat(encounter.getStatus()).isEqualTo(Encounter.EncounterStatus.FINISHED);
        assertThat(encounter.getPeriod().hasEnd()).isTrue();
        // Same visit number means same conditional URL and same fullUrl: A03 updates the A01 encounter.
        assertThat(discharge.getEntry().get(1).getRequest().getUrl())
                .isEqualTo(admit.getEntry().get(1).getRequest().getUrl());
        assertThat(discharge.getEntry().get(1).getFullUrl()).isEqualTo(admit.getEntry().get(1).getFullUrl());
    }

    @Test
    void updateTouchesPatientOnly() throws Exception {
        Bundle bundle = map("adt_a08.hl7");
        assertThat(bundle.getEntry()).hasSize(1);
        Patient patient = (Patient) bundle.getEntry().get(0).getResource();
        assertThat(patient.getAddressFirstRep().getLine().get(0).getValue()).isEqualTo("Gumpendorfer Strasse 12");
    }

    @Test
    void missingPatientIdentifierIsAMappingError() throws Exception {
        assertThatThrownBy(() -> map("adt_a01_missing_pid3.hl7"))
                .isInstanceOf(MappingException.class)
                .hasMessageContaining("PID-3");
    }

    @Test
    void patientClassMapping() {
        assertThat(AdtMapper.encounterClass("I").getCode()).isEqualTo("IMP");
        assertThat(AdtMapper.encounterClass("E").getCode()).isEqualTo("EMER");
        assertThat(AdtMapper.encounterClass("O").getCode()).isEqualTo("AMB");
        assertThat(AdtMapper.encounterClass(null).getCode()).isEqualTo("AMB");
    }
}
