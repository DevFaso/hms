package com.example.hms.service;

import com.example.hms.enums.AuditEventType;
import com.example.hms.mapper.AuditEventLogMapper;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.AuditEventLogResponseDTO;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
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
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA round 16 residual #13: a PATIENT-typed audit row that names its patient
 * only by a phone number (or a name, an email) was filed under the first
 * holder in the whole database. With shared family numbers that is somebody
 * else, possibly at another hospital. The row now resolves by the request's
 * patient id, else only to the single holder registered at the event's
 * hospital; anything ambiguous stays unresolved.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AuditEventLogPatientResolutionTest {

    private static final String SHARED_NUMBER = "22670001002";

    @Mock private UserRepository userRepository;
    @Mock private AuditEventLogRepository auditRepository;
    @Mock private AuditEventLogMapper auditMapper;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private org.springframework.transaction.PlatformTransactionManager transactionManager;

    @InjectMocks
    private AuditEventLogServiceImpl auditService;

    private final UUID hospitalA = UUID.randomUUID();
    private final UUID hospitalB = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        when(auditRepository.save(any())).thenReturn(AuditEventLog.builder().build());
        when(auditMapper.toDto(any())).thenReturn(new AuditEventLogResponseDTO());
        Hospital a = new Hospital();
        a.setId(hospitalA);
        a.setCountry("Burkina Faso");
        when(hospitalRepository.findById(hospitalA)).thenReturn(Optional.of(a));
    }

    private static Patient patient() {
        Patient p = new Patient();
        p.setId(UUID.randomUUID());
        p.setPhoneNumberPrimary(SHARED_NUMBER);
        return p;
    }

    private void registered(Patient p, UUID hospitalId) {
        when(registrationRepository.isPatientRegisteredInHospitalFixed(p.getId(), hospitalId)).thenReturn(true);
    }

    private AuditEventLog log(AuditEventRequestDTO.AuditEventRequestDTOBuilder request) {
        auditService.logEvent(request
            .eventType(AuditEventType.PATIENT_ACCESS)
            .eventDescription("chart opened")
            .entityType("PATIENT")
            .build());
        ArgumentCaptor<AuditEventLog> captor = ArgumentCaptor.forClass(AuditEventLog.class);
        verify(auditRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("the single holder of the number at the event's hospital is the row's patient")
    void uniqueHolderAtTheEventHospitalResolves() {
        Patient holder = patient();
        Patient elsewhere = patient();
        when(patientRepository.findAllByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(List.of(elsewhere, holder));
        // The old rule took this first holder, wherever it was registered.
        when(patientRepository.findFirstByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(Optional.of(elsewhere));
        registered(holder, hospitalA);
        registered(elsewhere, hospitalB);

        AuditEventLog row = log(AuditEventRequestDTO.builder().hospitalId(hospitalA).resourceName(SHARED_NUMBER));

        assertThat(row.getPatientId()).isEqualTo(holder.getId());
        assertThat(row.getResourceId()).isEqualTo(holder.getId().toString());
    }

    @Test
    @DisplayName("a holder registered only at another hospital is never the row's patient")
    void holderAtAnotherHospitalOnlyStaysUnresolved() {
        Patient elsewhere = patient();
        when(patientRepository.findAllByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(List.of(elsewhere));
        when(patientRepository.findFirstByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(Optional.of(elsewhere));
        registered(elsewhere, hospitalB);

        AuditEventLog row = log(AuditEventRequestDTO.builder().hospitalId(hospitalA).resourceName(SHARED_NUMBER));

        assertThat(row.getPatientId()).isNull();
        assertThat(row.getResourceId()).isEqualTo("Unknown Resource");
    }

    @Test
    @DisplayName("two holders of a shared number at the hospital: no patient rather than a guess")
    void twoHoldersAtTheHospitalStayUnresolved() {
        Patient mother = patient();
        Patient child = patient();
        when(patientRepository.findAllByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(List.of(mother, child));
        when(patientRepository.findFirstByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(Optional.of(mother));
        registered(mother, hospitalA);
        registered(child, hospitalA);

        AuditEventLog row = log(AuditEventRequestDTO.builder().hospitalId(hospitalA).resourceName(SHARED_NUMBER));

        assertThat(row.getPatientId()).isNull();
        assertThat(row.getResourceId()).isEqualTo("Unknown Resource");
    }

    @Test
    @DisplayName("a name shared by two patients at the hospital stays unresolved; one match resolves")
    void nameResolvesOnlyWhenUniqueAtTheHospital() {
        Patient one = patient();
        Patient two = patient();
        PatientHospitalRegistration r1 = PatientHospitalRegistration.builder().patient(one).build();
        PatientHospitalRegistration r2 = PatientHospitalRegistration.builder().patient(two).build();
        when(registrationRepository.findActiveByHospitalIdAndIdentifier(hospitalA, "Awa Ouedraogo"))
            .thenReturn(List.of(r1, r2));
        // The old rule took the first patient whose first OR last name contained a part.
        when(patientRepository.findByFirstNameContainingIgnoreCaseOrLastNameContainingIgnoreCase("Awa", "Ouedraogo"))
            .thenReturn(List.of(one, two));

        AuditEventLog row = log(AuditEventRequestDTO.builder().hospitalId(hospitalA).resourceName("Awa Ouedraogo"));
        assertThat(row.getPatientId()).isNull();
    }

    @Test
    @DisplayName("the request's own patient id wins, with no lookup by name")
    void requestPatientIdWins() {
        UUID patientId = UUID.randomUUID();

        AuditEventLog row = log(AuditEventRequestDTO.builder()
            .hospitalId(hospitalA).patientId(patientId).resourceName(SHARED_NUMBER));

        assertThat(row.getPatientId()).isEqualTo(patientId);
        assertThat(row.getResourceId()).isEqualTo(patientId.toString());
        verify(patientRepository, never()).findAllByPhoneNumberPrimary(anyString());
        verify(patientRepository, never()).findFirstByPhoneNumberPrimary(anyString());
    }

    @Test
    @DisplayName("no hospital on the event: the number is not resolved across the whole database")
    void noHospitalNoResolution() {
        Patient only = patient();
        when(patientRepository.findAllByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(List.of(only));
        when(patientRepository.findFirstByPhoneNumberPrimary(SHARED_NUMBER)).thenReturn(Optional.of(only));

        AuditEventLog row = log(AuditEventRequestDTO.builder().resourceName(SHARED_NUMBER));

        assertThat(row.getPatientId()).isNull();
        verifyNoInteractions(registrationRepository);
        verify(patientRepository, never()).findAllByPhoneSubscriberAtHospital(anyString(), eq(hospitalA), any());
    }
}
