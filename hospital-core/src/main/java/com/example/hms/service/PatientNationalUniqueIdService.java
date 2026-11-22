package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Patient;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.repository.PatientRepository;
import com.example.hms.security.PatientNationalUniqueId;
import com.example.hms.service.empi.PatientNationalUniqueIdAliases;
import com.example.hms.utility.RoleValidator;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Optional IU boundary. Ordinary patient reads expose only the masked DTO field. */
@Service
@RequiredArgsConstructor
public class PatientNationalUniqueIdService {
    private static final Set<String> EDITORS = Set.of("ROLE_RECEPTIONIST", "ROLE_NURSE", "ROLE_MIDWIFE",
        "ROLE_DOCTOR", "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN");
    /** The roles of {@code PatientNationalUniqueIdController.SEARCHERS}: who may find a patient by IU. */
    static final Set<String> SEARCHERS = Set.of("ROLE_RECEPTIONIST", "ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR",
        "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_LAB_SCIENTIST", "ROLE_PHARMACIST", "ROLE_RADIOLOGIST",
        "ROLE_PHYSIOTHERAPIST", "ROLE_SUPER_ADMIN", "ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT");
    private final PatientRepository patients;
    private final RoleValidator roles;
    private final EntityManager entityManager;
    private final PatientNationalUniqueIdAliases aliases;
    private final ObjectProvider<RequiredAuditEventLogPort> audit;

    public record DuplicateWarning(List<UUID> accessiblePatientIds, boolean registeredElsewhere) {
        public boolean duplicate() { return !accessiblePatientIds.isEmpty() || registeredElsewhere; }
    }

    /** Does not carry the value or fingerprint, including in its generated toString. */
    public record Change(boolean changed, String operation, String reason) {
        private static Change unchanged() { return new Change(false, "", null); }
    }

    @Transactional(readOnly = true)
    public DuplicateWarning check(String input, UUID excludePatientId) {
        requireEditor();
        roles.requireActiveHospitalId();
        if (excludePatientId != null) requirePatient(excludePatientId);
        String hash = PatientNationalUniqueId.fingerprint(input);
        return hash == null ? new DuplicateWarning(List.of(), false) : duplicates(hash, excludePatientId);
    }

    @Transactional(readOnly = true)
    public List<PatientResponseDTO> search(String input) {
        UUID hospitalId = roles.requireActiveHospitalId();
        String hash = PatientNationalUniqueId.fingerprint(input);
        if (hash == null) return List.of();
        return patients.findByNationalUniqueIdHashInScope(hash).stream()
            .map(patient -> identityMatch(patient, hospitalId)).toList();
    }

    /**
     * QA D2521 (r65): the patients in the caller's scope whose IU is exactly {@code term}, for the free
     * patient search (a desk types the 12 digits of an ID card into the one search box). Empty, never an
     * error, when the term is not an IU, when the caller is not one of the roles of
     * {@code POST /patients/national-unique-id/search}, or when no lookup key is configured: the search
     * then answers as it did. The IU itself never leaves this method; the caller lists the patients.
     */
    @Transactional(readOnly = true)
    public List<Patient> matchesForSearch(String term) {
        if (!isNationalUniqueId(term) || !callerHolds(SEARCHERS)) return List.of();
        String hash;
        try {
            hash = PatientNationalUniqueId.fingerprint(term);
        } catch (FieldBusinessException unavailable) {
            return List.of();
        }
        return hash == null ? List.of() : patients.findByNationalUniqueIdHashInScope(hash);
    }

    /** Twelve digits, spaces or dashes between them allowed: what an IU is typed as. */
    static boolean isNationalUniqueId(String term) {
        if (term == null || term.isBlank()) return false;
        try {
            return PatientNationalUniqueId.normalize(term) != null;
        } catch (FieldBusinessException notAnIu) {
            return false;
        }
    }

