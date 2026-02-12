package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.mapper.StaffAvailabilityMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.StaffAvailability;
import com.example.hms.payload.dto.*;
import com.example.hms.repository.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.*;

import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SuperAdminDashboardServiceImplTest {

    @Mock private UserRepository userRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AuditEventLogRepository auditEventLogRepository;
    @Mock private EncounterService encounterService;
    @Mock private StaffAvailabilityRepository staffAvailabilityRepository;
    @Mock private StaffAvailabilityMapper staffAvailabilityMapper;
    @Mock private PatientConsentService patientConsentService;

    @InjectMocks private SuperAdminDashboardServiceImpl service;

    @Test
    void getSummary_success() {
        when(userRepository.count()).thenReturn(100L);
        when(userRepository.countByIsActiveTrueAndIsDeletedFalse()).thenReturn(80L);
        when(hospitalRepository.count()).thenReturn(10L);
        when(hospitalRepository.countByActiveTrue()).thenReturn(8L);
        when(patientRepository.count()).thenReturn(500L);
        when(roleRepository.count()).thenReturn(5L);
        when(assignmentRepository.count()).thenReturn(200L);
        when(assignmentRepository.countByActiveTrue()).thenReturn(150L);
        when(assignmentRepository.countByHospitalIsNull()).thenReturn(10L);
        when(assignmentRepository.countByHospitalIsNullAndActiveTrue()).thenReturn(8L);

        AuditEventLog auditLog = AuditEventLog.builder()
            .eventType(AuditEventType.USER_CREATE)
            .status(AuditStatus.SUCCESS)
            .entityType("USER")
            .eventTimestamp(LocalDateTime.now())
            .build();
        auditLog.setId(UUID.randomUUID());
        Page<AuditEventLog> auditPage = new PageImpl<>(List.of(auditLog));
        when(auditEventLogRepository.findAllByOrderByEventTimestampDesc(any(Pageable.class))).thenReturn(auditPage);

        SuperAdminSummaryDTO result = service.getSummary(10);

        assertThat(result).isNotNull();
        assertThat(result.getTotalUsers()).isEqualTo(100L);
        assertThat(result.getActiveUsers()).isEqualTo(80L);
        assertThat(result.getInactiveUsers()).isEqualTo(20L);
        assertThat(result.getTotalHospitals()).isEqualTo(10L);
        assertThat(result.getTotalPatients()).isEqualTo(500L);
        assertThat(result.getRecentAuditEvents()).hasSize(1);
    }

    @Test
    void getSummary_zeroValues() {
        when(userRepository.count()).thenReturn(0L);
        when(userRepository.countByIsActiveTrueAndIsDeletedFalse()).thenReturn(0L);
        when(hospitalRepository.count()).thenReturn(0L);
        when(hospitalRepository.countByActiveTrue()).thenReturn(0L);
        when(patientRepository.count()).thenReturn(0L);
        when(roleRepository.count()).thenReturn(0L);
        when(assignmentRepository.count()).thenReturn(0L);
        when(assignmentRepository.countByActiveTrue()).thenReturn(0L);
        when(assignmentRepository.countByHospitalIsNull()).thenReturn(0L);
        when(assignmentRepository.countByHospitalIsNullAndActiveTrue()).thenReturn(0L);
        when(auditEventLogRepository.findAllByOrderByEventTimestampDesc(any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of()));

        SuperAdminSummaryDTO result = service.getSummary(-1);
        assertThat(result).isNotNull();
    }

    @Test
    void getRecentEncounters_success() {
        Page<EncounterResponseDTO> page = new PageImpl<>(List.of(new EncounterResponseDTO()));
        when(encounterService.list(any(), any(), any(), any(), any(), any(), any(Pageable.class), any(Locale.class)))
            .thenReturn(page);

        List<EncounterResponseDTO> result = service.getRecentEncounters(5, Locale.ENGLISH);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentStaffAvailability_success() {
        StaffAvailability sa = new StaffAvailability();
        Page<StaffAvailability> page = new PageImpl<>(List.of(sa));
        when(staffAvailabilityRepository.findAllByOrderByDateDesc(any(Pageable.class))).thenReturn(page);
        StaffAvailabilityResponseDTO dto = new StaffAvailabilityResponseDTO(
            UUID.randomUUID(), UUID.randomUUID(), "name", "lic",
            UUID.randomUUID(), "hosp", UUID.randomUUID(), "dept", "deptTr",
            null, null, null, false, null);
        when(staffAvailabilityMapper.toDto(sa)).thenReturn(dto);

        List<StaffAvailabilityResponseDTO> result = service.getRecentStaffAvailability(5);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentPatientConsents_success() {
        Page<PatientConsentResponseDTO> page = new PageImpl<>(List.of(PatientConsentResponseDTO.builder().build()));
        when(patientConsentService.getAllConsents(any(Pageable.class))).thenReturn(page);

        List<PatientConsentResponseDTO> result = service.getRecentPatientConsents(5);
        assertThat(result).hasSize(1);
    }

    @Test
    void getRecentEncounters_emptyPage() {
        Page<EncounterResponseDTO> page = new PageImpl<>(List.of());
        when(encounterService.list(any(), any(), any(), any(), any(), any(), any(Pageable.class), any(Locale.class)))
            .thenReturn(page);

        List<EncounterResponseDTO> result = service.getRecentEncounters(-1, Locale.ENGLISH);
        assertThat(result).isEmpty();
    }
}
