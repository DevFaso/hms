package com.example.hms.mapper;

import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.stereotype.Component;

@Component
public class PrescriptionMapper {

    private static final Logger log = LoggerFactory.getLogger(PrescriptionMapper.class);

    /** Entity label carried into the {@code safeInit} lazy-load diagnostics. */
    private static final String ENTITY = "Prescription";

    /* ============================
       Response mapping
       ============================ */
    public PrescriptionResponseDTO toResponseDTO(Prescription p) {
        if (p == null) return null;

        // Defensive: if a parent row was hard-deleted while a Prescription
        // still references it (dangling FK), `safeInit` returns null instead
        // of letting the lazy proxy throw EntityNotFoundException — which
        // would otherwise 500 the entire list endpoint via
        // GlobalExceptionHandler.handleEntityNotFound.
        Patient   patient  = safeInit(p.getPatient(),   ENTITY, p.getId(), "patient");
        Staff     staff    = safeInit(p.getStaff(),     ENTITY, p.getId(), "staff");
        Encounter enc      = safeInit(p.getEncounter(), ENTITY, p.getId(), "encounter");
        Hospital  hospital = safeInit(p.getHospital(),  ENTITY, p.getId(), "hospital");

        return PrescriptionResponseDTO.builder()
            .id(p.getId())

            .patientId(patient != null ? patient.getId() : null)
            .patientFullName(resolvePatientFullName(patient))
            .patientEmail(patient != null ? nullSafe(patient.getEmail()) : "")

            .staffId(staff != null ? staff.getId() : null)
            .staffFullName(resolveStaffFullName(staff))

            .encounterId(enc != null ? enc.getId() : null)
            .hospitalId(resolveHospitalId(hospital, enc))
            .hospitalName(resolveHospitalName(hospital, enc))

            .medicationName(p.getMedicationName())
            .medicationDisplayName(p.getMedicationDisplayName())

            .dosage(p.getDosage())
            .frequency(p.getFrequency())
            .duration(p.getDuration())
            .route(p.getRoute())
            .instructions(p.getInstructions())
            .notes(p.getNotes())

            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .controlledSubstance(p.isControlledSubstance())
            .requiresCosign(p.isRequiresCosign())
            .twoFactorVerifiedAt(p.getTwoFactorVerifiedAt())
            .cosignedAt(p.getCosignedAt())
            // Tier 2 item 33. requiresPharmacistVerification mirrors
            // PharmacistVerificationService.requiresVerification — kept in
            // sync deliberately rather than injecting the service into a
            // mapper; the service test pins the rule and the mapper test
            // pins this projection.
            .requiresPharmacistVerification(p.isControlledSubstance() || p.isRequiresCosign())
            .pharmacistVerifiedAt(p.getPharmacistVerifiedAt())
            .pharmacistVerifiedByUserId(
                p.getPharmacistVerifiedBy() != null ? p.getPharmacistVerifiedBy().getId() : null)
            .pharmacistVerifiedByName(
                p.getPharmacistVerifiedBy() != null ? p.getPharmacistVerifiedBy().getUsername() : null)
            .pharmacistVerificationNote(p.getPharmacistVerificationNote())
            .cosignedByStaffId(p.getCosignedBy() != null ? p.getCosignedBy().getId() : null)
            .signatureValue(p.getSignatureValue())
            .signatureAlgorithm(p.getSignatureAlgorithm())
            .signedAt(p.getSignedAt())
            .signedByStaffId(p.getSignedBy() != null ? p.getSignedBy().getId() : null)

            .createdAt(p.getCreatedAt())
            .updatedAt(p.getUpdatedAt())
            .build();
    }

    /* ============================
       Entity mapping
       ============================ */
    public Prescription toEntity(PrescriptionRequestDTO dto, Patient patient, Staff staff, Encounter encounter) {
        Prescription e = new Prescription();
        e.setPatient(patient);
        e.setStaff(staff);
        e.setEncounter(encounter);
        if (encounter != null && encounter.getHospital() != null) {
            e.setHospital(encounter.getHospital());
        } else if (staff != null) {
            e.setHospital(staff.getHospital());
        }

        e.setMedicationName(dto.getMedicationName());
        e.setMedicationCode(dto.getMedicationCode());
        e.setMedicationDisplayName(dto.getMedicationName());

        e.setDosage(dto.getDosage());
        e.setFrequency(dto.getFrequency());
        e.setDuration(dto.getDuration());
        e.setNotes(dto.getNotes());

        if (dto.getStatus() != null) {
            e.setStatus(dto.getStatus());
        }

        // Safeguard flags (P2 #15). Writable at last: the gates guarding these
        // shipped with no writer anywhere, so they could never fire.
        if (Boolean.TRUE.equals(dto.getControlledSubstance())) {
            e.setControlledSubstance(true);
        }
        if (Boolean.TRUE.equals(dto.getRequiresCosign())) {
            e.setRequiresCosign(true);
        }

        return e;
    }

