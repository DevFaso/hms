package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.exception.UnauthorizedAccessException;
import com.example.hms.model.BreakGlassSession;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.BreakGlassDeclareRequestDTO;
import com.example.hms.payload.dto.BreakGlassRevokeRequestDTO;
import com.example.hms.payload.dto.BreakGlassSessionResponseDTO;
import com.example.hms.repository.BreakGlassSessionRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.SecurityUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Implementation of {@link BreakGlassService}. Holds the time-bound override
 * lifecycle and its audit trail.
 *
 * <p>Role enforcement is double-layered: the controller pre-filters with
 * {@code @PreAuthorize}, but {@link #ensurePrivilegedAtHospital} re-checks the
 * (user, hospital, role) tuple inside the transaction to defeat any controller
 * misconfiguration. SUPER_ADMIN holds an active assignment with hospital=null
 * so it is checked separately via the global-role lookup.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class BreakGlassServiceImpl implements BreakGlassService {

    /** Default TTL when the request omits {@code ttlMinutes}. */
    static final int DEFAULT_TTL_MINUTES = 240;

    /** Hard upper bound on caller-supplied TTL — keeps emergency access genuinely short. */
    static final int MAX_TTL_MINUTES = 240;

    /**
     * Roles allowed to declare a break-the-glass session. Codes match what
     * V2__seed_roles.sql persists in {@code security.roles.code} (all prefixed
     * with {@code ROLE_}); the controller's @PreAuthorize uses the same form
     * via Spring's authority convention.
     */
    static final Set<String> DECLARE_ROLES = Set.of(
        "ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN"
    );

    /** Roles allowed to revoke any session at the hospital (declaring user can always revoke their own). */
    static final Set<String> ADMIN_REVOKE_ROLES = Set.of("ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN");

    /** Persisted role code for the global super-admin. */
    static final String SUPER_ADMIN_CODE = "ROLE_SUPER_ADMIN";

    private final BreakGlassSessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final PatientRepository patientRepository;
    private final HospitalRepository hospitalRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogService auditService;

    // ---------------------------------------------------------------------
    // Declare
    // ---------------------------------------------------------------------

    @Override
    @Transactional
    public BreakGlassSessionResponseDTO declare(BreakGlassDeclareRequestDTO request) {
        User caller = currentUserOrThrow();
        Hospital hospital = hospitalRepository.findById(request.getHospitalId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Hospital not found: " + request.getHospitalId()));
        Patient patient = patientRepository.findById(request.getPatientId())
            .orElseThrow(() -> new ResourceNotFoundException(
                "Patient not found: " + request.getPatientId()));

        ensurePrivilegedAtHospital(caller, hospital.getId(), DECLARE_ROLES);

        int ttl = clampTtl(request.getTtlMinutes());
        LocalDateTime now = LocalDateTime.now();
        BreakGlassSession session = BreakGlassSession.builder()
            .user(caller)
            .patient(patient)
            .hospital(hospital)
            .reason(request.getReason())
            .startedAt(now)
            .expiresAt(now.plusMinutes(ttl))
            .auditCount(0)
            .build();
        BreakGlassSession saved = sessionRepository.save(session);

        emitAudit(AuditEventType.BREAK_GLASS_ACCESS, caller, hospital, patient,
            "Break-the-glass session declared (TTL " + ttl + " min): " + request.getReason(),
            saved.getId(), AuditStatus.SUCCESS);

        log.warn("[BREAK_GLASS] Declared session={} user={} patient={} hospital={} ttl={}min reason='{}'",
            saved.getId(), caller.getId(), patient.getId(), hospital.getId(), ttl, request.getReason());

        return toDto(saved);
    }

    // ---------------------------------------------------------------------
    // Revoke
    // ---------------------------------------------------------------------

    @Override
    @Transactional
    public BreakGlassSessionResponseDTO revoke(UUID sessionId, BreakGlassRevokeRequestDTO request) {
        User caller = currentUserOrThrow();
        BreakGlassSession session = sessionRepository.findById(sessionId)
            .orElseThrow(() -> new ResourceNotFoundException(
                "Break-glass session not found: " + sessionId));

        boolean isOwner = session.getUser().getId().equals(caller.getId());
        boolean isAdmin = hasAnyRoleAtHospital(caller, session.getHospital().getId(), ADMIN_REVOKE_ROLES)
            || isSuperAdmin(caller);

        if (!isOwner && !isAdmin) {
            throw new UnauthorizedAccessException(
                "Only the declaring user or a HOSPITAL_ADMIN/SUPER_ADMIN may revoke this session.");
        }

        if (session.getRevokedAt() == null && session.getExpiresAt().isAfter(LocalDateTime.now())) {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokedBy(caller);
            session.setRevokeReason(request != null ? request.getReason() : null);
            sessionRepository.save(session);

            emitAudit(AuditEventType.BREAK_GLASS_ACCESS, caller, session.getHospital(), session.getPatient(),
                "Break-the-glass session revoked early: "
                    + (request != null && request.getReason() != null ? request.getReason() : "no reason given"),
                session.getId(), AuditStatus.SUCCESS);

            log.warn("[BREAK_GLASS] Revoked session={} by user={} reason='{}'",
                session.getId(), caller.getId(),
                request != null ? request.getReason() : null);
        }
        return toDto(session);
    }

    // ---------------------------------------------------------------------
    // Read paths
    // ---------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public List<BreakGlassSessionResponseDTO> listLiveForPatient(UUID patientId) {
        // Service-side authz: the controller's @PreAuthorize accepts any privileged
        // role globally, but we still need the caller to actually be staff at one
        // of the hospitals where this session was declared (or super admin).
        // Filter the row set to those hospitals before returning.
        User caller = currentUserOrThrow();
        boolean superAdmin = isSuperAdmin(caller);
        return sessionRepository.findLiveForPatient(patientId, LocalDateTime.now())
            .stream()
            .filter(s -> superAdmin
                || hasAnyRoleAtHospital(caller, s.getHospital().getId(), DECLARE_ROLES))
            .map(this::toDto)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Page<BreakGlassSessionResponseDTO> listForHospital(UUID hospitalId, Pageable pageable) {
        // The controller-level @PreAuthorize only checks that the caller has the
        // ROLE_HOSPITAL_ADMIN authority — it does NOT verify the call is for the
        // caller's own hospital. Without this service-side check a hospital admin
        // at hospital A could enumerate emergency-access activity at hospital B.
        User caller = currentUserOrThrow();
        boolean isAdminForHospital = isSuperAdmin(caller)
            || hasAnyRoleAtHospital(caller, hospitalId, ADMIN_REVOKE_ROLES);
        if (!isAdminForHospital) {
            throw new UnauthorizedAccessException(
                "Caller is not an administrator at hospital " + hospitalId
                    + "; cannot view its break-glass audit trail.");
        }
        return sessionRepository.findByHospitalIdOrderByStartedAtDesc(hospitalId, pageable)
            .map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BreakGlassSessionResponseDTO> findLiveForCurrentUserAndPatient(UUID patientId) {
        User caller = currentUserOrNull();
        if (caller == null) {
            return Optional.empty();
        }
        return sessionRepository.findLiveForUserAndPatient(caller.getId(), patientId, LocalDateTime.now())
            .stream()
            .findFirst()
            .map(this::toDto);
    }

    @Override
    @Transactional
    public Optional<BreakGlassSessionResponseDTO> consumeIfLive(UUID patientId, UUID sessionUserId, String purpose) {
        if (patientId == null) {
            return Optional.empty();
        }
        UUID userId = sessionUserId;
        if (userId == null) {
            User caller = currentUserOrNull();
            if (caller == null) {
                return Optional.empty();
            }
            userId = caller.getId();
        }
        Optional<BreakGlassSession> maybe = sessionRepository
            .findLiveForUserAndPatient(userId, patientId, LocalDateTime.now())
            .stream()
            .findFirst();
        if (maybe.isEmpty()) {
            return Optional.empty();
        }
        BreakGlassSession session = maybe.get();
        session.setAuditCount(session.getAuditCount() + 1);
        sessionRepository.save(session);

        emitAudit(AuditEventType.BREAK_GLASS_ACCESS, session.getUser(),
            session.getHospital(), session.getPatient(),
            "BREAK_GLASS read: " + (purpose == null ? "(no purpose given)" : purpose),
            session.getId(), AuditStatus.SUCCESS);

        return Optional.of(toDto(session));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private int clampTtl(Integer requested) {
        if (requested == null) {
            return DEFAULT_TTL_MINUTES;
        }
        if (requested < 15) {
            throw new BusinessException("ttlMinutes must be at least 15.");
        }
        return Math.min(requested, MAX_TTL_MINUTES);
    }

    private User currentUserOrThrow() {
        User u = currentUserOrNull();
        if (u == null) {
            throw new UnauthorizedAccessException("No authenticated user in security context.");
        }
        return u;
    }

    private User currentUserOrNull() {
        String username = SecurityUtils.getCurrentUsername();
        if (username == null || username.isBlank()) {
            return null;
        }
        return userRepository.findByUsernameIgnoreCase(username).orElse(null);
    }

    private void ensurePrivilegedAtHospital(User caller, UUID hospitalId, Set<String> roleCodes) {
        if (isSuperAdmin(caller)) {
            return;
        }
        if (!hasAnyRoleAtHospital(caller, hospitalId, roleCodes)) {
            throw new UnauthorizedAccessException(
                "User lacks the required role at hospital " + hospitalId
                    + " to declare break-the-glass access.");
        }
    }

    private boolean hasAnyRoleAtHospital(User caller, UUID hospitalId, Set<String> roleCodes) {
        return assignmentRepository.existsActiveByUserAndHospitalAndAnyRoleCode(
            caller.getId(), hospitalId, roleCodes);
    }

    private boolean isSuperAdmin(User caller) {
        return assignmentRepository
            .findFirstByUserIdAndRole_CodeIgnoreCaseAndActiveTrue(caller.getId(), SUPER_ADMIN_CODE)
            .isPresent();
    }

    private void emitAudit(AuditEventType type, User user, Hospital hospital, Patient patient,
                           String description, UUID sessionId, AuditStatus status) {
        AuditEventRequestDTO dto = AuditEventRequestDTO.builder()
            .eventType(type)
            .eventDescription(description)
            .userId(user != null ? user.getId() : null)
            .userName(user != null ? user.getUsername() : null)
            .hospitalName(hospital != null ? hospital.getName() : null)
            .resourceId(sessionId != null ? sessionId.toString() : null)
            .entityType("BREAK_GLASS_SESSION")
            .resourceName(patient != null ? "Patient " + patient.getId() : null)
            .status(status)
            .build();
        try {
            auditService.logEvent(dto);
        } catch (RuntimeException ex) {
            // logEvent is documented as best-effort but defend in depth.
            log.error("[BREAK_GLASS] Audit emission failed for session={}: {}",
                sessionId, ex.getMessage(), ex);
        }
    }

    private BreakGlassSessionResponseDTO toDto(BreakGlassSession s) {
        return BreakGlassSessionResponseDTO.builder()
            .id(s.getId())
            .userId(s.getUser() != null ? s.getUser().getId() : null)
            .userName(s.getUser() != null ? s.getUser().getUsername() : null)
            .patientId(s.getPatient() != null ? s.getPatient().getId() : null)
            .hospitalId(s.getHospital() != null ? s.getHospital().getId() : null)
            .hospitalName(s.getHospital() != null ? s.getHospital().getName() : null)
            .reason(s.getReason())
            .startedAt(s.getStartedAt())
            .expiresAt(s.getExpiresAt())
            .revokedAt(s.getRevokedAt())
            .revokedByUserId(s.getRevokedBy() != null ? s.getRevokedBy().getId() : null)
            .revokeReason(s.getRevokeReason())
            .auditCount(s.getAuditCount())
            .live(s.isLive())
            .build();
    }
}
