package com.example.hms.mapper;

import com.example.hms.model.AuditEventLog;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.hms.repository.PatientRepository;
import java.util.Locale;
import java.util.UUID;

@Component
public class AuditEventLogMapper {
    @Autowired
    private PatientRepository patientRepository;

    public AuditEventLogResponseDTO toDto(AuditEventLog event) {
        if (event == null) return null;

        User user = event.getUser();
        UserRoleHospitalAssignment assignment = event.getAssignment();

        // Resolve resource name for PATIENT entity type
        String resourceName = null;
        if (event.getEntityType() != null && event.getResourceId() != null) {
            switch (event.getEntityType().toUpperCase(Locale.ROOT)) {
                case "PATIENT":
                    try {
                        resourceName = patientRepository.findById(UUID.fromString(event.getResourceId()))
                            .map(p -> p.getFullName()).orElse(event.getResourceId());
                    } catch (Exception e) {
                        resourceName = event.getResourceId();
                    }
                    break;
                default:
                    resourceName = event.getResourceId();
            }
        }

        // Hospital Name
        String hospitalName = event.getHospitalName();
        if ((hospitalName == null || hospitalName.isBlank()) && assignment != null && assignment.getHospital() != null) {
            hospitalName = assignment.getHospital().getName();
        }

        // Role Name
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

        return AuditEventLogResponseDTO.builder()
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
            .build();
    }

    private String getUserFullName(User user) {
        if (user == null) return null;
        String first = user.getFirstName() != null ? user.getFirstName() : "";
        String last = user.getLastName() != null ? user.getLastName() : "";
        return (first + " " + last).trim();
    }
}
