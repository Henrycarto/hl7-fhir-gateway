package io.github.henrycarto.gateway.mapping;

import static io.github.henrycarto.gateway.TestSupport.message;
import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.hl7v2.DefaultHapiContext;
import ca.uhn.hl7v2.HapiContext;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.parser.CanonicalModelClassFactory;
import ca.uhn.hl7v2.validation.impl.ValidationContextFactory;
import io.github.henrycarto.gateway.TestSupport;
import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.hl7.Hl7Parser;
import java.math.BigDecimal;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class OruMapperTest {

    private static HapiContext context;
    private static Hl7Parser parser;
    private static final GatewayProperties props = TestSupport.properties();
    private final OruMapper mapper = new OruMapper(props, new PatientMapper(props));

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

    private Bundle map() throws Exception {
        Message m = parser.parse(message("oru_r01.hl7"));
        return mapper.map(m, "R01");
    }

    @Test
    void patientIsCreatedOnlyIfMissing() throws Exception {
        Bundle.BundleEntryComponent patient = map().getEntry().get(0);
        assertThat(patient.getRequest().getMethod()).isEqualTo(Bundle.HTTPVerb.POST);
        assertThat(patient.getRequest().getIfNoneExist()).startsWith("identifier=").contains("4711");
    }

    @Test
    void oneObservationPerObx() throws Exception {
        Bundle bundle = map();
        List<Observation> observations = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(Observation.class::isInstance)
                .map(Observation.class::cast)
                .toList();

        assertThat(observations).hasSize(4);
        assertThat(observations).extracting(o -> o.getCode().getCodingFirstRep().getCode())
                .containsExactly("718-7", "6690-2", "777-3", "2345-7");
        assertThat(observations).allSatisfy(o -> {
            assertThat(o.getCode().getCodingFirstRep().getSystem()).isEqualTo(OruMapper.LOINC);
            assertThat(o.getStatus()).isEqualTo(Observation.ObservationStatus.FINAL);
            assertThat(o.getCategoryFirstRep().getCodingFirstRep().getCode()).isEqualTo("laboratory");
            assertThat(o.getSubject().getReference()).isEqualTo(bundle.getEntry().get(0).getFullUrl());
        });
    }

    @Test
    void numericValuesBecomeUcumQuantities() throws Exception {
        Observation hgb = (Observation) map().getEntry().get(1).getResource();
        Quantity q = hgb.getValueQuantity();
        assertThat(q.getValue()).isEqualByComparingTo(new BigDecimal("13.2"));
        assertThat(q.getUnit()).isEqualTo("g/dL");
        assertThat(q.getSystem()).isEqualTo(OruMapper.UCUM);
        assertThat(hgb.getReferenceRangeFirstRep().getText()).isEqualTo("12.0-16.0");
        assertThat(hgb.getInterpretationFirstRep().getCodingFirstRep().getCode()).isEqualTo("N");
    }

    @Test
    void effectiveTimeFromObxThenFallsBackToObr() throws Exception {
        Bundle bundle = map();
        Observation withObx14 = (Observation) bundle.getEntry().get(1).getResource();
        Observation withoutObx14 = (Observation) bundle.getEntry().get(2).getResource();
        assertThat(withObx14.getEffectiveDateTimeType().getValueAsString()).startsWith("2026-10-20T15:55:00");
        assertThat(withoutObx14.getEffectiveDateTimeType().getValueAsString()).startsWith("2026-10-20T15:30:00");
    }

    @Test
    void observationIdentifierIsStableAcrossReplays() throws Exception {
        Bundle first = map();
        Bundle second = map();
        assertThat(second.getEntry()).extracting(e -> e.getRequest().getUrl())
                .containsExactlyElementsOf(first.getEntry().stream().map(e -> e.getRequest().getUrl()).toList());
        Observation hgb = (Observation) first.getEntry().get(1).getResource();
        assertThat(hgb.getIdentifierFirstRep().getValue()).isEqualTo("WIEN_KH|LAB98765|1|718-7");
    }

    @Test
    void nonNumericNmValueIsKeptAsText() {
        assertThat(OruMapper.value("NM", "<0.5", "mg/L")).isInstanceOf(StringType.class);
        assertThat(((StringType) OruMapper.value("NM", "<0.5", "mg/L")).getValue()).isEqualTo("<0.5 mg/L");
    }

    @Test
    void resultStatusMapping() {
        assertThat(OruMapper.status("C")).isEqualTo(Observation.ObservationStatus.CORRECTED);
        assertThat(OruMapper.status("P")).isEqualTo(Observation.ObservationStatus.PRELIMINARY);
        assertThat(OruMapper.status("X")).isEqualTo(Observation.ObservationStatus.CANCELLED);
        assertThat(OruMapper.status("D")).isEqualTo(Observation.ObservationStatus.ENTEREDINERROR);
        assertThat(OruMapper.status(null)).isEqualTo(Observation.ObservationStatus.UNKNOWN);
    }
}
