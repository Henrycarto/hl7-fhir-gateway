package io.github.henrycarto.gateway.mapping;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_ORDER_OBSERVATION;
import ca.uhn.hl7v2.model.v25.group.ORU_R01_PATIENT_RESULT;
import ca.uhn.hl7v2.model.v25.message.ORU_R01;
import ca.uhn.hl7v2.model.v25.segment.OBR;
import ca.uhn.hl7v2.model.v25.segment.OBX;
import ca.uhn.hl7v2.model.v25.segment.PID;
import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.hl7.Hl7Dates;
import io.github.henrycarto.gateway.hl7.MessageHeader;
import java.math.BigDecimal;
import java.time.ZoneId;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.StringType;
import org.springframework.stereotype.Component;

/**
 * ORU^R01 lab results: one FHIR Observation per OBX.
 *
 * Each Observation gets a stable business identifier
 * (facility | filler order or control id | OBX set id | OBX code),
 * so a corrected result (OBX-11 = C) replaces the original instead of duplicating it.
 */
@Component
public class OruMapper implements MessageMapper {

    static final String LOINC = "http://loinc.org";
    static final String UCUM = "http://unitsofmeasure.org";
    static final String CATEGORY_SYSTEM = "http://terminology.hl7.org/CodeSystem/observation-category";
    static final String INTERPRETATION_SYSTEM =
            "http://terminology.hl7.org/CodeSystem/v3-ObservationInterpretation";

    private final GatewayProperties properties;
    private final PatientMapper patientMapper;

    public OruMapper(GatewayProperties properties, PatientMapper patientMapper) {
        this.properties = properties;
        this.patientMapper = patientMapper;
    }

    @Override
    public boolean supports(String messageCode, String triggerEvent) {
        return "ORU".equals(messageCode) && "R01".equals(triggerEvent);
    }

    @Override
    public Bundle map(Message message, String triggerEvent) throws HL7Exception {
        if (!(message instanceof ORU_R01 oru)) {
            throw new MappingException("ORU^R01 did not parse into the ORU_R01 structure");
        }
        MessageHeader header = MessageHeader.of(message);
        ZoneId zone = ZoneId.of(properties.defaultTimezone());
        TransactionBuilder tx = new TransactionBuilder();
        int observations = 0;

        for (int p = 0; p < oru.getPATIENT_RESULTReps(); p++) {
            ORU_R01_PATIENT_RESULT result = oru.getPATIENT_RESULT(p);
            PID pid = result.getPATIENT().getPID();
            Patient patient = patientMapper.map(pid);
            String mrn = patient.getIdentifierFirstRep().getValue();
            Reference patientRef = tx.ensureExists(patient, properties.mrnSystem(), mrn);

            for (int o = 0; o < result.getORDER_OBSERVATIONReps(); o++) {
                ORU_R01_ORDER_OBSERVATION order = result.getORDER_OBSERVATION(o);
                OBR obr = order.getOBR();
                String orderKey = firstNonBlank(
                        obr.getFillerOrderNumber().getEntityIdentifier().getValue(),
                        header.controlId() + "-" + (o + 1));
                String orderTime = obr.getObservationDateTime().getTime().getValue();

                for (int x = 0; x < order.getOBSERVATIONReps(); x++) {
                    ORU_R01_OBSERVATION obs = order.getOBSERVATION(x);
                    OBX obx = obs.getOBX();
                    Observation observation = observation(obx, orderTime, zone, patientRef);
                    String identifier = String.join("|",
                            header.sendingFacility(),
                            orderKey,
                            firstNonBlank(obx.getSetIDOBX().getValue(), String.valueOf(x + 1)),
                            observation.getCode().getCodingFirstRep().getCode());
                    observation.addIdentifier()
                            .setSystem(properties.observationSystem())
                            .setValue(identifier);
                    tx.upsert(observation, properties.observationSystem(), identifier);
                    observations++;
                }
            }
        }
        if (observations == 0) {
            throw new MappingException("ORU^R01 contains no OBX segments");
        }
        return tx.build();
    }

