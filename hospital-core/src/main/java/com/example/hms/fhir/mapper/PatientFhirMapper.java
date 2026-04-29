package com.example.hms.fhir.mapper;

import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import org.hl7.fhir.r4.model.Address;
import org.hl7.fhir.r4.model.ContactPoint;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.HumanName;
import org.hl7.fhir.r4.model.Identifier;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Date;
import java.util.Set;

/**
 * Maps the internal {@link com.example.hms.model.Patient} JPA entity to a
 * FHIR R4 {@link org.hl7.fhir.r4.model.Patient} resource.
 *
 * <p>The mapping is intentionally one-way (entity → FHIR). Inbound FHIR write
 * support will be added once the read API has been validated against
 * downstream consumers (OpenMRS, DHIS2, OpenHIE).
 */
@Component
public class PatientFhirMapper {

    private static final String IDENTIFIER_SYSTEM_INTERNAL_ID = "urn:hms:patient:id";
    private static final String IDENTIFIER_SYSTEM_MRN_PREFIX = "urn:hms:hospital:";

    public org.hl7.fhir.r4.model.Patient toFhir(Patient src) {
        if (src == null) {
            return null;
        }
        org.hl7.fhir.r4.model.Patient out = new org.hl7.fhir.r4.model.Patient();
        out.setId(src.getId() == null ? null : src.getId().toString());
        out.setActive(src.isActive());

        Identifier internal = new Identifier()
            .setSystem(IDENTIFIER_SYSTEM_INTERNAL_ID)
            .setValue(src.getId() == null ? null : src.getId().toString());
        internal.setUse(Identifier.IdentifierUse.OFFICIAL);
        out.addIdentifier(internal);

        addMrnIdentifiers(out, src.getHospitalRegistrations());

        addName(out, src);
        addTelecom(out, src);
        addAddress(out, src);
        setBirthDate(out, src);
        setGender(out, src);

        if (src.getCreatedAt() != null) {
            out.getMeta().setLastUpdated(
                Date.from(
                    (src.getUpdatedAt() == null ? src.getCreatedAt() : src.getUpdatedAt())
                        .atZone(ZoneId.systemDefault()).toInstant()
                )
            );
        }
        return out;
    }

    private void addMrnIdentifiers(org.hl7.fhir.r4.model.Patient out, Set<PatientHospitalRegistration> regs) {
        if (regs == null) return;
        regs.stream()
            .filter(r -> r != null && r.getMrn() != null && !r.getMrn().isBlank())
            .filter(r -> r.getHospital() != null && r.getHospital().getId() != null)
            .forEach(r -> {
                Identifier mrn = new Identifier()
                    .setSystem(IDENTIFIER_SYSTEM_MRN_PREFIX + r.getHospital().getId() + ":mrn")
                    .setValue(r.getMrn());
                mrn.setUse(Identifier.IdentifierUse.USUAL);
                mrn.getType().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                    .setCode("MR")
                    .setDisplay("Medical record number");
                out.addIdentifier(mrn);
            });
    }

    private void addName(org.hl7.fhir.r4.model.Patient out, Patient src) {
        HumanName n = new HumanName();
        n.setUse(HumanName.NameUse.OFFICIAL);
        if (src.getLastName() != null && !src.getLastName().isBlank()) {
            n.setFamily(src.getLastName().trim());
        }
        if (src.getFirstName() != null && !src.getFirstName().isBlank()) {
            n.addGiven(src.getFirstName().trim());
        }
        if (src.getMiddleName() != null && !src.getMiddleName().isBlank()) {
            n.addGiven(src.getMiddleName().trim());
        }
        if (n.getFamily() != null || !n.getGiven().isEmpty()) {
            out.addName(n);
        }
    }

    private void addTelecom(org.hl7.fhir.r4.model.Patient out, Patient src) {
        if (src.getPhoneNumberPrimary() != null && !src.getPhoneNumberPrimary().isBlank()) {
            out.addTelecom(new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.PHONE)
                .setUse(ContactPoint.ContactPointUse.MOBILE)
                .setRank(1)
                .setValue(src.getPhoneNumberPrimary().trim()));
        }
        if (src.getPhoneNumberSecondary() != null && !src.getPhoneNumberSecondary().isBlank()) {
            out.addTelecom(new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.PHONE)
                .setUse(ContactPoint.ContactPointUse.HOME)
                .setRank(2)
                .setValue(src.getPhoneNumberSecondary().trim()));
        }
        if (src.getEmail() != null && !src.getEmail().isBlank()) {
            out.addTelecom(new ContactPoint()
                .setSystem(ContactPoint.ContactPointSystem.EMAIL)
                .setUse(ContactPoint.ContactPointUse.HOME)
                .setValue(src.getEmail().trim()));
        }
    }

    private void addAddress(org.hl7.fhir.r4.model.Patient out, Patient src) {
        boolean hasAny = anyText(src.getAddressLine1(), src.getAddressLine2(),
            src.getCity(), src.getState(), src.getZipCode(), src.getCountry());
        if (!hasAny) return;

        Address a = new Address().setUse(Address.AddressUse.HOME);
        if (text(src.getAddressLine1())) a.addLine(src.getAddressLine1().trim());
        if (text(src.getAddressLine2())) a.addLine(src.getAddressLine2().trim());
        if (text(src.getCity()))      a.setCity(src.getCity().trim());
        if (text(src.getState()))     a.setState(src.getState().trim());
        if (text(src.getZipCode()))   a.setPostalCode(src.getZipCode().trim());
        if (text(src.getCountry()))   a.setCountry(src.getCountry().trim());
        out.addAddress(a);
    }

    private void setBirthDate(org.hl7.fhir.r4.model.Patient out, Patient src) {
        if (src.getDateOfBirth() != null) {
            out.setBirthDate(Date.from(
                src.getDateOfBirth().atStartOfDay(ZoneId.systemDefault()).toInstant()
            ));
        }
    }

    private void setGender(org.hl7.fhir.r4.model.Patient out, Patient src) {
        if (src.getGender() == null) return;
        switch (src.getGender().trim().toLowerCase()) {
            case "m", "male"   -> out.setGender(Enumerations.AdministrativeGender.MALE);
            case "f", "female" -> out.setGender(Enumerations.AdministrativeGender.FEMALE);
            case "o", "other"  -> out.setGender(Enumerations.AdministrativeGender.OTHER);
            default            -> out.setGender(Enumerations.AdministrativeGender.UNKNOWN);
        }
    }

    private static boolean text(String s) {
        return s != null && !s.isBlank();
    }

    private static boolean anyText(String... s) {
        for (String x : s) if (text(x)) return true;
        return false;
    }
}