    public void updateEntity(Prescription target, PrescriptionRequestDTO dto,
                             Patient patient, Staff staff, Encounter encounter) {
        target.setPatient(patient);
        target.setStaff(staff);
        target.setEncounter(encounter);
        if (encounter != null && encounter.getHospital() != null) {
            target.setHospital(encounter.getHospital());
        } else if (staff != null) {
            target.setHospital(staff.getHospital());
        }

        target.setMedicationName(dto.getMedicationName());
        if (dto.getMedicationCode() != null) {
            target.setMedicationCode(dto.getMedicationCode());
        }
        if (!notBlank(target.getMedicationDisplayName())) {
            target.setMedicationDisplayName(dto.getMedicationName());
        }

        target.setDosage(dto.getDosage());
        target.setFrequency(dto.getFrequency());
        target.setDuration(dto.getDuration());
        target.setNotes(dto.getNotes());

        if (dto.getStatus() != null) {
            target.setStatus(dto.getStatus());
        }

        // Set-only on update: the service refuses a true->false withdrawal
        // before this runs, and null means "leave unchanged".
        if (Boolean.TRUE.equals(dto.getControlledSubstance())) {
            target.setControlledSubstance(true);
        }
        if (Boolean.TRUE.equals(dto.getRequiresCosign())) {
            target.setRequiresCosign(true);
        }
    }

    /* ============================
       Helpers
       ============================ */
    private String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String resolveStaffFullName(Staff staff) {
        if (staff != null && notBlank(staff.getName())) {
            return staff.getName();
        }
        // staff.user is itself a LAZY @ManyToOne — guard against a dangling FK.
        com.example.hms.model.User user = staff != null
            ? safeInit(staff.getUser(), "Staff", staff.getId(), "user")
            : null;
        String firstName = user != null ? user.getFirstName() : null;
        String lastName  = user != null ? user.getLastName()  : null;
        return buildFullName(firstName, lastName);
    }

    private String resolvePatientFullName(Patient patient) {
        if (patient == null) return "";
        return buildFullName(patient.getFirstName(), patient.getLastName());
    }

    /**
     * Resolve the prescription's hospital, preferring the prescription's own
     * {@code hospital} field (set by {@code toEntity}) and falling back to
     * the encounter for older rows written before that field was always
     * populated. Both inputs are passed in already-{@link #safeInit safeInit}'d
     * to absorb dangling-FK lazy load failures.
     */
    private Hospital resolveHospital(Hospital hospital, Encounter enc) {
        if (hospital != null) {
            return hospital;
        }
        if (enc == null) {
            return null;
        }
        return safeInit(enc.getHospital(), "Encounter", enc.getId(), "hospital");
    }

    private java.util.UUID resolveHospitalId(Hospital hospital, Encounter enc) {
        Hospital h = resolveHospital(hospital, enc);
        return h != null ? h.getId() : null;
    }

    private String resolveHospitalName(Hospital hospital, Encounter enc) {
        Hospital h = resolveHospital(hospital, enc);
        return h != null ? h.getName() : null;
    }

    /**
     * Safely initialise a Hibernate lazy proxy. Returns {@code null} when the
     * referenced row was hard-deleted while this row still references it
     * (dangling FK) instead of letting {@link EntityNotFoundException} /
     * {@link JpaObjectRetrievalFailureException} propagate out — which would
     * otherwise be mapped to HTTP 500 by {@code GlobalExceptionHandler.handleEntityNotFound}
     * and break the whole list response over a single bad row.
     */
    private static <T> T safeInit(T proxyOrEntity, String parentEntity, Object parentId, String association) {
        if (proxyOrEntity == null) return null;
        try {
            Hibernate.initialize(proxyOrEntity);
            return proxyOrEntity;
        } catch (EntityNotFoundException | JpaObjectRetrievalFailureException e) {
            log.warn("⚠️ {}({}) has a dangling FK on '{}' — referenced row was deleted. " +
                     "Returning null for this association; DB cleanup required.",
                     parentEntity, parentId, association);
            return null;
        }
    }

    private String buildFullName(String first, String last) {
        String f = first == null ? "" : first.trim();
        String l = last  == null ? "" : last.trim();
        String full = (f + " " + l).trim();
        return full.isEmpty() ? "" : full;
    }
}
