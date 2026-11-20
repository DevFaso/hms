package com.example.hms.service.imaging;

import com.example.hms.enums.AuditEventType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingReportingSettingsDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Policy rule 3 (2026-11-20, provisional): « Échographie : compte rendu par un non-radiologue ». */
@ExtendWith(MockitoExtension.class)
class ImagingReportingSettingsServiceTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private AuditEventLogService auditEventLogService;

    private ImagingReportingSettingsService service;
    private Hospital hospital;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new ImagingReportingSettingsService(hospitalRepository, auditEventLogService);
        hospital = new Hospital();
        hospital.setId(hospitalId);
    }

    @Test
    void aHospitalStartsOnPhysiciansOnly() {
        assertThat(Hospital.builder().build().isUltrasoundByNonPhysician()).isFalse();
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));

        assertThat(service.get(hospitalId))
            .isEqualTo(new ImagingReportingSettingsDTO(hospitalId, false));
    }

    @Test
    void aChangeIsSavedAndAuditedWithTheNewValue() {
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));

        ImagingReportingSettingsDTO saved = service.setUltrasoundByNonPhysician(hospitalId, true, admin);

        assertThat(saved.ultrasoundByNonPhysician()).isTrue();
        assertThat(hospital.isUltrasoundByNonPhysician()).isTrue();
        verify(hospitalRepository).save(hospital);
        ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(auditEventLogService).logEvent(audit.capture());
        assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
        assertThat(audit.getValue().getUserId()).isEqualTo(admin);
        assertThat(audit.getValue().getHospitalId()).isEqualTo(hospitalId);
        assertThat(audit.getValue().getEntityType()).isEqualTo("HOSPITAL_IMAGING_REPORTING_SETTINGS");
        assertThat(audit.getValue().getDetails())
            .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("ultrasoundByNonPhysician", "true");
    }

    @Test
    void theSameValueWritesNothingAndAuditsNothing() {
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));

        service.setUltrasoundByNonPhysician(hospitalId, false, admin);

        verify(hospitalRepository, never()).save(any());
        verify(auditEventLogService, never()).logEvent(any());
    }

    @Test
    void aFailedAuditNeverFailsTheChange_andAnUnknownHospitalIsMissing() {
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        doThrow(new IllegalStateException("audit down")).when(auditEventLogService).logEvent(any());
        assertThatCode(() -> service.setUltrasoundByNonPhysician(hospitalId, true, admin)).doesNotThrowAnyException();

        UUID unknown = UUID.randomUUID();
        when(hospitalRepository.findClinicalById(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(unknown)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.setUltrasoundByNonPhysician(unknown, true, admin)).isInstanceOf(ResourceNotFoundException.class);
    }
}
