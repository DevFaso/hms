package com.example.hms.mapper;

import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import com.example.hms.repository.PatientRepository;
import java.util.Locale;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class AuditEventLogMapper {
    private final PatientRepository patientRepository;

    public AuditEventLogResponseDTO toDto(AuditEventLog event) {
        return toDtoInternal(event, true);
    }

    /**
     * MVP-8: per-row mapping for cross-tenant audit search.
     * <p>Skips the {@link PatientRepository#findById} lookup that
     * {@link #resolveResourceName} performs for {@code PATIENT} events,
     * eliminating the N+1 query on a multi-page result set. Falls back
     * to the {@code resourceName} column already denormalised on the
     * entity (or {@code resourceId} when blank) — sufficient for the
     * search-results table where each row links into the patient detail
     * page anyway. (PR #228 review).
     */
    public AuditEventLogResponseDTO toDtoLite(AuditEventLog event) {
        return toDtoInternal(event, false);
    }

    private AuditEventLogResponseDTO toDtoInternal(AuditEventLog event, boolean resolvePatientName) {
        if (event == null) {
            return null;
        }

        User user = event.getUser();
        UserRoleHospitalAssignment assignment = event.getAssignment();

        String resourceName = resolvePatientName
            ? resolveResourceName(event)
            : resolveResourceNameFromColumns(event);
        String hospitalName = resolveHospitalName(event, assignment);
        String roleName = resolveRoleName(event, assignment, user);

        return AuditEventLogResponseDTO.builder()
            .id(event.getId())
            .userName(getUserFullName(user))
            .hospitalName(hospitalName)
            .roleName(roleName)
            .eventType(event.getEventType() != null ? event.getEventType().name() : null)
            .eventDescription(event.getEventDescription())
            .details(event.getDetails())
            .eventTimestamp(event.getEventTimestamp())
            .ipAddress(event.getIpAddress())
            .status(event.getStatus() != null ? event.getStatus().name() : null)
            .resourceId(event.getResourceId())
            .resourceName(resourceName)
            .entityType(event.getEntityType())
            .actorType(event.getActorType() != null ? event.getActorType().name() : null)
            .actorLabel(event.getActorLabel())
            .impersonatorUserId(event.getImpersonatorUserId())
            .impersonatorUsername(event.getImpersonatorUsername())
            .build();
    }

    private String resolveResourceName(AuditEventLog event) {
        if (event.getEntityType() == null || event.getResourceId() == null) {
            return null;
        }
        if ("PATIENT".equals(event.getEntityType().toUpperCase(Locale.ROOT))) {
            try {
                return patientRepository.findById(UUID.fromString(event.getResourceId()))
                    .map(Patient::getFullName).orElse(event.getResourceId());
            } catch (RuntimeException e) {
                return event.getResourceId();
            }
        }
        return event.getResourceId();
    }

    /** MVP-8 fast path — same fallback chain as {@link #resolveResourceName}
     *  but never hits PatientRepository. */
    private String resolveResourceNameFromColumns(AuditEventLog event) {
        if (event.getResourceName() != null && !event.getResourceName().isBlank()) {
            return event.getResourceName();
        }
        return event.getResourceId();
    }

    private String resolveHospitalName(AuditEventLog event, UserRoleHospitalAssignment assignment) {
        String hospitalName = event.getHospitalName();
        if ((hospitalName == null || hospitalName.isBlank()) && assignment != null && assignment.getHospital() != null) {
            hospitalName = assignment.getHospital().getName();
        }
        return hospitalName;
    }

    private String resolveRoleName(AuditEventLog event, UserRoleHospitalAssignment assignment, User user) {
        String roleName = event.getRoleName();
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

    private String getUserFullName(User user) {
        if (user == null) {
            return null;
        }
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        return (first + " " + last).trim();
    }
}
