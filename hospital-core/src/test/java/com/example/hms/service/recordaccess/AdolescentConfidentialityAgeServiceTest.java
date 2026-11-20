package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.recordaccess.AdolescentConfidentialityAgeDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Policy 2026-11-20 (provisional): the per-hospital adolescent confidentiality age. */
@DisplayName("Policy 2026-11-20: a hospital's adolescent confidentiality age")
class AdolescentConfidentialityAgeServiceTest {

    private final HospitalRepository hospitalRepository = mock(HospitalRepository.class);
    private final AuditEventLogService auditEventLogService = mock(AuditEventLogService.class);
    private final AdolescentConfidentialityAgeService service =
        new AdolescentConfidentialityAgeService(hospitalRepository, auditEventLogService);
    private final Hospital hospital = new Hospital();
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        hospital.setId(hospitalId);
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitalRepository.save(hospital)).thenReturn(hospital);
    }

    @Test
    @DisplayName("a hospital that set none uses the provisional default, 12, and is told it is provisional")
    void theProvisionalDefault() {
        AdolescentConfidentialityAgeDTO age = service.get(hospitalId);

        assertThat(age.configuredAge()).isNull();
        assertThat(age.effectiveAge()).isEqualTo(12);
        assertThat(age.defaultAge()).isEqualTo(12);
        assertThat(age.provisional()).isTrue();
    }

    @Test
    @DisplayName("the hospital sets its own age: stored, read back, and the change is audited with before and after")
    void theHospitalSetsItsOwn() {
        AdolescentConfidentialityAgeDTO set = service.set(hospitalId, 14, adminId);

        assertThat(hospital.getAdolescentConfidentialityAge()).isEqualTo(14);
        assertThat(set.configuredAge()).isEqualTo(14);
        assertThat(service.get(hospitalId).effectiveAge()).isEqualTo(14);
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
        assertThat(audit.getValue().getEntityType()).isEqualTo("HOSPITAL_ADOLESCENT_CONFIDENTIALITY_AGE");
        assertThat(audit.getValue().getUserId()).isEqualTo(adminId);
        assertThat(audit.getValue().getDetails()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP).containsEntry("from", "null").containsEntry("to", "14");
    }

    @Test
    @DisplayName("null returns the hospital to the default; the same value again writes and audits nothing")
    void backToTheDefault() {
        hospital.setAdolescentConfidentialityAge(15);

        assertThat(service.set(hospitalId, null, adminId).effectiveAge()).isEqualTo(12);
        assertThat(hospital.getAdolescentConfidentialityAge()).isNull();
        verify(auditEventLogService).logEvent(any());

        service.set(hospitalId, null, adminId);
        verify(hospitalRepository).save(hospital);
        verify(auditEventLogService).logEvent(any());
        verify(hospitalRepository, never()).delete(any());
    }
}