    Observation observation(OBX obx, String orderTime, ZoneId zone, Reference patientRef) throws HL7Exception {
        String code = obx.getObservationIdentifier().getIdentifier().getValue();
        if (code == null || code.isBlank()) {
            throw new MappingException("OBX-3 observation identifier is missing");
        }
        String text = obx.getObservationIdentifier().getText().getValue();
        String codingSystem = obx.getObservationIdentifier().getNameOfCodingSystem().getValue();
        String system = "LN".equalsIgnoreCase(codingSystem) ? LOINC : properties.localCodeSystem();

        Observation observation = new Observation();
        observation.setStatus(status(obx.getObservationResultStatus().getValue()));
        observation.addCategory(new CodeableConcept(new Coding(CATEGORY_SYSTEM, "laboratory", "Laboratory")));
        observation.setCode(new CodeableConcept(new Coding(system, code.trim(), text)).setText(text));
        observation.setSubject(patientRef);

        String effective = firstNonBlank(obx.getDateTimeOfTheObservation().getTime().getValue(), orderTime);
        if (effective != null) {
            observation.setEffective(Hl7Dates.toDateTime(effective, zone));
        }

        if (obx.getObservationValueReps() > 0 && obx.getObservationValue(0).getData() != null) {
            String raw = obx.getObservationValue(0).getData().encode();
            if (raw != null && !raw.isBlank()) {
                String valueType = obx.getValueType().getValue();
                String unit = obx.getUnits().getIdentifier().getValue();
                observation.setValue(value(valueType, raw.trim(), unit));
            }
        }

        String range = obx.getReferencesRange().getValue();
        if (range != null && !range.isBlank()) {
            observation.addReferenceRange().setText(range.trim());
        }

        if (obx.getAbnormalFlagsReps() > 0) {
            String flag = obx.getAbnormalFlags(0).getValue();
            if (flag != null && !flag.isBlank()) {
                observation.addInterpretation(
                        new CodeableConcept(new Coding(INTERPRETATION_SYSTEM, flag.trim().toUpperCase(), null)));
            }
        }
        return observation;
    }

    static org.hl7.fhir.r4.model.Type value(String valueType, String raw, String unit) {
        if ("NM".equalsIgnoreCase(valueType)) {
            try {
                Quantity q = new Quantity().setValue(new BigDecimal(raw));
                if (unit != null && !unit.isBlank()) {
                    q.setUnit(unit.trim()).setSystem(UCUM).setCode(unit.trim());
                }
                return q;
            } catch (NumberFormatException e) {
                // Labs send things like "<0.5" in NM fields. Keep the text rather than reject the result.
                return new StringType(unit == null || unit.isBlank() ? raw : raw + " " + unit.trim());
            }
        }
        if ("CE".equalsIgnoreCase(valueType) || "CWE".equalsIgnoreCase(valueType)) {
            String[] parts = raw.split("\\^", -1);
            String text = parts.length > 1 && !parts[1].isBlank() ? parts[1] : parts[0];
            return new CodeableConcept().setText(text);
        }
        return new StringType(raw);
    }

    static Observation.ObservationStatus status(String obx11) {
        if (obx11 == null) {
            return Observation.ObservationStatus.UNKNOWN;
        }
        return switch (obx11.trim().toUpperCase()) {
            case "F" -> Observation.ObservationStatus.FINAL;
            case "P", "R", "S" -> Observation.ObservationStatus.PRELIMINARY;
            case "C" -> Observation.ObservationStatus.CORRECTED;
            case "X" -> Observation.ObservationStatus.CANCELLED;
            case "D", "W" -> Observation.ObservationStatus.ENTEREDINERROR;
            default -> Observation.ObservationStatus.UNKNOWN;
        };
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a.trim();
        }
        return b == null || b.isBlank() ? null : b.trim();
    }
}
