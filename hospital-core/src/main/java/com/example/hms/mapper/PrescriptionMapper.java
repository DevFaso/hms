package com.example.hms.mapper;

import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.payload.dto.PrescriptionRequestDTO;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import jakarta.persistence.EntityNotFoundException;
import org.hibernate.Hibernate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaObjectRetrievalFailureException;
import org.springframework.stereotype.Component;

@Component
public class PrescriptionMapper {

    private static final Logger log = LoggerFactory.getLogger(PrescriptionMapper.class);

    /** Entity label carried into the {@code safeInit} lazy-load diagnostics. */
    private static final String ENTITY = "Prescription";

    /**
     * D654: the catalog's controlled flag is shown on the prescription even
     * when the prescriber did not tick it. Display only: the prescriber
     * two-factor signing block stays on the prescriber's own tick until a
     * two-factor step exists (tasklist standing debt). Optional so a plain
     * {@code new PrescriptionMapper()} in unit tests still works.
     */
    private MedicationCatalogItemRepository catalogRepository;

    @Autowired(required = false)
    public void setCatalogRepository(MedicationCatalogItemRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    /**
     * QA D934: whether an unsigned order's prescriber holds a signature of her
     * own (doctor, physician, surgeon) at the order's hospital. Optional for
     * the same reason as the catalog repository.
     */
    private com.example.hms.utility.RoleValidator roleValidator;

    @Autowired(required = false)
    public void setRoleValidator(com.example.hms.utility.RoleValidator roleValidator) {
        this.roleValidator = roleValidator;
    }

    /**
     * Statuses the pharmacy writes (gap G7). While the row is in one of these
     * the status IS the latest pharmacy event; DRAFT, SIGNED, CANCELLED and
     * the like are the prescriber's, and report no pharmacy event.
     */
    static final java.util.Set<PrescriptionStatus> PHARMACY_OWNED_STATUSES = java.util.EnumSet.of(
        PrescriptionStatus.TRANSMITTED,
        PrescriptionStatus.TRANSMISSION_FAILED,
        PrescriptionStatus.PENDING_CLARIFICATION,
        PrescriptionStatus.DISPENSED,
        PrescriptionStatus.PARTIALLY_FILLED,
        PrescriptionStatus.PENDING_STOCK,
        PrescriptionStatus.SENT_TO_PARTNER,
        PrescriptionStatus.PARTNER_ACCEPTED,
        PrescriptionStatus.PARTNER_REJECTED,
        PrescriptionStatus.PARTNER_DISPENSED,
        PrescriptionStatus.PRINTED_FOR_PATIENT);

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
        // D654/D678: tick OR the catalog item at the order's hospital (the rule
        // PrescriptionControlledResolver applies for verification, chart and history).
        boolean controlled = p.isControlledSubstance()
            || catalogControlled(resolveHospitalId(hospital, enc),
                p.getMedicationCatalogItemId(), p.getMedicationCode());

        return PrescriptionResponseDTO.builder()
            .id(p.getId())

            .patientId(patient != null ? patient.getId() : null)
            .patientFullName(resolvePatientFullName(patient))
            .patientEmail(patient != null ? nullSafe(patient.getEmail()) : "")
            .patientPhone(patient != null ? nullSafe(patient.getPhoneNumberPrimary()) : "")

            .staffId(staff != null ? staff.getId() : null)
            .staffFullName(resolveStaffFullName(staff))

            .encounterId(enc != null ? enc.getId() : null)
            .hospitalId(resolveHospitalId(hospital, enc))
            .hospitalName(resolveHospitalName(hospital, enc))

            .medicationName(p.getMedicationName())
            .medicationDisplayName(p.getMedicationDisplayName())
            .medicationCatalogItemId(p.getMedicationCatalogItemId())

            .dosage(p.getDosage())
            .frequency(p.getFrequency())
            .duration(p.getDuration())
            .route(p.getRoute())
            .instructions(p.getInstructions())
            .notes(p.getNotes())

            // Gap G13 — what was ordered, so a remainder can carry its unit.
            .quantity(p.getQuantity())
            .quantityUnit(p.getQuantityUnit())
            .refillsAllowed(p.getRefillsAllowed())
            .refillsRemaining(p.getRefillsRemaining())
            .refillsUsed(p.getRefillsUsed())

            .status(p.getStatus() != null ? p.getStatus().name() : null)
            .controlledSubstance(controlled)
            .requiresCosign(p.isRequiresCosign())
            .awaitingPhysicianSignature(awaitsPhysicianSignature(p, staff, resolveHospitalId(hospital, enc)))
            .takeHome(p.isTakeHome())
            .twoFactorVerifiedAt(p.getTwoFactorVerifiedAt())
            .cosignedAt(p.getCosignedAt())
            // Tier 2 item 33. requiresPharmacistVerification mirrors
            // PharmacistVerificationService.requiresVerification — kept in
            // sync deliberately rather than injecting the service into a
            // mapper; the service test pins the rule and the mapper test
            // pins this projection.
            // QA D678: the same controlled answer as the service, catalog included.
            .requiresPharmacistVerification(controlled || p.isRequiresCosign())
            .pharmacistVerifiedAt(p.getPharmacistVerifiedAt())
            .pharmacistVerifiedByUserId(
                p.getPharmacistVerifiedBy() != null ? p.getPharmacistVerifiedBy().getId() : null)
            .pharmacistVerifiedByName(displayName(p.getPharmacistVerifiedBy()))
            .pharmacistVerificationNote(p.getPharmacistVerificationNote())
            .cosignedByStaffId(p.getCosignedBy() != null ? p.getCosignedBy().getId() : null)
            .signatureValue(p.getSignatureValue())
            .signatureAlgorithm(p.getSignatureAlgorithm())
            .signedAt(p.getSignedAt())
            .signedByStaffId(p.getSignedBy() != null ? p.getSignedBy().getId() : null)

            .pharmacyId(p.getPharmacyId())
            .pharmacyName(p.getPharmacyName())
            .pharmacyContact(p.getPharmacyContact())
            .dispatchChannel(p.getDispatchChannel())
            .dispatchStatus(p.getDispatchStatus())
            .dispatchedAt(p.getDispatchedAt())
            .lastPharmacyEvent(lastPharmacyEvent(p))
            .lastPharmacyEventAt(lastPharmacyEventAt(p))

            .clarificationReason(p.getClarificationReason())
            .clarificationRequestedAt(p.getClarificationRequestedAt())
            .clarificationResponse(p.getClarificationResponse())
            .clarificationResolvedAt(p.getClarificationResolvedAt())
            .withdrawalReason(p.getWithdrawalReason())
            // QA D1101: the override reason the prescriber gave, for the
            // pharmacist, the co-signer and the chart.
            .overrideReason(p.getOverrideReason())
            .allergyOverride(p.getOverrideReason() == null ? null : p.isAllergiesReviewed())
            // QA D1096: the paediatric dose review, required and recorded.
            .pediatricDoseReviewRequired(p.isPediatricDoseReviewRequired())
            .pediatricDoseReviewedAt(p.getPediatricDoseReviewedAt())
            .pediatricDoseReviewedByName(displayName(p.getPediatricDoseReviewedBy()))
            .pediatricDoseReviewReason(p.getPediatricDoseReviewReason())

            .createdAt(p.getCreatedAt())
            .updatedAt(p.getUpdatedAt())
            .build();
    }

