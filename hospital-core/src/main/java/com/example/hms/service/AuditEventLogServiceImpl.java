package com.example.hms.service;

import com.example.hms.enums.ActorType;
import com.example.hms.enums.AuditEventType;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.ImpersonationContext;
import com.example.hms.security.context.ImpersonationContextHolder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import com.example.hms.enums.AuditStatus;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditEventLogServiceImpl implements AuditEventLogService {

    private final UserRepository userRepository;
    private final AuditEventLogRepository auditRepository;
    private final AuditEventLogMapper auditMapper;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final ObjectMapper objectMapper;
    private final PatientRepository patientRepository;
    private final StaffRepository staffRepository;

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventLogResponseDTO> getAuditLogsByUser(UUID userId, Pageable pageable) {
        return auditRepository.findByUserId(userId, pageable)
            .map(auditMapper::toDto);

    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventLogResponseDTO> getAuditLogsByEventTypeAndStatus(AuditEventType type, AuditStatus status, Pageable pageable) {
        Page<AuditEventLog> logs;

        if (status == null) {
            logs = auditRepository.findByEventType(type, pageable);
        } else {
            logs = auditRepository.findByEventTypeAndStatus(type, status, pageable);
        }

        return logs.map(auditMapper::toDto);

    }


    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventLogResponseDTO> getAuditLogsByTarget(String entityType, String resourceId, Pageable pageable) {
        return auditRepository
            .findByEntityTypeIgnoreCaseAndResourceId(entityType.trim(), resourceId.trim(), pageable)
            .map(auditMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventLogResponseDTO> getAuditLogsByDateRange(LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable) {
        return auditRepository.findByDateRange(fromDate, toDate, pageable)
            .map(auditMapper::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AuditEventLogResponseDTO> getAuditLogsByHospital(UUID hospitalId, Pageable pageable) {
        return auditRepository
            .findByAssignment_Hospital_IdOrderByEventTimestampDesc(hospitalId, pageable)
            .map(auditMapper::toDto);
    }


    /**
     * Best-effort audit logging. Returns the persisted DTO on success, or {@code null}
     * if the audit event could not be recorded. Audit failures are logged but
     * <b>never propagated</b> — callers are guaranteed this method will not throw.
     */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public AuditEventLogResponseDTO logEvent(AuditEventRequestDTO requestDTO) {
        try {
            return doLogEvent(requestDTO);
        } catch (Exception e) {
            log.error("[AUDIT] Failed to persist audit event (eventType={}, entityType={}, resourceId={}, userId={}, userName={}): {}",
                    requestDTO.getEventType(),
                    requestDTO.getEntityType(),
                    requestDTO.getResourceId(),
                    requestDTO.getUserId(),
                    requestDTO.getUserName(),
                    e.getMessage(), e);
            return null;
        }
    }

    /**
     * Internal implementation that does the actual audit persistence.
     * Separated from {@link #logEvent} so the outer method can swallow exceptions.
     */
    private AuditEventLogResponseDTO doLogEvent(AuditEventRequestDTO requestDTO) {
        User user = resolveUser(requestDTO);
        UserRoleHospitalAssignment assignment = resolveAssignment(requestDTO, user);
        ActorContext actor = resolveActorContext(requestDTO, user);
        String hospitalName = resolveHospitalName(requestDTO, assignment);
        String roleName = resolveRoleName(requestDTO, assignment, user);
        String detailsStr = convertDetailsToString(requestDTO.getDetails());

        String resourceId = requestDTO.getResourceId();
        String resourceName = requestDTO.getResourceName();
        if ("PATIENT".equalsIgnoreCase(requestDTO.getEntityType())) {
            resourceId = resolvePatientResourceId(resourceId, resourceName);
            resourceName = resolvePatientResourceName(resourceName, resourceId);
        }

        ImpersonatorIdentity impersonator = resolveImpersonatorIdentity(requestDTO);

        ActorType actorType = actor.actorType();
        String userName = actor.userName();
        UUID impersonatorUserId = impersonator.userId();
        String impersonatorUsername = impersonator.username();

        AuditEventLog event = AuditEventLog.builder()
                .user(user)          // nullable — SYSTEM / bootstrap flows have no actor user
                .assignment(assignment)
                .actorType(actorType)
                .actorLabel(userName)
                .eventType(requestDTO.getEventType())
                .eventDescription(requestDTO.getEventDescription())
                .resourceId(resourceId != null && !resourceId.isBlank() ? resourceId : "Unknown Resource")
                .entityType(requestDTO.getEntityType())
                .status(requestDTO.getStatus())
                .details(detailsStr)
                .ipAddress(requestDTO.getIpAddress())
                .userName(userName)
                .hospitalName(hospitalName)
                .roleName(roleName)
                .resourceName(resourceName != null && !resourceName.isBlank() ? resourceName : "Unknown Resource")
                .eventTimestamp(java.time.LocalDateTime.now())
                .impersonatorUserId(impersonatorUserId)
                .impersonatorUsername(impersonatorUsername)
                .patientId(resolvePatientKey(requestDTO, resourceId))
                .build();

        AuditEventLog saved = auditRepository.save(event);
        return auditMapper.toDto(saved);
    }

    /**
     * The patient this event concerns (V141 / Tier 2 item 39).
     *
     * <p>An explicit {@code patientId} always wins. Absent one, fall back to
     * the pre-V141 convention — {@code entityType = "PATIENT"} means the
     * resource id <i>is</i> the patient — so the emitters that already
     * followed it keep working untouched. The emitters that did not follow
     * it (break-the-glass keys on the session, eligibility on the check)
     * are the ones that now pass the id explicitly; they are why the
     * column exists.
     *
     * <p>The parse is guarded because {@code resourceId} is free text and
     * {@link #doLogEvent} substitutes the literal {@code "Unknown Resource"}
     * when it is blank.
     */
    private UUID resolvePatientKey(AuditEventRequestDTO requestDTO, String resolvedResourceId) {
        if (requestDTO.getPatientId() != null) {
            return requestDTO.getPatientId();
        }
        if (!"PATIENT".equalsIgnoreCase(requestDTO.getEntityType())) {
            return null;
        }
        // A PATIENT-typed row whose resource id is not a UUID keeps the event
        // and loses only the key. Never the other way round.
        UUID parsed = parseUuidOrNull(resolvedResourceId);
        if (parsed == null) {
            log.debug("[AUDIT] entityType=PATIENT but resourceId is not a UUID ({}) — no patient key",
                resolvedResourceId);
        }
        return parsed;
    }

    /**
     * Resolve the acting user. Returns {@code null} when no user can be found
     * (e.g. SYSTEM / bootstrap flows) — this is intentional, not an error.
     */
    private User resolveUser(AuditEventRequestDTO requestDTO) {
        if (requestDTO.getUserId() != null) {
            return userRepository.findById(requestDTO.getUserId()).orElse(null);
        }
        if (requestDTO.getUserName() != null && !requestDTO.getUserName().isBlank()) {
            return userRepository.findByUsername(requestDTO.getUserName()).orElse(null);
        }
        return null;
    }

    private UserRoleHospitalAssignment resolveAssignment(AuditEventRequestDTO requestDTO, User user) {
        UserRoleHospitalAssignment assignment = null;
        if (requestDTO.getAssignmentId() != null) {
            assignment = assignmentRepository.findById(requestDTO.getAssignmentId()).orElse(null);
        } else if (requestDTO.getRoleName() != null && requestDTO.getHospitalName() != null && user != null) {
            assignment = assignmentRepository.findByUserIdAndRoleNameAndHospitalName(
                user.getId(), requestDTO.getRoleName(), requestDTO.getHospitalName()
            ).orElse(null);
        }
        if (assignment != null && user != null && assignment.getUser() != null
            && !assignment.getUser().getId().equals(user.getId())) {
            log.debug("Audit assignment/user mismatch (assignment user id: {}, actor id: {}). Dropping assignment link.",
                assignment.getUser().getId(), user.getId());
            return null;
        }
        return assignment;
    }

    private String resolveHospitalName(AuditEventRequestDTO requestDTO, UserRoleHospitalAssignment assignment) {
        String hospitalName = requestDTO.getHospitalName();
        if ((hospitalName == null || hospitalName.isBlank()) && assignment != null && assignment.getHospital() != null) {
            hospitalName = assignment.getHospital().getName();
        }
        return hospitalName;
    }

    private String resolveRoleName(AuditEventRequestDTO requestDTO, UserRoleHospitalAssignment assignment, User user) {
        String roleName = requestDTO.getRoleName();
        if ((roleName == null || roleName.isBlank()) && assignment != null && assignment.getRole() != null) {
            roleName = assignment.getRole().getName();
        }
        if ((roleName == null || roleName.isBlank()) && user != null && user.getUserRoles() != null && !user.getUserRoles().isEmpty()) {
            roleName = user.getUserRoles().iterator().next().getRole().getName();
        }
        if (roleName == null || roleName.isBlank()) {
            roleName = "Unknown Role";
        }
        return roleName;
    }

    private String resolvePatientResourceId(String resourceId, String resourceName) {
        if ((resourceId != null && !resourceId.isBlank()) || resourceName == null || resourceName.isBlank()) {
            return resourceId;
        }
        Patient patient = findPatientByName(resourceName);
        if (patient == null && resourceName.contains("@")) {
            patient = patientRepository.findByEmailContainingIgnoreCase(resourceName)
                .stream().findFirst().orElse(null);
        }
        if (patient == null && resourceName.matches("\\d{6,}")) {
            patient = findPatientByPhone(resourceName);
        }
        return patient != null ? patient.getId().toString() : resourceId;
    }

    private Patient findPatientByName(String resourceName) {
        String[] parts = resourceName.trim().split(" ");
        if (parts.length < 2) {
            return null;
        }
        String first = parts[0];
        String last = parts[parts.length - 1];
        return patientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(first, last)
            .stream().findFirst().orElse(null);
    }

    private Patient findPatientByPhone(String resourceName) {
        Patient patient = patientRepository.findByPhoneNumberPrimary(resourceName).orElse(null);
        if (patient == null) {
            patient = patientRepository.findByPhoneNumberSecondary(resourceName).orElse(null);
        }
        return patient;
    }

    private String resolvePatientResourceName(String resourceName, String resourceId) {
        if ((resourceName != null && !resourceName.isBlank()) || resourceId == null || resourceId.isBlank()) {
            return resourceName;
        }
        // Guarded. This used to be a bare UUID.fromString(resourceId), and a
        // PATIENT-typed event whose resource id was not a UUID threw
        // IllegalArgumentException here — which logEvent catches and
        // swallows, so the AUDIT ROW WAS SILENTLY DROPPED. An audit trail
        // that discards writes it cannot label is the worst failure mode
        // available to it: the event is gone, and nothing anywhere says so.
        // Falling back to the raw id is what the .orElse below already
        // intended for the not-found case. Found by the V141 tests.
        UUID parsed = parseUuidOrNull(resourceId);
        if (parsed == null) {
            return resourceId;
        }
        return patientRepository.findById(parsed)
            .map(Patient::getFullName).orElse(resourceId);
    }

    /** {@link UUID#fromString} without the throw. */
    private UUID parseUuidOrNull(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(candidate.trim());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Helper method to convert the details object to a JSON string.
     */
    private String convertDetailsToString(Object details) {
        if (details == null) {
            return null;
        }
        if (details instanceof String string) {
            return string;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize audit event details: {}", e.getMessage());
            return "{\"error\":\"Could not serialize details object\"}";
        }
    }

    // ── Cognitive-complexity extracts (Sonar S3776) ───────────────────────────

    /** (actorType, userName) pair derived from the resolved user + request. */
    private record ActorContext(ActorType actorType, String userName) {}

    /** (userId, username) pair for the impersonating super-admin, if any. */
    private record ImpersonatorIdentity(UUID userId, String username) {}

    private ActorContext resolveActorContext(AuditEventRequestDTO requestDTO, User user) {
        if (user != null) {
            return new ActorContext(ActorType.USER, user.getFirstName() + " " + user.getLastName());
        }
        if (requestDTO.getUserName() != null && !requestDTO.getUserName().isBlank()) {
            return new ActorContext(ActorType.SYSTEM, requestDTO.getUserName());
        }
        return new ActorContext(ActorType.SYSTEM, "SYSTEM");
    }

    /**
     * MVP-4 — caller may have explicitly set impersonator fields on the request
     * (boundary events from SupportImpersonationService); for every other event
     * we read the request-scoped ImpersonationContext so any action taken under
     * a support-impersonation token carries the real super-admin's identity
     * even though {@code user} points at the impersonated target.
     */
    private ImpersonatorIdentity resolveImpersonatorIdentity(AuditEventRequestDTO requestDTO) {
        UUID userId = requestDTO.getImpersonatorUserId();
        String username = requestDTO.getImpersonatorUsername();
        if (userId != null && username != null) {
            return new ImpersonatorIdentity(userId, username);
        }
        ImpersonationContext ctx = ImpersonationContextHolder.get().orElse(null);
        if (ctx == null) {
            return new ImpersonatorIdentity(userId, username);
        }
        return new ImpersonatorIdentity(
            userId != null ? userId : ctx.impersonatorUserId(),
            username != null ? username : ctx.impersonatorUsername()
        );
    }
}

