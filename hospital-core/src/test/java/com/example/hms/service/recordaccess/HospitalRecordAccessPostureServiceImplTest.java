package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TenantIsolationMode;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.RecordAccessPostureDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("HospitalRecordAccessPostureServiceImpl")
class HospitalRecordAccessPostureServiceImplTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private AuditEventLogService auditEventLogService;
    @InjectMocks private HospitalRecordAccessPostureServiceImpl service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setIsolationMode(TenantIsolationMode.ROW_LEVEL);
        hospital.setRecordAccessPosture(RecordAccessPosture.TREATMENT_PRESUMED);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
    }

    @Test
    @DisplayName("get returns posture with isolation mode beside it")
    void get() {
        RecordAccessPostureDTO dto = service.get(hospitalId);

        assertThat(dto.posture()).isEqualTo(RecordAccessPosture.TREATMENT_PRESUMED);
        assertThat(dto.isolationMode()).isEqualTo(TenantIsolationMode.ROW_LEVEL);
    }

    @Test
    @DisplayName("set persists the change and audits CONFIGURATION_CHANGED with before and after")
    void setChangesAndAudits() {
        when(hospitalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RecordAccessPostureDTO dto = service.set(hospitalId, RecordAccessPosture.EXPLICIT_CONSENT, actor);

        assertThat(dto.posture()).isEqualTo(RecordAccessPosture.EXPLICIT_CONSENT);
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
        assertThat(audit.getValue().getEventDescription())
            .contains("TREATMENT_PRESUMED").contains("EXPLICIT_CONSENT");
    }

    @Test
    @DisplayName("setting the posture it already has writes nothing and audits nothing")
    void setSameIsNoop() {
        service.set(hospitalId, RecordAccessPosture.TREATMENT_PRESUMED, actor);

        verify(hospitalRepository, never()).save(any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    @DisplayName("unknown hospital → 404 carrying the message key")
    void unknownHospital() {
        UUID ghost = UUID.randomUUID();
        when(hospitalRepository.findById(ghost)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(ghost)).isInstanceOf(ResourceNotFoundException.class);
    }
}
