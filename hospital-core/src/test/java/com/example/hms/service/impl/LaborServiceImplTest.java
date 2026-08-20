package com.example.hms.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborOutcome;
import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.LiquorColour;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.LaborMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeResponseDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryRequestDTO;
import com.example.hms.payload.dto.clinical.labor.PartographEntryResponseDTO;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.LaborPartographEntryRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.NotificationService;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LaborServiceImplTest {

    @Mock private LaborEpisodeRepository episodeRepository;
    @Mock private LaborPartographEntryRepository entryRepository;
    @Mock private DeliveryRecordRepository deliveryRecordRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private MaternalHistoryRepository maternalHistoryRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock private NotificationService notificationService;

    private LaborServiceImpl service;

    private UUID patientId;
    private UUID hospitalId;
    private UUID episodeId;
    private UUID recorderUserId;
    private Patient patient;
    private Hospital hospital;
    private LaborEpisode episode;
    private User recorder;

    @BeforeEach
    void setUp() {
        service = new LaborServiceImpl(
            episodeRepository, entryRepository, deliveryRecordRepository,
            patientRepository, hospitalRepository, registrationRepository,
            maternalHistoryRepository, staffRepository, userRepository,
            notificationService, new LaborMapper());

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        episodeId = UUID.randomUUID();
        recorderUserId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");

        hospital = new Hospital();
        hospital.setId(hospitalId);

        recorder = new User();
        recorder.setId(recorderUserId);
        recorder.setUsername("midwife.nina");

        episode = LaborEpisode.builder()
            .patient(patient)
            .hospital(hospital)
            .status(LaborStatus.ACTIVE)
            .admittedAt(LocalDateTime.now().minusHours(6))
            .build();
        episode.setId(episodeId);

        lenient().when(userRepository.findById(recorderUserId)).thenReturn(Optional.of(recorder));
        lenient().when(episodeRepository.findByIdAndHospital_Id(episodeId, hospitalId))
            .thenReturn(Optional.of(episode));
        lenient().when(entryRepository.save(any(LaborPartographEntry.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(episodeRepository.save(any(LaborEpisode.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(deliveryRecordRepository.save(any(DeliveryRecord.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    private PartographEntryRequestDTO entryRequest() {
        PartographEntryRequestDTO request = new PartographEntryRequestDTO();
        request.setHospitalId(hospitalId);
        request.setObservationTime(LocalDateTime.now());
        return request;
    }

    /* ── Episodes ─────────────────────────────────────────────────────── */

    @Test
    void startEpisodeRejectsSecondActiveEpisode() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, LaborStatus.ACTIVE))
            .thenReturn(true);

        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active labor episode");
    }

    @Test
    void startEpisodeSnapshotsGravidaParaAndEddDerivedGestationalAge() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, LaborStatus.ACTIVE))
            .thenReturn(false);

        MaternalHistory history = new MaternalHistory();
        history.setGravida(3);
        history.setPara(2);
        history.setEstimatedDueDate(LocalDate.now().plusWeeks(2)); // → 38 weeks
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(history));

        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).build();

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId, request);

        assertThat(created.getGravida()).isEqualTo(3);
        assertThat(created.getPara()).isEqualTo(2);
        assertThat(created.getGestationalAgeWeeks()).isEqualTo(38);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void getEpisodesRequiresHospitalScope() {
        assertThatThrownBy(() -> service.getEpisodes(patientId, null, 10))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Hospital context");
    }

    /* ── Partograph entries ───────────────────────────────────────────── */

    @Test
    void addEntryRejectsClosedEpisode() {
        episode.setStatus(LaborStatus.DELIVERED);
        PartographEntryRequestDTO request = entryRequest();

        assertThatThrownBy(() -> service.addEntry(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active labor episode");
    }

    @Test
    void addEntryAnchorsActivePhaseAtFourCentimetres() {
        PartographEntryRequestDTO request = entryRequest();
        request.setCervicalDilationCm(4);

        service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(episode.getActivePhaseStartAt()).isEqualTo(request.getObservationTime());
        verify(episodeRepository).save(episode);
    }

    @Test
    void addEntryDoesNotAnchorInLatentPhase() {
        PartographEntryRequestDTO request = entryRequest();
        request.setCervicalDilationCm(3);

        service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(episode.getActivePhaseStartAt()).isNull();
    }

    @Test
    void slowProgressBehindAlertLineRaisesCaution() {
        // Anchored 4 h ago at 4 cm → expected 8 cm; observed 6 cm = 2 h behind
        episode.setActivePhaseStartAt(LocalDateTime.now().minusHours(4));
        PartographEntryRequestDTO request = entryRequest();
        request.setCervicalDilationCm(6);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-alert-line");
                assertThat(alert.getSeverity()).isEqualTo("CAUTION");
            });
        verify(notificationService, never()).createNotification(anyString(), anyString());
    }

    @Test
    void crossingActionLineRaisesUrgentAndNotifies() {
        // Anchored 6 h ago at 4 cm → expected 10 cm; observed 5 cm = 5 h behind (≥ 4 h)
        episode.setActivePhaseStartAt(LocalDateTime.now().minusHours(6));
        PartographEntryRequestDTO request = entryRequest();
        request.setCervicalDilationCm(5);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-action-line");
                assertThat(alert.getSeverity()).isEqualTo("URGENT");
            });
        verify(notificationService).createNotification(contains("labor alert"), eq("midwife.nina"));
    }

    @Test
    void fetalBradycardiaRaisesUrgentAlert() {
        PartographEntryRequestDTO request = entryRequest();
        request.setFetalHeartRateBpm(95);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-fetal-bradycardia"));
        verify(notificationService).createNotification(contains("FETAL_HEART_RATE"), eq("midwife.nina"));
    }

    @Test
    void meconiumLiquorRaisesCaution() {
        PartographEntryRequestDTO request = entryRequest();
        request.setLiquorColour(LiquorColour.MECONIUM_STAINED);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-liquor");
                assertThat(alert.getSeverity()).isEqualTo("CAUTION");
            });
    }

    @Test
    void severeHypertensionRaisesUrgentAlert() {
        PartographEntryRequestDTO request = entryRequest();
        request.setSystolicBpMmHg(165);
        request.setDiastolicBpMmHg(112);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-severe-hypertension"));
    }

    @Test
    void normalEntryRaisesNoAlerts() {
        episode.setActivePhaseStartAt(LocalDateTime.now().minusHours(2));
        PartographEntryRequestDTO request = entryRequest();
        request.setCervicalDilationCm(7); // ahead of the alert line
        request.setFetalHeartRateBpm(140);
        request.setLiquorColour(LiquorColour.CLEAR);
        request.setSystolicBpMmHg(118);
        request.setDiastolicBpMmHg(76);
        request.setTemperatureCelsius(37.0);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts()).isEmpty();
        assertThat(response.getHoursSinceActivePhaseStart()).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.1));
    }

    @Test
    void notificationFailureDoesNotFailTheEntrySave() {
        PartographEntryRequestDTO request = entryRequest();
        request.setFetalHeartRateBpm(90);
        when(notificationService.createNotification(anyString(), anyString()))
            .thenThrow(new IllegalStateException("broker down"));

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response).isNotNull(); // save survived
    }

    /* ── Delivery ─────────────────────────────────────────────────────── */

    private DeliveryRecordRequestDTO deliveryRequest() {
        DeliveryRecordRequestDTO request = new DeliveryRecordRequestDTO();
        request.setHospitalId(hospitalId);
        request.setDeliveryMode(DeliveryMode.SPONTANEOUS_VAGINAL);
        return request;
    }

    @Test
    void recordDeliveryClosesEpisodeWithDerivedOutcome() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setDeliveryMode(DeliveryMode.CAESAREAN_EMERGENCY);

        service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(episode.getStatus()).isEqualTo(LaborStatus.DELIVERED);
        assertThat(episode.getOutcome()).isEqualTo(LaborOutcome.CAESAREAN_SECTION);
    }

    @Test
    void recordDeliveryRejectsSecondRecord() {
        when(deliveryRecordRepository.existsByEpisode_Id(episodeId)).thenReturn(true);
        DeliveryRecordRequestDTO request = deliveryRequest();

        assertThatThrownBy(() -> service.recordDelivery(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
    }

    @Test
    void bloodLossAtThresholdRaisesPphAlertAndNotifies() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setEstimatedBloodLossMl(650);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-pph");
                assertThat(alert.getSeverity()).isEqualTo(LaborAlertSeverity.URGENT.name());
            });
        verify(notificationService).createNotification(contains("HEMORRHAGE"), eq("midwife.nina"));
    }

    @Test
    void stillbirthSetsOutcomeAndUrgentAlert() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setLiveBirth(false);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(episode.getOutcome()).isEqualTo(LaborOutcome.STILLBIRTH);
        assertThat(response.getAlerts())
            .anySatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-stillbirth"));
    }

    @Test
    void criticalApgarRaisesUrgentAlert() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setApgarFiveMinute(3);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-apgar-critical"));
    }

    /* ── Tenant isolation ─────────────────────────────────────────────── */

    @Test
    void crossTenantEpisodeReadsAsNotFound() {
        UUID otherHospital = UUID.randomUUID();
        when(episodeRepository.findByIdAndHospital_Id(episodeId, otherHospital)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEntries(patientId, episodeId, otherHospital))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void episodeOfDifferentPatientReadsAsNotFound() {
        UUID otherPatient = UUID.randomUUID();

        assertThatThrownBy(() -> service.getEntries(otherPatient, episodeId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deliveryLookupThrowsWhenNoneRecorded() {
        when(deliveryRecordRepository.findByEpisode_Id(episodeId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDelivery(patientId, episodeId, hospitalId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    /* ── Alert-order sanity: captured entity carries the alerts ───────── */

    @Test
    void alertsArePersistedOnTheEntry() {
        PartographEntryRequestDTO request = entryRequest();
        request.setFetalHeartRateBpm(170);

        service.addEntry(patientId, episodeId, recorderUserId, request);

        ArgumentCaptor<LaborPartographEntry> captor = ArgumentCaptor.forClass(LaborPartographEntry.class);
        verify(entryRepository).save(captor.capture());
        assertThat(captor.getValue().getAlerts())
            .anySatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-fetal-tachycardia"));
    }
}
