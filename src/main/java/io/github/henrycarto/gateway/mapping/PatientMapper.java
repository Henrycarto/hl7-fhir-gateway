package io.github.henrycarto.gateway.mapping;

import ca.uhn.hl7v2.model.v25.datatype.CX;
import ca.uhn.hl7v2.model.v25.datatype.XAD;
import ca.uhn.hl7v2.model.v25.datatype.XPN;
import ca.uhn.hl7v2.model.v25.segment.PID;
import io.github.henrycarto.gateway.config.GatewayProperties;
import io.github.henrycarto.gateway.hl7.Hl7Dates;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.Enumerations.AdministrativeGender;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/** PID segment to FHIR Patient. Shared by ADT and ORU. */
@Component
public class PatientMapper {

    private final GatewayProperties properties;

    public PatientMapper(GatewayProperties properties) {
        this.properties = properties;
    }

    /** PID-3: prefer the repetition typed MR, else the first one with a value. */
    public String medicalRecordNumber(PID pid) {
        CX[] ids = pid.getPatientIdentifierList();
        String first = null;
        for (CX cx : ids) {
            String value = cx.getIDNumber().getValue();
            if (value == null || value.isBlank()) {
                continue;
            }
            if ("MR".equalsIgnoreCase(cx.getIdentifierTypeCode().getValue())) {
                return value.trim();
            }
            if (first == null) {
                first = value.trim();
            }
        }
        if (first == null) {
            throw new MappingException("PID-3 patient identifier is missing");
        }
        return first;
    }

    public Patient map(PID pid) {
        Patient patient = new Patient();
        patient.addIdentifier()
                .setSystem(properties.mrnSystem())
                .setValue(medicalRecordNumber(pid));

        for (XPN xpn : pid.getPatientName()) {
            String family = xpn.getFamilyName().getSurname().getValue();
            String given = xpn.getGivenName().getValue();
            if (isBlank(family) && isBlank(given)) {
                continue;
            }
            HumanName name = patient.addName().setUse(HumanName.NameUse.OFFICIAL);
            if (!isBlank(family)) {
                name.setFamily(family.trim());
            }
            if (!isBlank(given)) {
                name.addGiven(given.trim());
            }
            String middle = xpn.getSecondAndFurtherGivenNamesOrInitialsThereof().getValue();
            if (!isBlank(middle)) {
                name.addGiven(middle.trim());
            }
        }

        String birth = pid.getDateTimeOfBirth().getTime().getValue();
        if (!isBlank(birth)) {
            patient.setBirthDateElement(Hl7Dates.toDate(birth.length() > 8 ? birth.substring(0, 8) : birth));
        }

        patient.setGender(gender(pid.getAdministrativeSex().getValue()));

        for (XAD xad : pid.getPatientAddress()) {
            String street = xad.getStreetAddress().getStreetOrMailingAddress().getValue();
            String city = xad.getCity().getValue();
            if (isBlank(street) && isBlank(city)) {
                continue;
            }
            Address address = patient.addAddress();
            if (!isBlank(street)) {
                address.addLine(street.trim());
            }
            address.setCity(trimOrNull(city));
            address.setPostalCode(trimOrNull(xad.getZipOrPostalCode().getValue()));
            address.setCountry(trimOrNull(xad.getCountry().getValue()));
        }
        return patient;
    }

    static AdministrativeGender gender(String hl7) {
        if (hl7 == null) {
            return AdministrativeGender.UNKNOWN;
        }
        return switch (hl7.trim().toUpperCase()) {
            case "M" -> AdministrativeGender.MALE;
            case "F" -> AdministrativeGender.FEMALE;
            case "O", "A", "N" -> AdministrativeGender.OTHER;
            default -> AdministrativeGender.UNKNOWN;
        };
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(String s) {
        return isBlank(s) ? null : s.trim();
    }
}
