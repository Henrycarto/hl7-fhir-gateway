package io.github.henrycarto.gateway.mapping;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.segment.PID;
import ca.uhn.hl7v2.model.v25.segment.PV1;
import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.hl7.Hl7Dates;
import java.time.ZoneId;
import java.util.Set;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Period;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

/**
 * ADT A01 (admit), A04 (register outpatient), A03 (discharge), A08 (update patient).
 *
 * A01/A04/A03 upsert Patient and Encounter. A08 upserts Patient only: it carries
 * demographic corrections, and replacing the Encounter from it would risk resetting
 * the visit status that A01/A03 set.
 */
@Component
public class AdtMapper implements MessageMapper {

    static final String ACT_CODE_SYSTEM = "http://terminology.hl7.org/CodeSystem/v3-ActCode";
    private static final Set<String> TRIGGERS = Set.of("A01", "A03", "A04", "A08");

    private final GatewayProperties properties;
    private final PatientMapper patientMapper;

    public AdtMapper(GatewayProperties properties, PatientMapper patientMapper) {
        this.properties = properties;
        this.patientMapper = patientMapper;
    }

    @Override
    public boolean supports(String messageCode, String triggerEvent) {
        return "ADT".equals(messageCode) && TRIGGERS.contains(triggerEvent);
    }

    @Override
    public Bundle map(Message message, String triggerEvent) throws HL7Exception {
        PID pid = (PID) message.get("PID");
        TransactionBuilder tx = new TransactionBuilder();

        Patient patient = patientMapper.map(pid);
        String mrn = patient.getIdentifierFirstRep().getValue();
        Reference patientRef = tx.upsert(patient, properties.mrnSystem(), mrn);

        if (!"A08".equals(triggerEvent)) {
            PV1 pv1 = (PV1) message.get("PV1");
            Encounter encounter = encounter(pv1, triggerEvent, patientRef);
            tx.upsert(encounter, properties.visitSystem(), encounter.getIdentifierFirstRep().getValue());
        }
        return tx.build();
    }

    Encounter encounter(PV1 pv1, String trigger, Reference patientRef) {
        String visitNumber = pv1.getVisitNumber().getIDNumber().getValue();
        if (visitNumber == null || visitNumber.isBlank()) {
            throw new MappingException("PV1-19 visit number is missing");
        }
        ZoneId zone = ZoneId.of(properties.defaultTimezone());

        Encounter encounter = new Encounter();
        encounter.addIdentifier().setSystem(properties.visitSystem()).setValue(visitNumber.trim());
        encounter.setStatus("A03".equals(trigger)
                ? Encounter.EncounterStatus.FINISHED
                : Encounter.EncounterStatus.INPROGRESS);
        encounter.setClass_(encounterClass(pv1.getPatientClass().getValue()));
        encounter.setSubject(patientRef);

        Period period = new Period();
        String admit = pv1.getAdmitDateTime().getTime().getValue();
        if (admit != null && !admit.isBlank()) {
            period.setStartElement(Hl7Dates.toDateTime(admit, zone));
        }
        if (pv1.getDischargeDateTimeReps() > 0) {
            String discharge = pv1.getDischargeDateTime(0).getTime().getValue();
            if (discharge != null && !discharge.isBlank()) {
                period.setEndElement(Hl7Dates.toDateTime(discharge, zone));
            }
        }
        if (!period.isEmpty()) {
            encounter.setPeriod(period);
        }

        String location = location(pv1);
        if (location != null) {
            encounter.addLocation().setLocation(new Reference().setDisplay(location));
        }
        return encounter;
    }

    static Coding encounterClass(String patientClass) {
        String pc = patientClass == null ? "" : patientClass.trim().toUpperCase();
        return switch (pc) {
            case "I" -> new Coding(ACT_CODE_SYSTEM, "IMP", "inpatient encounter");
            case "E" -> new Coding(ACT_CODE_SYSTEM, "EMER", "emergency");
            case "O" -> new Coding(ACT_CODE_SYSTEM, "AMB", "ambulatory");
            case "P" -> new Coding(ACT_CODE_SYSTEM, "PRENC", "pre-admission");
            default -> new Coding(ACT_CODE_SYSTEM, "AMB", "ambulatory");
        };
    }

    private static String location(PV1 pv1) {
        String ward = pv1.getAssignedPatientLocation().getPointOfCare().getValue();
        String room = pv1.getAssignedPatientLocation().getRoom().getValue();
        String bed = pv1.getAssignedPatientLocation().getBed().getValue();
        StringBuilder sb = new StringBuilder();
        for (String part : new String[] {ward, room, bed}) {
            if (part != null && !part.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append(" / ");
                }
                sb.append(part.trim());
            }
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
