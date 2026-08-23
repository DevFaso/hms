package com.example.hms.service.impl;

import com.example.hms.enums.IntakeOutputCategory;
import com.example.hms.enums.IntakeOutputRoute;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.IntakeOutputEntry;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.IntakeOutputEntryRequestDTO;
import com.example.hms.payload.dto.IntakeOutputSummaryDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.IntakeOutputEntryRepository;
import com.example.hms.service.support.PatientChartAccess;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fluid intake/output (P3 #18). The first NUMERIC I&O surface — the old
 * INTAKE_OUTPUT task category captured free text and no volumes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class IntakeOutputServiceImplTest {

    @Mock private PatientChartAccess patientChartAccess;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private IntakeOutputEntryRepository entryRepository;

    private IntakeOutputServiceImpl service;

    private UUID patientId;
    private UUID hospitalId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new IntakeOutputServiceImpl(
            patientChartAccess, hospitalRepository, staffRepository, userRepository, entryRepository);

        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);

        patientId = UUID.randomUUID();
        patient = Patient.builder()
            .firstName("Awa").lastName("Kaboré")
            .dateOfBirth(LocalDate.of(2020, 5, 1))
            .build();
        patient.setId(patientId);
        PatientHospitalRegistration registration = new PatientHospitalRegistration();
        registration.setHospital(hospital);
        registration.setActive(true);
        patient.setHospitalRegistrations(Set.of(registration));

        when(patientChartAccess.require(eq(patientId), any())).thenReturn(patient);
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(staffRepository.findByUserIdAndHospitalId(any(), any())).thenReturn(Optional.empty());
        when(userRepository.findById(any(UUID.class))).thenReturn(Optional.empty());
        when(entryRepository.save(any(IntakeOutputEntry.class))).thenAnswer(i -> i.getArgument(0));
        when(entryRepository.findWindow(any(), any(), any(), any())).thenReturn(List.of());
    }

    private IntakeOutputEntryRequestDTO valid() {
        return IntakeOutputEntryRequestDTO.builder()
            .route(IntakeOutputRoute.ORAL)
            .volumeMl(250)
            .build();
    }

    private IntakeOutputEntry stored(IntakeOutputRoute route, int volumeMl, int hoursAgo) {
        IntakeOutputEntry entry = IntakeOutputEntry.builder()
            .patient(patient)
            .hospital(hospital)
            .observationTime(LocalDateTime.now().minusHours(hoursAgo))
            .documentedAt(LocalDateTime.now().minusHours(hoursAgo))
            .route(route)
            .category(route.getCategory())
            .volumeMl(volumeMl)
            .build();
        entry.setId(UUID.randomUUID());
        return entry;
    }

    @Test
    void recordDerivesTheCategoryFromTheRoute() {
        IntakeOutputEntryRequestDTO request = valid();
        request.setRoute(IntakeOutputRoute.URINE);

        IntakeOutputSummaryDTO.Entry created = service.recordEntry(patientId, hospitalId, null, request);

        ArgumentCaptor<IntakeOutputEntry> captor = ArgumentCaptor.forClass(IntakeOutputEntry.class);
        verify(entryRepository).save(captor.capture());
        assertThat(captor.getValue().getCategory()).isEqualTo(IntakeOutputCategory.OUTPUT);
        assertThat(captor.getValue().getHospital()).isEqualTo(hospital);
        assertThat(created.getCategory()).isEqualTo(IntakeOutputCategory.OUTPUT);
        assertThat(created.getVolumeMl()).isEqualTo(250);
    }

    @Test
    void recordDefaultsTheObservationTimeToNow() {
        service.recordEntry(patientId, hospitalId, null, valid());

        ArgumentCaptor<IntakeOutputEntry> captor = ArgumentCaptor.forClass(IntakeOutputEntry.class);
        verify(entryRepository).save(captor.capture());
        assertThat(captor.getValue().getObservationTime())
            .isAfter(LocalDateTime.now().minusMinutes(1));
    }

    @Test
    void aFutureObservationTimeIsRefused() {
        IntakeOutputEntryRequestDTO request = valid();
        request.setObservationTime(LocalDateTime.now().plusHours(2));

        assertThatThrownBy(() -> service.recordEntry(patientId, hospitalId, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("future");
        verify(entryRepository, never()).save(any());
    }

    @Test
    void aMissingOrNonPositiveVolumeIsRefusedEvenWithoutBeanValidation() {
        // The nurse-station path has historically shipped DTOs with no
        // validation annotations; the service must not rely on @Valid.
        IntakeOutputEntryRequestDTO request = valid();
        request.setVolumeMl(0);

        assertThatThrownBy(() -> service.recordEntry(patientId, hospitalId, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("volume");
    }

    @Test
    void recordingWithoutAHospitalScopeIsRefused() {
        IntakeOutputEntryRequestDTO request = valid();

        assertThatThrownBy(() -> service.recordEntry(patientId, null, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active hospital");
    }

    @Test
    void recordingAgainstAPatientNotRegisteredHereIsRefused() {
        patient.setHospitalRegistrations(Set.of());
        IntakeOutputEntryRequestDTO request = valid();

        assertThatThrownBy(() -> service.recordEntry(patientId, hospitalId, null, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("not registered");
        verify(entryRepository, never()).save(any());
    }

    @Test
    void summaryComputesTotalsAndBalanceServerSide() {
        when(entryRepository.findWindow(any(), any(), any(), any())).thenReturn(List.of(
            stored(IntakeOutputRoute.ORAL, 500, 6),
            stored(IntakeOutputRoute.IV, 1000, 4),
            stored(IntakeOutputRoute.URINE, 700, 2),
            stored(IntakeOutputRoute.EMESIS, 100, 1)));

        IntakeOutputSummaryDTO summary = service.getSummary(patientId, null, null, null);

        assertThat(summary.getTotalIntakeMl()).isEqualTo(1500);
        assertThat(summary.getTotalOutputMl()).isEqualTo(800);
        assertThat(summary.getBalanceMl()).isEqualTo(700);
        assertThat(summary.getEntries()).hasSize(4);
    }

    @Test
    void summaryDefaultsToTheLastTwentyFourHours() {
        service.getSummary(patientId, null, null, null);

        ArgumentCaptor<LocalDateTime> fromCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        ArgumentCaptor<LocalDateTime> toCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(entryRepository).findWindow(any(), any(), fromCaptor.capture(), toCaptor.capture());
        assertThat(fromCaptor.getValue()).isEqualTo(toCaptor.getValue().minusHours(24));
    }

    @Test
    void aWindowThatStartsAfterItEndsIsRefused() {
        LocalDateTime from = LocalDateTime.now();
        LocalDateTime to = from.minusHours(4);

        assertThatThrownBy(() -> service.getSummary(patientId, null, from, to))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("start before it ends");
    }

    @Test
    void aPatientForeignToTheCallersHospitalReadsAsNotFound() {
        // The scope decision itself lives in PatientChartAccess (tested there);
        // what matters here is that the summary propagates it rather than
        // falling through to an empty-but-successful fluid balance.
        UUID foreignHospitalId = UUID.randomUUID();
        when(patientChartAccess.require(patientId, foreignHospitalId))
            .thenThrow(new ResourceNotFoundException("patient.notFound", patientId));

        assertThatThrownBy(() -> service.getSummary(patientId, foreignHospitalId, null, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anUnknownPatientIsNotFound() {
        UUID unknownId = UUID.randomUUID();
        when(patientChartAccess.require(eq(unknownId), any()))
            .thenThrow(new ResourceNotFoundException("patient.notFound", unknownId));

        assertThatThrownBy(() -> service.getSummary(unknownId, null, null, null))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
