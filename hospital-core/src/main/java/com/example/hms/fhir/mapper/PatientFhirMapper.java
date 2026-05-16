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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps the internal {@link com.example.hms.model.Patient} JPA entity to a
 * FHIR R4 {@link org.hl7.fhir.r4.model.Patient} resource.
 *
 * <p>{@link #toFhir(Patient)} is the read direction (entity → FHIR).
 * {@link #applyFhirUpdates(Patient, org.hl7.fhir.r4.model.Patient)} is the
 * write direction (FHIR → existing entity) and is intentionally narrow:
 * only contact + address fields are honored. Identity columns
 * (name / DOB / gender / email / phone) are <strong>not</strong> mutated
 * via FHIR PUT — those changes must flow through the registration admin
 * flow which carries a stronger audit trail.
 */
@Component
public class PatientFhirMapper {

    private static final String IDENTIFIER_SYSTEM_INTERNAL_ID = "urn:hms:patient:id";
    private static final String IDENTIFIER_SYSTEM_MRN_PREFIX = "urn:hms:hospital:";
    private static final String MRN_SYSTEM_SUFFIX = ":mrn";
    private static final Pattern MRN_SYSTEM_PATTERN = Pattern.compile(
        "^urn:hms:hospital:([0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}):mrn$"
    );

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

    /* ===================== Write direction (FHIR → entity) ===================== */

    /**
     * Result of extracting an MRN identifier from an inbound FHIR Patient or
     * {@code If-None-Exist} search clause. Carries both the hospital UUID
     * derived from the identifier system and the raw MRN value.
     */
    public record MrnIdentifier(UUID hospitalId, String mrn) {}

    /**
     * Find an MRN-shaped identifier on an inbound FHIR Patient. Looks for
     * any identifier whose system matches
     * {@code urn:hms:hospital:<uuid>:mrn}; returns the first match (FHIR
     * does not order identifiers but in practice senders emit at most one
     * MRN per hospital).
     *
     * <p>Returns {@code Optional.empty()} when no MRN identifier is
     * present — the write provider treats that as a policy violation
     * (no auto-provisioning without an MRN binding).
     */
    public Optional<MrnIdentifier> extractMrnIdentifier(org.hl7.fhir.r4.model.Patient src) {
        if (src == null || src.getIdentifier() == null) return Optional.empty();
        for (Identifier id : src.getIdentifier()) {
            Optional<MrnIdentifier> match = matchMrnSystem(id);
            if (match.isPresent()) return match;
        }
        return Optional.empty();
    }

    /**
     * Parse a FHIR conditional-create token value of the shape
     * {@code <system>|<value>} (the canonical search-parameter encoding
     * for {@code identifier=...} clauses). The {@code system} part must
     * match the MRN system; the {@code value} part is the MRN itself.
     * Returns empty if the token is missing the pipe separator or the
     * system does not parse as a hospital-scoped MRN URI.
     */
    public Optional<MrnIdentifier> parseMrnSearchToken(String tokenValue) {
        if (tokenValue == null || tokenValue.isBlank()) return Optional.empty();
        int pipe = tokenValue.indexOf('|');
        if (pipe <= 0 || pipe == tokenValue.length() - 1) return Optional.empty();
        String system = tokenValue.substring(0, pipe).trim();
        String mrn = tokenValue.substring(pipe + 1).trim();
        if (mrn.isEmpty()) return Optional.empty();
        Identifier probe = new Identifier().setSystem(system).setValue(mrn);
        return matchMrnSystem(probe);
    }

    private Optional<MrnIdentifier> matchMrnSystem(Identifier id) {
        if (id == null || id.getSystem() == null || id.getValue() == null) return Optional.empty();
        if (!id.getSystem().endsWith(MRN_SYSTEM_SUFFIX)) return Optional.empty();
        Matcher matcher = MRN_SYSTEM_PATTERN.matcher(id.getSystem());
        if (!matcher.matches()) return Optional.empty();
        try {
            UUID hospitalId = UUID.fromString(matcher.group(1));
            String value = id.getValue().trim();
            if (value.isEmpty()) return Optional.empty();
            return Optional.of(new MrnIdentifier(hospitalId, value));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    /**
     * Apply the FHIR-mutable subset of fields from an inbound FHIR
     * Patient onto an existing entity. Returns the same entity for
     * chaining. The caller is responsible for persisting + audit
     * emission.
     *
     * <p><strong>Intentionally narrow:</strong>
     * <ul>
     *   <li>Address (single home address)</li>
     *   <li>Telecom — phone (mobile + home) and email</li>
     *   <li>Active flag</li>
     * </ul>
     * Name, DOB, gender, and identifiers are <strong>not</strong>
     * overwritten — those flow through the registration admin path.
     */
    public Patient applyFhirUpdates(Patient existing, org.hl7.fhir.r4.model.Patient src) {
        if (existing == null || src == null) return existing;
        if (src.hasActive()) existing.setActive(src.getActive());
        applyTelecomUpdates(existing, src);
        applyAddressUpdates(existing, src);
        return existing;
    }

    private void applyTelecomUpdates(Patient out, org.hl7.fhir.r4.model.Patient src) {
        if (!src.hasTelecom()) return;
        String mobile = firstTelecom(src, ContactPoint.ContactPointSystem.PHONE, ContactPoint.ContactPointUse.MOBILE);
        String home = firstTelecom(src, ContactPoint.ContactPointSystem.PHONE, ContactPoint.ContactPointUse.HOME);
        String email = firstTelecom(src, ContactPoint.ContactPointSystem.EMAIL, null);
        if (mobile != null) out.setPhoneNumberPrimary(mobile);
        if (home != null) out.setPhoneNumberSecondary(home);
        if (email != null) out.setEmail(email);
    }

    private void applyAddressUpdates(Patient out, org.hl7.fhir.r4.model.Patient src) {
        if (!src.hasAddress()) return;
        Address a = src.getAddressFirstRep();
        if (a == null) return;
        if (a.hasLine() && !a.getLine().isEmpty()) {
            out.setAddressLine1(a.getLine().get(0).getValue());
            if (a.getLine().size() > 1) {
                out.setAddressLine2(a.getLine().get(1).getValue());
            }
        }
        if (a.hasCity()) out.setCity(a.getCity());
        if (a.hasState()) out.setState(a.getState());
        if (a.hasPostalCode()) out.setZipCode(a.getPostalCode());
        if (a.hasCountry()) out.setCountry(a.getCountry());
    }

    private static String firstTelecom(
        org.hl7.fhir.r4.model.Patient src,
        ContactPoint.ContactPointSystem system,
        ContactPoint.ContactPointUse use
    ) {
        for (ContactPoint cp : src.getTelecom()) {
            if (cp.getSystem() != system) continue;
            if (use != null && cp.getUse() != use) continue;
            if (cp.getValue() == null || cp.getValue().isBlank()) continue;
            return cp.getValue().trim();
        }
        return null;
    }
}