    private static boolean callerHolds(Set<String> roles) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getAuthorities().stream()
            .anyMatch(authority -> roles.contains(authority.getAuthority()));
    }

    /** A picker needs identity only; it must not become another clinical-record read surface. */
    private static PatientResponseDTO identityMatch(Patient patient, UUID hospitalId) {
        return com.example.hms.mapper.PatientMapper.withIdentityStatus(PatientResponseDTO.builder(), patient)
            .id(patient.getId()).firstName(patient.getFirstName())
            .lastName(patient.getLastName()).middleName(patient.getMiddleName()).dateOfBirth(patient.getDateOfBirth())
            .gender(patient.getGender())
            .mrn(hospitalId == null ? null : patient.getMrnForHospital(hospitalId))
            .nationalUniqueIdMasked(PatientNationalUniqueId.masked(patient.getNationalUniqueId())).build();
    }

    @Transactional
    public String reveal(UUID patientId) {
        requireEditor();
        UUID hospitalId = roles.requireActiveHospitalId();
        Patient patient = requirePatient(patientId);
        recordAudit(patient, hospitalId, "SHOW_FULL", null);
        return PatientNationalUniqueId.formatted(patient.getNationalUniqueId());
    }

    /** Called inside the existing create/replace/patch transaction, before any save. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Change prepare(Patient patient, String input, boolean allowDuplicate, String reason) {
        if (input == null) return Change.unchanged(); // omission retains an existing value
        requireEditor();
        roles.requireActiveHospitalId();
        if (patient.getId() != null) requirePatient(patient.getId());
        String canonical = PatientNationalUniqueId.normalize(input);
        if (Objects.equals(canonical, patient.getNationalUniqueId())) return Change.unchanged();
        // A single fixed, transaction-scoped lock serializes concurrent duplicate checks.
        // Neither the value nor its fingerprint becomes a lock key or SQL literal.
        boolean postgres = entityManager.unwrap(org.hibernate.Session.class).doReturningWork(connection ->
            connection.getMetaData().getDatabaseProductName().equals("PostgreSQL"));
        if (postgres) entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(732104321)").getSingleResult();
        String hash = PatientNationalUniqueId.fingerprint(canonical);
        String safeReason = null;
        if (hash != null && duplicates(hash, patient.getId()).duplicate()) {
            if (!allowDuplicate) throw field("patient.iu.duplicateOverrideRequired");
            if (reason == null || reason.isBlank()) throw field("patient.iu.reasonRequired");
            if (reason.length() > 1000) throw field("patient.iu.reasonTooLong");
            safeReason = PatientNationalUniqueId.auditReason(reason);
        }
        String operation = canonical == null ? "CLEAR" : patient.getNationalUniqueId() == null ? "CREATE" : "UPDATE";
        patient.setNationalUniqueId(canonical);
        patient.setNationalUniqueIdHash(hash);
        return new Change(true, operation, safeReason);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(Patient patient, Change change, UUID hospitalId) {
        if (!change.changed()) return;
        aliases.synchronize(patient);
        recordAudit(patient, hospitalId, change.operation(), change.reason());
    }

    private DuplicateWarning duplicates(String hash, UUID exclude) {
        List<UUID> ids = patients.findByNationalUniqueIdHashInScope(hash).stream().map(Patient::getId)
            .filter(id -> !id.equals(exclude)).toList();
        // Only a boolean crosses the unscoped boundary. No foreign entity is loaded.
        boolean elsewhere = ids.isEmpty() && patients.existsOtherNationalUniqueIdHash(hash, exclude);
        return new DuplicateWarning(ids, elsewhere);
    }

    private Patient requirePatient(UUID id) {
        roles.requireActiveHospitalId();
        if (!patients.isFindableByPatientSearch(id)) throw new ResourceNotFoundException("patient.notFound", id);
        return patients.findByIdUnscoped(id).orElseThrow(() -> new ResourceNotFoundException("patient.notFound", id));
    }

    private void recordAudit(Patient patient, UUID hospitalId, String operation, String reason) {
        UUID actorId = com.example.hms.security.PrincipalUserIds.of(SecurityContextHolder.getContext().getAuthentication())
            .orElseThrow(() -> new AccessDeniedException("Access denied"));
        audit.getObject().logRequiredEvent(AuditEventRequestDTO.builder()
            .userId(actorId)
            .eventType("SHOW_FULL".equals(operation) ? AuditEventType.PATIENT_ACCESS : AuditEventType.PATIENT_UPDATE)
            .status(AuditStatus.SUCCESS)
            .entityType("PATIENT").patientId(patient.getId()).resourceId(patient.getId().toString())
            .hospitalId(hospitalId).eventDescription("Patient identity: " + operation)
            .details(reason == null ? null : java.util.Map.of("overrideReason", reason)).build());
    }

    private static void requireEditor() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication.getAuthorities().stream()
            .noneMatch(authority -> EDITORS.contains(authority.getAuthority()))) {
            throw new AccessDeniedException("Access denied");
        }
    }

    private static FieldBusinessException field(String key) {
        return new FieldBusinessException("nationalUniqueId", key);
    }
}
