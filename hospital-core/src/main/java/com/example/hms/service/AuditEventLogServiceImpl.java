package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.ResourceNotFoundException;
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
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.hms.enums.AuditStatus;

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
    @Transactional
    public AuditEventLogResponseDTO logEvent(AuditEventRequestDTO requestDTO) {
        try {
            // Resolve user by name if userId is null
            User user = null;
            if (requestDTO.getUserId() != null) {
                user = userRepository.findById(requestDTO.getUserId())
                    .orElseThrow(() -> new ResourceNotFoundException("user.notfound", requestDTO.getUserId().toString()));
            } else if (requestDTO.getUserName() != null) {
                user = userRepository.findByUsername(requestDTO.getUserName())
                    .orElseThrow(() -> new ResourceNotFoundException("user.notfound", requestDTO.getUserName()));
            }

            // Resolve assignment by name if assignmentId is null
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
                assignment = null;
            }

            String userName = user != null ? (user.getFirstName() + " " + user.getLastName()) : requestDTO.getUserName();

            // Always try to fetch hospitalName and roleName from assignment if null
            String hospitalName = requestDTO.getHospitalName();
            String roleName = requestDTO.getRoleName();
            if ((hospitalName == null || hospitalName.isBlank()) && assignment != null && assignment.getHospital() != null) {
                hospitalName = assignment.getHospital().getName();
            }
            if ((roleName == null || roleName.isBlank()) && assignment != null && assignment.getRole() != null) {
                roleName = assignment.getRole().getName();
            }
            // If still null, fallback to user role if available
            if ((roleName == null || roleName.isBlank()) && user != null && user.getUserRoles() != null && !user.getUserRoles().isEmpty()) {
                roleName = user.getUserRoles().iterator().next().getRole().getName();
            }
            // Final fallback
            if (roleName == null || roleName.isBlank()) {
                roleName = "Unknown Role";
            }

            String detailsStr = convertDetailsToString(requestDTO.getDetails());

            // Resolve resourceId by resourceName if resourceId is null
            String resourceId = requestDTO.getResourceId();
            String resourceName = requestDTO.getResourceName();
            if ("PATIENT".equalsIgnoreCase(requestDTO.getEntityType())) {
                // If resourceId is missing, try to fetch by name (full name, email, phone)
                if ((resourceId == null || resourceId.isBlank()) && resourceName != null && !resourceName.isBlank()) {
                    Patient patient = null;
                    // Try full name match
                    String[] parts = resourceName.trim().split(" ");
                    if (parts.length >= 2) {
                        String first = parts[0];
                        String last = parts[parts.length - 1];
                        patient = patientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase(first, last)
                            .stream().findFirst().orElse(null);
                    }
                    // Try email match if not found
                    if (patient == null && resourceName.contains("@")) {
                        patient = patientRepository.findByEmailContainingIgnoreCase(resourceName)
                            .stream().findFirst().orElse(null);
                    }
                    // Try phone match if not found
                    if (patient == null && resourceName.matches("\\d{6,}")) {
                        patient = patientRepository.findByPhoneNumberPrimary(resourceName).orElse(null);
                        if (patient == null) {
                            patient = patientRepository.findByPhoneNumberSecondary(resourceName).orElse(null);
                        }
                    }
                    if (patient != null) {
                        resourceId = patient.getId().toString();
                    }
                }
                // If resourceName is missing, try to fetch by id
                if ((resourceName == null || resourceName.isBlank()) && resourceId != null && !resourceId.isBlank()) {
                    resourceName = patientRepository.findById(UUID.fromString(resourceId))
                        .map(Patient::getFullName).orElse(resourceId);
                }
            }

            AuditEventLog event = AuditEventLog.builder()
                .user(user)
                .assignment(assignment)
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
                .build();

            AuditEventLog saved = auditRepository.save(event);
            return auditMapper.toDto(saved);

        } catch (Exception e) {
            log.warn("Failed to log audit event for user {}: {}", requestDTO.getUserName(), e.getMessage(), e);
            throw new RuntimeException("audit.log.failed");
        }
    }

    /**
     * Helper method to convert the details object to a JSON string.
     */
    private String convertDetailsToString(Object details) {
        if (details == null) {
            return null;
        }
        if (details instanceof String) {
            return (String) details;
        }
        try {
            return objectMapper.writeValueAsString(details);
        } catch (JsonProcessingException e) {
            log.warn("Could not serialize audit event details: {}", e.getMessage());
            return "{\"error\":\"Could not serialize details object\"}";
        }
    }
}

