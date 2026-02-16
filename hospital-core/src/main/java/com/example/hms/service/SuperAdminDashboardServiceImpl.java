package com.example.hms.service;

import com.example.hms.mapper.StaffAvailabilityMapper;
import com.example.hms.payload.dto.EncounterResponseDTO;
import com.example.hms.payload.dto.PatientConsentResponseDTO;
import com.example.hms.payload.dto.StaffAvailabilityResponseDTO;
import com.example.hms.payload.dto.SuperAdminSummaryDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffAvailabilityRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class SuperAdminDashboardServiceImpl implements SuperAdminDashboardService {

    private final UserRepository userRepository;
    private final HospitalRepository hospitalRepository;
    private final PatientRepository patientRepository;
    private final RoleRepository roleRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final AuditEventLogRepository auditEventLogRepository;
    private final EncounterService encounterService;
    private final StaffAvailabilityRepository staffAvailabilityRepository;
    private final StaffAvailabilityMapper staffAvailabilityMapper;
    private final PatientConsentService patientConsentService;

    @Override
    @Transactional(readOnly = true)
    public SuperAdminSummaryDTO getSummary(int recentAuditLimit) {
        if (recentAuditLimit <= 0 || recentAuditLimit > 50) {
            recentAuditLimit = 10; // sane default + safety cap
        }

        long totalUsers = userRepository.count();
        long activeUsers = userRepository.countByIsActiveTrueAndIsDeletedFalse();
        long inactiveUsers = Math.max(totalUsers - activeUsers, 0);

        long totalHospitals = hospitalRepository.count();
        long activeHospitals = hospitalRepository.countByActiveTrue();
        long inactiveHospitals = Math.max(totalHospitals - activeHospitals, 0);

        long totalPatients = patientRepository.count();
        long totalRoles = roleRepository.count();
        long totalAssignments = assignmentRepository.count();
        long activeAssignments = assignmentRepository.countByActiveTrue();
        long inactiveAssignments = Math.max(totalAssignments - activeAssignments, 0);
        long globalAssignments = assignmentRepository.countByHospitalIsNull();
        long activeGlobalAssignments = assignmentRepository.countByHospitalIsNullAndActiveTrue();

        // Fetch recent audit events (simple page order by createdAt / eventTimestamp desc) if repository has method
    var page = auditEventLogRepository.findAllByOrderByEventTimestampDesc(PageRequest.of(0, recentAuditLimit));
        var recent = page.getContent().stream()
            .map(a -> SuperAdminSummaryDTO.RecentAuditEventDTO.builder()
                .id(a.getId().toString())
                .eventType(a.getEventType() != null ? a.getEventType().name() : null)
                .status(a.getStatus() != null ? a.getStatus().name() : null)
                .entityType(a.getEntityType())
                .resourceId(a.getResourceId())
                .resourceName(a.getResourceName())
                .userName(a.getUserName())
                .roleName(a.getRoleName())
                .hospitalName(a.getHospitalName())
                .eventTimestamp(a.getEventTimestamp())
                .eventDescription(a.getEventDescription())
                .build())
            .toList();

        return SuperAdminSummaryDTO.builder()
            .totalUsers(totalUsers)
            .activeUsers(activeUsers)
            .inactiveUsers(inactiveUsers)
            .totalHospitals(totalHospitals)
            .activeHospitals(activeHospitals)
            .inactiveHospitals(inactiveHospitals)
            .totalPatients(totalPatients)
            .totalRoles(totalRoles)
            .totalAssignments(totalAssignments)
            .activeAssignments(activeAssignments)
            .inactiveAssignments(inactiveAssignments)
            .globalAssignments(globalAssignments)
            .activeGlobalAssignments(activeGlobalAssignments)
            .recentAuditEvents(recent)
            .generatedAt(LocalDateTime.now())
            .build();
    }

    @Override
    @Transactional(readOnly = true)
    public List<EncounterResponseDTO> getRecentEncounters(int limit, Locale locale) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "createdAt"));
        return encounterService
            .list(null, null, null, null, null, null, pageable, locale)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffAvailabilityResponseDTO> getRecentStaffAvailability(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "date"));
        return staffAvailabilityRepository
            .findAllByOrderByDateDesc(pageable)
            .map(staffAvailabilityMapper::toDto)
            .getContent();
    }

    @Override
    @Transactional(readOnly = true)
    public List<PatientConsentResponseDTO> getRecentPatientConsents(int limit) {
        int safeLimit = sanitizeLimit(limit, 20, 100);
        var pageable = PageRequest.of(0, safeLimit, Sort.by(Sort.Direction.DESC, "consentTimestamp"));
        return patientConsentService.getAllConsents(pageable).getContent();
    }

    private int sanitizeLimit(int requested, int defaultValue, int maxValue) {
        if (requested <= 0) {
            return defaultValue;
        }
        return Math.min(requested, maxValue);
    }
}
