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
import static org.mockito.ArgumentMatchers.isNull;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.example.hms.i18n.TestMessageSources;

@org.junit.jupiter.api.extension.ExtendWith(com.example.hms.i18n.EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
class LaborServiceImplTest {

    @Mock
    private com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;

    @Mock private LaborEpisodeRepository episodeRepository;
    @Mock private LaborPartographEntryRepository entryRepository;
    @Mock private DeliveryRecordRepository deliveryRecordRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private MaternalHistoryRepository maternalHistoryRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private UserRepository userRepository;
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS) private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock private com.example.hms.service.maternity.ObstetricAlertEscalationService obstetricAlertEscalation;
    @Mock private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock private com.example.hms.repository.PostpartumCarePlanRepository postpartumCarePlanRepository;
    @Mock private com.example.hms.service.DeliveryChargeService deliveryChargeService;

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
            patientRepository, patientRegistrationGuard, hospitalRepository, registrationRepository,
            maternalHistoryRepository, staffRepository, userRepository,
            notificationService, new LaborMapper(), recordAccessPolicy, reachRecorder,
            TestMessageSources.bundles(), postpartumCarePlanRepository, deliveryChargeService,
            obstetricAlertEscalation);

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
        // QA D796: a booked pregnancy (no dating) is on record unless a test
        // says otherwise; an unbooked labour needs an estimated gestational age.
        lenient().when(maternalHistoryRepository.findCurrentByPatientId(any()))
            .thenReturn(Optional.of(new MaternalHistory()));
        lenient().when(maternalHistoryRepository.save(any(MaternalHistory.class)))
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
    void startEpisodeRefusesAPatientRecordedAsMale() {
        // QA D46: a labour episode was accepted for a MALE patient.
        patient.setGender("MALE");
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class);
        verify(episodeRepository, never())
            .save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void startEpisodeRefusesAnEpisodeWithNoOnsetTime_D295() {
        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("maternity.labor.onset.required");
        verify(episodeRepository, never()).save(org.mockito.ArgumentMatchers.any());

        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("laborOnsetAt");
        }
    }

    @Test
    void startEpisodeRefusesAFutureOnsetTime_D295() {
        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId)
            .laborOnsetAt(java.time.LocalDateTime.now().plusHours(3)).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("maternity.labor.onset.future");
    }

    @Test
    void startEpisodeRejectsSecondActiveEpisode() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, LaborStatus.ACTIVE))
            .thenReturn(true);

        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active labor episode");
    }

    @Test
    void startEpisodeSnapshotsGravidaParaAndEddDerivedGestationalAge() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, LaborStatus.ACTIVE))
            .thenReturn(false);

        MaternalHistory history = new MaternalHistory();
        history.setGravida(3);
        history.setPara(2);
        history.setEstimatedDueDate(LocalDate.now().plusWeeks(2)); // → 38 weeks
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(history));

        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build();

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId, request);

        assertThat(created.getGravida()).isEqualTo(3);
        assertThat(created.getPara()).isEqualTo(2);
        assertThat(created.getGestationalAgeWeeks()).isEqualTo(38);
        assertThat(created.getStatus()).isEqualTo("ACTIVE");
    }

    /** QA D629: the screen names nobody, so the recorder is the admitting clinician. */
    @Test
    void startEpisodeWithNoAdmittingStaffNamesTheRecorder() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, LaborStatus.ACTIVE))
            .thenReturn(false);
        when(staffRepository.findByUserIdAndHospitalId(recorderUserId, hospitalId))
            .thenReturn(Optional.of(recorderStaff()));
        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId)
            .laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build();

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId, request);

        assertThat(created.getAdmittedByStaffName()).isEqualTo("Nina Kabore");
    }

    /** QA D629: the delivery names the recorder when no delivering clinician is sent. */
    @Test
    void recordDeliveryWithNoDeliveringStaffNamesTheRecorder() {
        when(staffRepository.findByUserIdAndHospitalId(recorderUserId, hospitalId))
            .thenReturn(Optional.of(recorderStaff()));

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, deliveryRequest());

        assertThat(response.getDeliveredByStaffName()).isEqualTo("Nina Kabore");
    }

    private com.example.hms.model.Staff recorderStaff() {
        User named = new User();
        named.setId(recorderUserId);
        named.setUsername("midwife.nina");
        named.setFirstName("Nina");
        named.setLastName("Kabore");
        com.example.hms.model.Staff staff = new com.example.hms.model.Staff();
        staff.setId(UUID.randomUUID());
        staff.setUser(named);
        staff.setHospital(hospital);
        return staff;
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
    void qaD714AnEntryFiveHoursAheadIsRefusedBeforeAnyAlertOrSave() {
        // Round 10: observationTime = now + 5 h answered 201, raised a CAUTION
        // alert and was drawn on the chart.
        episode.setActivePhaseStartAt(LocalDateTime.now().minusHours(4));
        PartographEntryRequestDTO request = entryRequest();
        request.setObservationTime(LocalDateTime.now().plusHours(5));
        request.setCervicalDilationCm(6);

        assertThatThrownBy(() -> service.addEntry(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "maternity.partograph.futureTime");
        verify(entryRepository, never()).save(any());
        verify(episodeRepository, never()).save(any());
    }

    @Test
    void qaD745AnEntryBeforeLabourOnsetIsRefused() {
        // Round 10 re-test: an entry dated 6 h ago on an episode whose onset
        // was 3 h ago answered 201.
        episode.setLaborOnsetAt(LocalDateTime.now().minusHours(3));
        PartographEntryRequestDTO request = entryRequest();
        request.setObservationTime(LocalDateTime.now().minusHours(6));

        assertThatThrownBy(() -> service.addEntry(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasFieldOrPropertyWithValue("messageKey", "maternity.partograph.beforeOnset");
        verify(entryRepository, never()).save(any());
    }

    @Test
    void qaD714ASmallClockSkewIsStillAccepted() {
        PartographEntryRequestDTO request = entryRequest();
        request.setObservationTime(LocalDateTime.now().plusMinutes(LaborServiceImpl.PARTOGRAPH_FUTURE_SKEW_MINUTES - 2));

        assertThat(service.addEntry(patientId, episodeId, recorderUserId, request)).isNotNull();
    }

    @Test
    void qaD714ALateEntryInThePastIsStillAccepted() {
        PartographEntryRequestDTO request = entryRequest();
        request.setObservationTime(LocalDateTime.now().minusHours(3));
        request.setLateEntry(true);

        assertThat(service.addEntry(patientId, episodeId, recorderUserId, request)).isNotNull();
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
        verify(notificationService, never()).created(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
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
        verify(notificationService).created(contains("Alerte de travail"), eq("midwife.nina"), org.mockito.ArgumentMatchers.any());
        // QA D605: the on-call team is told too, not only the documenter.
        verify(obstetricAlertEscalation, org.mockito.Mockito.atLeastOnce()).raiseAfterCommit(org.mockito.ArgumentMatchers.argThat(a ->
            hospitalId.equals(a.hospitalId()) && patientId.equals(a.patientId())
                && com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_PARTOGRAPH.equals(a.sourceType())
                && recorderUserId.equals(a.documentedByUserId())
                && a.notificationText().contains("Alerte de travail")));
    }

    @Test
    void fetalBradycardiaRaisesUrgentAlert() {
        PartographEntryRequestDTO request = entryRequest();
        request.setFetalHeartRateBpm(95);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-fetal-bradycardia"));
        // QA D172: a translated type label and French text, never the raw enum or English.
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(text.capture(), eq("midwife.nina"), org.mockito.ArgumentMatchers.any());
        assertThat(text.getValue())
            .contains("rythme cardiaque fœtal", "95 bpm", "souffrance fœtale")
            .doesNotContain("FETAL_HEART_RATE", "Fetal heart rate", "fetal distress");
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
        when(notificationService.created(anyString(), anyString(), org.mockito.ArgumentMatchers.any()))
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

    /** QA D516: recording a birth posts its charge, filed by the recorder. */
    @Test
    void recordDeliveryPostsTheDeliveryCharge() {
        service.recordDelivery(patientId, episodeId, recorderUserId, deliveryRequest());

        verify(deliveryChargeService).chargeDelivery(
            org.mockito.ArgumentMatchers.argThat(d -> d.getPatient() == patient), org.mockito.ArgumentMatchers.eq(recorderUserId));
    }

    /** QA D516: a charge that cannot be posted never undoes the clinical record. */
    @Test
    void recordDeliveryKeepsTheBirthWhenTheChargeFails() {
        when(deliveryChargeService.chargeDelivery(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new IllegalStateException("boom"));

        service.recordDelivery(patientId, episodeId, recorderUserId, deliveryRequest());

        assertThat(episode.getStatus()).isEqualTo(LaborStatus.DELIVERED);
    }

    /**
     * QA D516 (self-review of #921): inside a transaction the charge waits
     * for the delivery to commit and then runs in its own transaction, so a
     * charge whose invoice save throws leaves the committed birth intact.
     */
    @Test
    void recordDeliveryPostsTheChargeOnlyAfterCommitAndSurvivesItsFailure() {
        when(deliveryChargeService.chargeDelivery(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new org.springframework.dao.DataIntegrityViolationException("invoice save failed"));
        org.springframework.transaction.support.TransactionSynchronizationManager.initSynchronization();
        try {
            service.recordDelivery(patientId, episodeId, recorderUserId, deliveryRequest());

            org.mockito.Mockito.verifyNoInteractions(deliveryChargeService);
            verify(deliveryRecordRepository).save(org.mockito.ArgumentMatchers.any());
            org.assertj.core.api.Assertions.assertThatCode(() ->
                org.springframework.transaction.support.TransactionSynchronizationManager.getSynchronizations()
                    .forEach(org.springframework.transaction.support.TransactionSynchronization::afterCommit))
                .doesNotThrowAnyException();
            verify(deliveryChargeService).chargeDelivery(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(recorderUserId));
            assertThat(episode.getStatus()).isEqualTo(LaborStatus.DELIVERED);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** QA D516: no second charge when the episode already has a delivery. */
    @Test
    void recordDeliverySecondAttemptPostsNoCharge() {
        when(deliveryRecordRepository.existsByEpisode_Id(episodeId)).thenReturn(true);

        DeliveryRecordRequestDTO request = deliveryRequest();
        assertThatThrownBy(() -> service.recordDelivery(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
        org.mockito.Mockito.verifyNoInteractions(deliveryChargeService);
    }

    @Test
    void recordDeliveryRejectsSecondRecord() {
        when(deliveryRecordRepository.existsByEpisode_Id(episodeId)).thenReturn(true);
        DeliveryRecordRequestDTO request = deliveryRequest();

        assertThatThrownBy(() -> service.recordDelivery(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already exists");
    }

    /* ── QA D214 (#896 review): two episodes at two hospitals ─────────── */

    @Test
    void recordDeliveryRefusesASecondBirthHoursAfterOneFiledOnAnotherEpisode() {
        // The mother had an episode opened here before giving birth at another
        // hospital; that birth is on a different episode, so startEpisode's
        // guard never ran for this one.
        when(deliveryRecordRepository.existsByPatient_IdAndEpisode_IdNotAndBirthDateTimeBetween(
            org.mockito.ArgumentMatchers.eq(patientId), org.mockito.ArgumentMatchers.eq(episodeId),
            any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(true);
        DeliveryRecordRequestDTO request = deliveryRequest();

        assertThatThrownBy(() -> service.recordDelivery(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("labor.delivery.recentDelivery");
        verify(deliveryRecordRepository, never()).save(any());
        assertThat(episode.getStatus()).isEqualTo(LaborStatus.ACTIVE);
    }

    @Test
    void recordDeliveryAcceptsAnOverrideAndKeepsTheReasonOnTheRecord() {
        when(deliveryRecordRepository.existsByPatient_IdAndEpisode_IdNotAndBirthDateTimeBetween(
            org.mockito.ArgumentMatchers.eq(patientId), org.mockito.ArgumentMatchers.eq(episodeId),
            any(LocalDateTime.class), any(LocalDateTime.class))).thenReturn(true);
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setRecentDeliveryOverrideReason("Second twin, delayed interval delivery");
        request.setNotes("Stable");

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getNotes())
            .contains("Dérogation (accouchement récent) : Second twin, delayed interval delivery")
            .contains("Stable");
    }

    @Test
    void recordDeliveryLooksTwentyWeeksEitherSideOfTheBirth() {
        LocalDateTime birth = LocalDateTime.of(2026, 10, 1, 8, 0);
        org.mockito.ArgumentCaptor<LocalDateTime> from = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        org.mockito.ArgumentCaptor<LocalDateTime> to = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        when(deliveryRecordRepository.existsByPatient_IdAndEpisode_IdNotAndBirthDateTimeBetween(
            org.mockito.ArgumentMatchers.eq(patientId), org.mockito.ArgumentMatchers.eq(episodeId),
            from.capture(), to.capture())).thenReturn(false);
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setBirthDateTime(birth);

        service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(from.getValue()).isEqualTo(birth.minusDays(140));
        assertThat(to.getValue()).isEqualTo(birth.plusDays(140));
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
        verify(notificationService).created(contains("hémorragie"), eq("midwife.nina"), org.mockito.ArgumentMatchers.any());
        // QA D605: a PPH reaches the on-call team and escalates.
        verify(obstetricAlertEscalation).raiseAfterCommit(org.mockito.ArgumentMatchers.argThat(a ->
            hospitalId.equals(a.hospitalId()) && com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_DELIVERY.equals(a.sourceType())
                && a.alertMessage().toLowerCase().contains("hémorragie")
                // QA D665: the sentence also travels as its key and its numbers.
                && "labor.alert.msg.labor-pph".equals(a.alertMessageKey())
                && a.alertMessageArgs() != null && a.alertMessageArgs().size() == 1));
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

    @Test
    void getEpisodesFollowThePatientAndAccountTheReach() {
        // E9 #59d — the delivery at Hôpital B is on the list at Hôpital A when
        // the policy reads B for this patient; the disclosure is accounted.
        UUID otherHospitalId = UUID.randomUUID();
        Hospital other = new Hospital();
        other.setId(otherHospitalId);
        LaborEpisode foreign = LaborEpisode.builder()
            .patient(patient).hospital(other).status(LaborStatus.DELIVERED)
            .admittedAt(LocalDateTime.now().minusDays(400)).build();
        foreign.setId(UUID.randomUUID());
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId, otherHospitalId));
        when(episodeRepository.findByPatient_IdAndHospital_IdInOrderByAdmittedAtDesc(
                eq(patientId), eq(Set.of(hospitalId, otherHospitalId)), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of(episode, foreign));

        List<LaborEpisodeResponseDTO> result = service.getEpisodes(patientId, hospitalId, 10);

        assertThat(result).extracting(LaborEpisodeResponseDTO::getHospitalId).containsExactly(hospitalId, otherHospitalId);
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(),
            eq(Map.of(otherHospitalId.toString(), 1L)), anyString());
    }

    @Test
    void recordDeliveryRefusesABirthBeforeLabourOnset() {
        // QA D104: a delivery time before the labour onset was accepted.
        episode.setLaborOnsetAt(LocalDateTime.now().minusHours(2));
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setBirthDateTime(LocalDateTime.now().minusHours(5));

        assertThatThrownBy(() -> service.recordDelivery(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class);
        assertThat(episode.getStatus()).isEqualTo(LaborStatus.ACTIVE);
    }

    /* ── QA D172: every alert text comes from the French staff bundle ── */

    @Test
    void maternalFeverAndTachycardiaAreFrenchCautions() {
        PartographEntryRequestDTO request = entryRequest();
        request.setTemperatureCelsius(38.6);
        request.setPulseBpm(128);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-maternal-fever");
                assertThat(alert.getSeverity()).isEqualTo(LaborAlertSeverity.CAUTION.name());
                assertThat(alert.getMessage()).contains("Température maternelle", "38,6").doesNotContain("temperature");
            })
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-maternal-tachycardia");
                assertThat(alert.getMessage()).contains("Pouls maternel 128 bpm").doesNotContain("pulse");
            });
    }

    @Test
    void elevatedBloodPressureIsAFrenchCaution() {
        PartographEntryRequestDTO request = entryRequest();
        request.setSystolicBpMmHg(145);
        request.setDiastolicBpMmHg(92);

        PartographEntryResponseDTO response = service.addEntry(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-hypertension");
                assertThat(alert.getMessage()).contains("Tension artérielle élevée 145/92 mmHg");
            })
            .noneSatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-severe-hypertension"));
    }

    @Test
    void incompletePlacentaAndLowApgarAreFrenchAlerts() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setPlacentaComplete(false);
        request.setApgarFiveMinute(5);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts())
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-retained-placenta");
                assertThat(alert.getMessage()).contains("Placenta incomplet").doesNotContain("retained");
            })
            .anySatisfy(alert -> {
                assertThat(alert.getCode()).isEqualTo("labor-apgar-low");
                assertThat(alert.getSeverity()).isEqualTo(LaborAlertSeverity.CAUTION.name());
                assertThat(alert.getMessage()).contains("Apgar à 5 minutes 5");
            });
    }

    /* ── QA D162: gestational age from the LMP when no EDD was recorded ── */

    @Test
    void startEpisodeDerivesGestationalAgeFromTheLmpWhenNoEddIsRecorded() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, LaborStatus.ACTIVE))
            .thenReturn(false);
        MaternalHistory history = new MaternalHistory();
        history.setLastMenstrualPeriod(LocalDate.now().minusWeeks(38));
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(history));

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId,
            LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build());

        assertThat(created.getGestationalAgeWeeks()).isEqualTo(38);
    }

    @Test
    void startEpisodeLeavesGestationalAgeEmptyForAnImplausibleLmp() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(episodeRepository.existsByPatient_IdAndHospital_IdAndStatus(patientId, hospitalId, LaborStatus.ACTIVE))
            .thenReturn(false);
        MaternalHistory history = new MaternalHistory();
        history.setLastMenstrualPeriod(LocalDate.now().minusWeeks(93));
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(history));

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId,
            LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build());

        assertThat(created.getGestationalAgeWeeks()).isNull();
    }

    @Test
    void nullPayloadsAreRejectedInPlainText() {
        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("maternity.");
        assertThatThrownBy(() -> service.addEntry(patientId, episodeId, recorderUserId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("maternity.");
        assertThatThrownBy(() -> service.recordDelivery(patientId, episodeId, recorderUserId, null))
            .isInstanceOf(BusinessException.class)
            .hasMessageNotContaining("maternity.");
    }

    @Test
    void recordDeliveryRejectsClosedEpisode() {
        episode.setStatus(LaborStatus.DELIVERED);
        DeliveryRecordRequestDTO request = deliveryRequest();

        assertThatThrownBy(() -> service.recordDelivery(patientId, episodeId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("active labor episode");
    }

    /* ── QA D214: second labour 24 h after a birth ──────────────────────── */

    @Test
    void startEpisodeRefusesANewLabourSoonAfterARecordedBirthWithoutAnOverride() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq(patientId), any(LocalDateTime.class))).thenReturn(true);

        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("labor.episode.recentDelivery");
        verify(episodeRepository, never()).save(any());
    }

    @Test
    void startEpisodeAcceptsAnExplicitOverrideAndKeepsTheReasonOnTheChart() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq(patientId), any(LocalDateTime.class))).thenReturn(true);

        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2))
            .recentDeliveryOverrideReason("Second twin retained").riskNotes("Anaemia").build();

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId, request);

        assertThat(created.getRiskNotes())
            .contains("Dérogation (accouchement récent) : Second twin retained")
            .contains("Anaemia");
    }

    @Test
    void startEpisodeLooksBackTwentyWeeksForAPriorBirth() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        org.mockito.ArgumentCaptor<LocalDateTime> since = org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq(patientId), since.capture())).thenReturn(false);

        service.startEpisode(patientId, recorderUserId,
            LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(java.time.LocalDateTime.now().minusHours(2)).build());

        assertThat(since.getValue()).isBetween(
            LocalDateTime.now().minusDays(141), LocalDateTime.now().minusDays(139));
    }

    @Test
    void aBirthAtTwentyFourWeeksRaisesAnUrgentFrenchPretermAlert() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setGestationalAgeWeeksAtBirth(24);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts()).anySatisfy(alert -> {
            assertThat(alert.getCode()).isEqualTo("labor-preterm");
            assertThat(String.valueOf(alert.getSeverity())).isEqualTo("URGENT");
            assertThat(alert.getMessage()).contains("prématurée à 24 semaines");
        });
    }

    @Test
    void aTermBirthRaisesNoPretermAlert() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setGestationalAgeWeeksAtBirth(39);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts()).noneSatisfy(alert ->
            assertThat(alert.getCode()).isEqualTo("labor-preterm"));
    }

    /* -- QA D796: a labour episode with no pregnancy on record -- */

    private void unbookedPatientAtTheHospital() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.empty());
    }

    /** The exact round-12 call: MIDWIFE_A posts an onset and an admission time for A3, who has no maternal history. */
    @Test
    void anUnbookedLabourWithNoEstimatedGestationalAgeIsRefused_D796() {
        unbookedPatientAtTheHospital();
        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId)
            .laborOnsetAt(LocalDateTime.now().minusHours(1)).admittedAt(LocalDateTime.now().minusMinutes(30)).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("maternity.labor.pregnancyRequired");
        verify(episodeRepository, never()).save(any());
        verify(maternalHistoryRepository, never()).save(any());
    }

    @Test
    void anUnbookedLabourOpensItsPregnancyFromTheEstimateAndLinksIt_D796() {
        unbookedPatientAtTheHospital();
        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId)
            .laborOnsetAt(LocalDateTime.now().minusHours(1)).gestationalAgeWeeks(38).gravida(2).para(1).build();

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId, request);

        org.mockito.ArgumentCaptor<MaternalHistory> pregnancy = org.mockito.ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(pregnancy.capture());
        MaternalHistory opened = pregnancy.getValue();
        assertThat(opened.getPatient()).isSameAs(patient);
        assertThat(opened.getHospital()).isSameAs(hospital);
        assertThat(opened.getVersionNumber()).isEqualTo(1);
        assertThat(opened.getEstimatedDueDate()).isEqualTo(LocalDate.now().plusWeeks(2));
        assertThat(opened.getGravida()).isEqualTo(2);
        assertThat(opened.getPara()).isEqualTo(1);
        assertThat(opened.getUpdateReason()).contains("38");
        org.mockito.ArgumentCaptor<LaborEpisode> saved = org.mockito.ArgumentCaptor.forClass(LaborEpisode.class);
        verify(episodeRepository).save(saved.capture());
        assertThat(saved.getValue().getMaternalHistory()).isSameAs(opened);
        assertThat(created.getGestationalAgeWeeks()).isEqualTo(38);
    }

    @Test
    void anUnbookedLabourEstimateOutsideTwentyToFortyFiveWeeksIsRefused_D796() {
        unbookedPatientAtTheHospital();
        LaborEpisodeRequestDTO request = LaborEpisodeRequestDTO.builder().hospitalId(hospitalId)
            .laborOnsetAt(LocalDateTime.now().minusHours(1)).gestationalAgeWeeks(14).build();

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, request))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("maternity.labor.gestationalAgeRange");
        verify(maternalHistoryRepository, never()).save(any());
    }

    @Test
    void aBookedLabourOpensNoSecondPregnancy_D796() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        MaternalHistory booked = new MaternalHistory();
        booked.setEstimatedDueDate(LocalDate.now().plusWeeks(1));
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(booked));

        LaborEpisodeResponseDTO created = service.startEpisode(patientId, recorderUserId,
            LaborEpisodeRequestDTO.builder().hospitalId(hospitalId).laborOnsetAt(LocalDateTime.now().minusHours(1)).build());

        verify(maternalHistoryRepository, never()).save(any());
        assertThat(created.getGestationalAgeWeeks()).isEqualTo(39);
    }

    @Test
    void aPregnancyThatHasDeliveredIsNotTheCurrentOne_D796() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        MaternalHistory previous = new MaternalHistory();
        previous.setRecordedDate(LocalDateTime.now().minusYears(2));
        previous.setEstimatedDueDate(LocalDate.now().minusYears(2).plusMonths(5));
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(previous));
        when(maternalHistoryRepository.findMaxVersionByPatientId(patientId)).thenReturn(3);
        // The birth of that pregnancy: after its record, long before the D214 window.
        when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq(patientId), any(LocalDateTime.class)))
            .thenAnswer(inv -> inv.<LocalDateTime>getArgument(1).isBefore(LocalDateTime.now().minusYears(1)));

        assertThatThrownBy(() -> service.startEpisode(patientId, recorderUserId, LaborEpisodeRequestDTO.builder()
                .hospitalId(hospitalId).laborOnsetAt(LocalDateTime.now().minusHours(1)).build()))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey()).isEqualTo("maternity.labor.pregnancyRequired");

        service.startEpisode(patientId, recorderUserId, LaborEpisodeRequestDTO.builder()
            .hospitalId(hospitalId).laborOnsetAt(LocalDateTime.now().minusHours(1)).gestationalAgeWeeks(36).build());

        org.mockito.ArgumentCaptor<MaternalHistory> opened = org.mockito.ArgumentCaptor.forClass(MaternalHistory.class);
        verify(maternalHistoryRepository).save(opened.capture());
        assertThat(opened.getValue()).isNotSameAs(previous);
        assertThat(opened.getValue().getVersionNumber()).isEqualTo(4);
        assertThat(opened.getValue().getEstimatedDueDate()).isEqualTo(LocalDate.now().plusWeeks(4));
    }

    @Test
    void aLabourThatContinuesARecentBirthKeepsItsPregnancy_D796() {
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        MaternalHistory twins = new MaternalHistory();
        twins.setRecordedDate(LocalDateTime.now().minusMonths(7));
        when(maternalHistoryRepository.findCurrentByPatientId(patientId)).thenReturn(Optional.of(twins));
        when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq(patientId), any(LocalDateTime.class))).thenReturn(true);

        service.startEpisode(patientId, recorderUserId, LaborEpisodeRequestDTO.builder()
            .hospitalId(hospitalId).laborOnsetAt(LocalDateTime.now().minusHours(1))
            .recentDeliveryOverrideReason("Second twin retained").build());

        verify(maternalHistoryRepository, never()).save(any());
        org.mockito.ArgumentCaptor<LaborEpisode> saved = org.mockito.ArgumentCaptor.forClass(LaborEpisode.class);
        verify(episodeRepository).save(saved.capture());
        assertThat(saved.getValue().getMaternalHistory()).isSameAs(twins);
    }

    /* -- QA D797: implausible birth weight for gestational age -- */

    /** The exact round-12 call: the episode is dated 24 weeks, the delivery sends 3,200 g and no age. */
    @Test
    void threeThousandTwoHundredGramsAtTwentyFourWeeksIsFlaggedButRecorded_D797() {
        episode.setGestationalAgeWeeks(24);
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setBirthWeightGrams(3200);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts()).anySatisfy(alert -> {
            assertThat(alert.getCode()).isEqualTo("labor-weight-implausible");
            assertThat(String.valueOf(alert.getSeverity())).isEqualTo("CAUTION");
            assertThat(alert.getMessage()).contains("24 semaines").contains("peu plausible");
        });
        assertThat(episode.getStatus()).isEqualTo(LaborStatus.DELIVERED);
        verify(deliveryRecordRepository).save(any(DeliveryRecord.class));
    }

    @Test
    void aTypicalWeightForTheAgeRaisesNoPlausibilityAlert_D797() {
        DeliveryRecordRequestDTO request = deliveryRequest();
        request.setGestationalAgeWeeksAtBirth(39);
        request.setBirthWeightGrams(3200);

        var response = service.recordDelivery(patientId, episodeId, recorderUserId, request);

        assertThat(response.getAlerts()).noneSatisfy(alert ->
            assertThat(alert.getCode()).isEqualTo("labor-weight-implausible"));
    }

    @Test
    void theBandIsWideAndTwoSided_D797() {
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(3200, 24)).isTrue();
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(650, 24)).isFalse();
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(1000, 24)).isFalse();
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(4500, 40)).isFalse();
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(2200, 37)).isFalse();
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(600, 40)).isTrue();
        // Outside the table: clamped to its first and last weeks.
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(3000, 20)).isTrue();
        assertThat(LaborServiceImpl.isImplausibleBirthWeight(3600, 44)).isFalse();
    }

    @Test
    void noAgeMeansNoPlausibilityAlert_D797() {
        DeliveryRecordRequestDTO noAge = deliveryRequest();
        noAge.setBirthWeightGrams(3200);
        assertThat(service.recordDelivery(patientId, episodeId, recorderUserId, noAge).getAlerts())
            .noneSatisfy(alert -> assertThat(alert.getCode()).isEqualTo("labor-weight-implausible"));
    }

    /* ── QA D215: a new delivery closes the earlier postpartum plan ─────── */

    @Test
    void recordDeliveryClosesThePostpartumPlanOfAnEarlierBirth() {
        com.example.hms.model.postpartum.PostpartumCarePlan old = new com.example.hms.model.postpartum.PostpartumCarePlan();
        old.setDeliveryOccurredAt(LocalDateTime.now().minusDays(1));
        old.setActive(true);
        when(postpartumCarePlanRepository.findByPatient_IdAndHospital_IdAndActiveTrue(patientId, hospitalId))
            .thenReturn(java.util.List.of(old));

        service.recordDelivery(patientId, episodeId, recorderUserId, deliveryRequest());

        assertThat(old.isActive()).isFalse();
        verify(postpartumCarePlanRepository).save(old);
    }
}