    private static String lastPharmacyEvent(Prescription p) {
        PrescriptionStatus status = p.getStatus();
        return status != null && PHARMACY_OWNED_STATUSES.contains(status) ? status.name() : null;
    }

    private static java.time.LocalDateTime lastPharmacyEventAt(Prescription p) {
        PrescriptionStatus status = p.getStatus();
        if (status == null || !PHARMACY_OWNED_STATUSES.contains(status)) {
            return null;
        }
        if (status == PrescriptionStatus.PENDING_CLARIFICATION && p.getClarificationRequestedAt() != null) {
            return p.getClarificationRequestedAt();
        }
        if (status == PrescriptionStatus.TRANSMITTED && p.getDispatchedAt() != null) {
            return p.getDispatchedAt();
        }
        return p.getUpdatedAt();
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
        e.setMedicationCatalogItemId(dto.getMedicationCatalogItemId());
        e.setMedicationDisplayName(dto.getMedicationName());

        e.setDosage(dto.getDosage());
        e.setFrequency(dto.getFrequency());
        e.setRoute(normalizeRoute(dto.getRoute()));
        e.setDuration(dto.getDuration());
        e.setNotes(dto.getNotes());
        // QA D706: the prescribed quantity the pharmacy caps dispenses at.
        e.setQuantity(dto.getQuantity());
        e.setQuantityUnit(dto.getQuantityUnit());

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
        // QA D757: a take-home order is marked as such by its prescriber.
        e.setTakeHome(Boolean.TRUE.equals(dto.getTakeHome()));

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
        // D654: an edit names its catalog pick again, or falls back to the code.
        target.setMedicationCatalogItemId(dto.getMedicationCatalogItemId());
        if (!notBlank(target.getMedicationDisplayName())) {
            target.setMedicationDisplayName(dto.getMedicationName());
        }

        target.setDosage(dto.getDosage());
        target.setFrequency(dto.getFrequency());
        if (dto.getRoute() != null) {
            target.setRoute(normalizeRoute(dto.getRoute()));
        }
        target.setDuration(dto.getDuration());
        target.setNotes(dto.getNotes());
        // QA D706: null leaves the prescribed quantity unchanged.
        if (dto.getQuantity() != null) {
            target.setQuantity(dto.getQuantity());
        }
        if (dto.getQuantityUnit() != null) {
            target.setQuantityUnit(dto.getQuantityUnit());
        }

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
        if (dto.getTakeHome() != null) {
            target.setTakeHome(dto.getTakeHome());
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
        String fullName = patient.getFullName();
        return fullName != null ? fullName : "";
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

    private boolean catalogControlled(java.util.UUID hospitalId, java.util.UUID catalogItemId,
                                      String medicationCode) {
        if (catalogRepository == null || hospitalId == null) {
            return false;
        }
        // D654: the picked item decides; a UI-created item has no code.
        if (catalogItemId != null) {
            return catalogRepository.findByIdAndHospital_Id(catalogItemId, hospitalId)
                .map(MedicationCatalogItem::isControlled)
                .orElse(false);
        }
        if (medicationCode == null || medicationCode.isBlank()) {
            return false;
        }
        return catalogRepository.findByHospitalIdAndCode(hospitalId, medicationCode.trim())
            .map(MedicationCatalogItem::isControlled)
            .orElse(false);
    }

    /**
     * QA D934: an unsigned order drafted by a nurse or a midwife of the
     * order's hospital ({@code RoleValidator.draftsForPhysicianSignature}),
     * the same rule {@code PrescriptionServiceImpl} applies when a doctor
     * signs it.
     */
    private boolean awaitsPhysicianSignature(Prescription p, Staff staff, java.util.UUID hospitalId) {
        if (roleValidator == null || hospitalId == null || p.getSignatureValue() != null
                || (p.getStatus() != PrescriptionStatus.DRAFT && p.getStatus() != PrescriptionStatus.PENDING_SIGNATURE)) {
            return false;
        }
        java.util.UUID userId = staff != null && staff.getUser() != null ? staff.getUser().getId() : null;
        if (userId == null) {
            return false;
        }
        return roleValidator.draftsForPhysicianSignature(userId, hospitalId);
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

    /**
     * The name to put in front of a clinician (Tier 2 item 33).
     *
     * <p>Falls back to the login name only when there is nothing else, because
     * "Verified by pharm1" tells a nurse rather less than "Verified by Awa
     * Traoré" about who checked the drug they are holding. Same shape as
     * {@code ObgynReferralMapper.buildDisplayName}.
     */
    private String displayName(User user) {
        if (user == null) {
            return null;
        }
        String full = buildFullName(user.getFirstName(), user.getLastName());
        return full.isEmpty() ? user.getUsername() : full;
    }

    /** Trim and upper-case a route code; blank means "not given". */
    private static String normalizeRoute(String route) {
        if (route == null || route.isBlank()) {
            return null;
        }
        return route.trim().toUpperCase(java.util.Locale.ROOT);
    }
}
