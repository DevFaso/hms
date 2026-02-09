package com.example.hms.mapper;

import com.example.hms.model.AuditEventLog;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import org.springframework.stereotype.Component;


@Component
public class AuditEventLogMapper {
    public AuditEventLogResponseDTO toDto(AuditEventLog event) {
        if (event == null) return null;

        User user = event.getUser();
        UserRoleHospitalAssignment assignment = event.getAssignment();

        return AuditEventLogResponseDTO.builder()
            .userName(getUserFullName(user))
            .hospitalName(resolveHospitalName(event.getHospitalName(), assignment))
            .roleName(resolveRoleName(event.getRoleName(), assignment, user))
            .eventType(event.getEventType() != null ? event.getEventType().name() : null)
            .eventDescription(event.getEventDescription())
            .details(event.getDetails())
            .eventTimestamp(event.getEventTimestamp())
            .ipAddress(event.getIpAddress())
            .status(event.getStatus() != null ? event.getStatus().name() : null)
            .resourceId(event.getResourceId())
            .resourceName(resolveResourceName(event))
            .entityType(event.getEntityType())
            .build();
    }

    private String resolveResourceName(AuditEventLog event) {
        String name = event.getResourceName();
        return (name != null && !name.isBlank()) ? name : event.getResourceId();
    }

    private String resolveHospitalName(String hospitalName, UserRoleHospitalAssignment assignment) {
        if ((hospitalName == null || hospitalName.isBlank()) && assignment != null && assignment.getHospital() != null) {
            return assignment.getHospital().getName();
        }
        return hospitalName;
    }

    private String resolveRoleName(String roleName, UserRoleHospitalAssignment assignment, User user) {
        if ((roleName == null || roleName.isBlank()) && assignment != null && assignment.getRole() != null) {
            roleName = assignment.getRole().getName();
        }
        if ((roleName == null || roleName.isBlank()) && user != null && user.getUserRoles() != null && !user.getUserRoles().isEmpty()) {
            roleName = user.getUserRoles().iterator().next().getRole().getName();
        }
        return (roleName == null || roleName.isBlank()) ? "Unknown Role" : roleName;
    }

    private String getUserFullName(User user) {
        if (user == null) return null;
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        return (first + " " + last).trim();
    }
}
