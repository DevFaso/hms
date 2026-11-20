package com.example.hms.service.impl;

import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.enums.PostpartumAlertType;
import com.example.hms.enums.PostpartumEducationTopic;
import com.example.hms.enums.PostpartumFundusTone;
import com.example.hms.enums.PostpartumLochiaAmount;
import com.example.hms.enums.PostpartumMoodStatus;
import com.example.hms.enums.PostpartumSchedulePhase;
import com.example.hms.enums.PostpartumSupportStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.mapper.PostpartumObservationMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PostpartumCarePlanRepository;
import com.example.hms.repository.PostpartumObservationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.List;
import java.util.Map;
import com.example.hms.i18n.TestMessageSources;

@ExtendWith(MockitoExtension.class)
class PostpartumCareServiceImplTest {

    @Mock
    private com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;

    @Mock
    private PostpartumObservationRepository observationRepository;
    @Mock
    private PostpartumCarePlanRepository carePlanRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private StaffRepository staffRepository;
    @Mock
    private UserRepository userRepository;
    @Mock(answer = org.mockito.Answers.CALLS_REAL_METHODS)
    private com.example.hms.service.RenderingNotificationService notificationService;
    @Mock
    private com.example.hms.service.maternity.ObstetricAlertEscalationService obstetricAlertEscalation;
    @Mock
    private com.example.hms.service.recordaccess.RecordAccessPolicy recordAccessPolicy;
    @Mock
    private com.example.hms.service.recordaccess.CrossHospitalReachRecorder reachRecorder;
    @Mock
    private com.example.hms.service.pro.ProResponseService proResponseService;
    @Mock
    private com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;

    private PostpartumCareServiceImpl service;
    private UUID patientId;
    private UUID hospitalId;
    private UUID recorderId;
    private Patient patient;
    private Hospital hospital;

    @Mock
    private com.example.hms.service.vitals.MaternityVitalCharting maternityVitalCharting;

    @BeforeEach
    void setUp() {
        service = new PostpartumCareServiceImpl(
            observationRepository,
            carePlanRepository,
            patientRepository, patientRegistrationGuard,
            registrationRepository,
            hospitalRepository,
            staffRepository,
            userRepository,
            notificationService,
            obstetricAlertEscalation,
            new PostpartumObservationMapper(),
            proResponseService,
            recordAccessPolicy,
            reachRecorder,
            TestMessageSources.bundles(),
            deliveryRecordRepository,
            com.example.hms.service.vitals.SevereHypertensionFixtures.actingHospitalOnly(deliveryRecordRepository),
            maternityVitalCharting
        );

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        recorderId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Test");
        patient.setLastName("Patient");
        patient.setDateOfBirth(LocalDate.of(1990, 1, 1));
        patient.setEmail("test.patient@example.com");
        patient.setPhoneNumberPrimary("1234567890");
        patient.setHospitalId(hospitalId);

        hospital = Hospital.builder()
            .name("Demo Hospital")
            .code("DEMO")
            .build();
        hospital.setId(hospitalId);

        User recorder = User.builder()
            .username("nurse.jane")
            .email("nurse.jane@example.com")
            .passwordHash("hash")
            .phoneNumber("0000000000")
            .build();
        recorder.setId(recorderId);

        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(userRepository.findById(recorderId)).thenReturn(Optional.of(recorder));
        lenient().when(staffRepository.findByUserIdAndHospitalId(recorderId, hospitalId)).thenReturn(Optional.empty());
        lenient().when(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(patientId, hospitalId)).thenReturn(Optional.empty());
        lenient().when(notificationService.created(anyString(), anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(null);
        lenient().when(observationRepository.save(any(PostpartumObservation.class))).thenAnswer(invocation -> {
            PostpartumObservation obs = invocation.getArgument(0);
            obs.setId(UUID.randomUUID());
            return obs;
        });
        lenient().when(carePlanRepository.save(any(PostpartumCarePlan.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // QA D517: postpartum care needs a recorded birth; the tests below have one.
        lenient().when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq(patientId), any(LocalDateTime.class))).thenReturn(true);
    }

    /** QA D517: a postpartum observation for a woman with no recorded delivery is refused. */
    @Test
    void recordObservationWithNoRecordedDeliveryIsRefused() {
        when(deliveryRecordRepository.existsByPatient_IdAndBirthDateTimeGreaterThanEqual(
            org.mockito.ArgumentMatchers.eq(patientId), any(LocalDateTime.class))).thenReturn(false);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .build();

        com.example.hms.exception.BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(
            com.example.hms.exception.BusinessException.class,
            () -> service.recordObservation(patientId, request, recorderId));
        assertEquals("maternity.postpartum.noDelivery", ex.getMessageKey());
        verify(observationRepository, never()).save(any());
        verify(carePlanRepository, never()).save(any());
    }

    @Test
    void recordObservationWithHemorrhageIndicatorsEmitsUrgentAlert() {
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .excessiveBleeding(true)
            .estimatedBloodLossMl(650)
            .educationTopics(Set.of(PostpartumEducationTopic.WARNING_SIGNS_FEVER))
            .educationCompleted(Boolean.TRUE)
            .build();

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);
        // QA D2580: the observation is charted where the chart reads.
        verify(maternityVitalCharting).chartPostpartumObservation(org.mockito.ArgumentMatchers.any());

        assertNotNull(response);
        assertFalse(response.getAlerts().isEmpty(), "Expected hemorrhage alert to be generated");
        assertEquals(PostpartumAlertType.HEMORRHAGE, response.getAlerts().get(0).getType());
        assertEquals(PostpartumAlertSeverity.URGENT, response.getAlerts().get(0).getSeverity());
        assertEquals("postpartum-hemorrhage", response.getAlerts().get(0).getCode());
        // QA D226: the alert body is the staff-locale (French) text, not English composed in code.
        assertTrue(response.getAlerts().get(0).getMessage().startsWith("Risque d"));
        assertTrue(response.getAlerts().get(0).getMessage().toLowerCase().contains("hémorragie"));

    verify(notificationService, times(1)).created(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        // QA D605: the URGENT postpartum alert reaches the on-call team too.
        verify(obstetricAlertEscalation).raiseAfterCommit(org.mockito.ArgumentMatchers.argThat(a ->
            hospitalId.equals(a.hospitalId()) && com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_POSTPARTUM.equals(a.sourceType())
                && "postpartum.alert.msg.postpartum-hemorrhage".equals(a.alertMessageKey())));

        ArgumentCaptor<PostpartumObservation> observationCaptor = ArgumentCaptor.forClass(PostpartumObservation.class);
        verify(observationRepository).save(observationCaptor.capture());
        PostpartumObservation savedObservation = observationCaptor.getValue();
        // CR-06 class: the alert stays, but it does not record that anyone activated the protocol.
        assertFalse(savedObservation.isHemorrhageProtocolActivated(), "An alert must not attest the protocol");
    }

    /**
     * QA D1890 (provisional, 2026-11-14): systolic >= 160 OR diastolic >= 110 mmHg after birth is an
     * URGENT obstetric-review warning; the observation is saved either way.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}/{1} -> alert {2}")
    @org.junit.jupiter.params.provider.CsvSource({
        "162,112,true", "164,114,true", "160,80,true", "120,110,true", "159,109,false", "200,,true", ",115,true"
    })
    void severeBloodPressureAfterBirthIsAnUrgentWarningThatNeverBlocksTheSave_D1890(
        Integer systolic, Integer diastolic, boolean alerts) {
        // r54: born within the 42 postpartum days.
        lenient().when(deliveryRecordRepository
                .findFirstByPatient_IdAndHospital_IdInAndBirthDateTimeBetweenOrderByBirthDateTimeDesc(
                    eq(patientId), any(), any(), any()))
            .thenReturn(Optional.of(com.example.hms.service.vitals.SevereHypertensionFixtures.birth(hospitalId,
                LocalDateTime.now().minusDays(2))));
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .systolicBpMmHg(systolic)
            .diastolicBpMmHg(diastolic)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        verify(observationRepository).save(any(PostpartumObservation.class));
        java.util.List<com.example.hms.payload.dto.clinical.postpartum.PostpartumAlertDTO> severe = response.getAlerts().stream()
            .filter(alert -> "postpartum-severe-hypertension".equals(alert.getCode()))
            .toList();
        assertEquals(alerts ? 1 : 0, severe.size());
        if (alerts) {
            assertEquals(PostpartumAlertType.HYPERTENSION, severe.get(0).getType());
            assertEquals(PostpartumAlertSeverity.URGENT, severe.get(0).getSeverity());
            assertTrue(severe.get(0).getMessage().contains("avis obstétrical urgent"), severe.get(0).getMessage());
            verify(obstetricAlertEscalation).raiseAfterCommit(org.mockito.ArgumentMatchers.argThat(a ->
                com.example.hms.service.vitals.SevereHypertension.MESSAGE_KEY.equals(a.alertMessageKey())));
        } else {
            verify(obstetricAlertEscalation, never()).raiseAfterCommit(any());
        }
    }

    /**
     * r54 (provisional, 2026-11-15): past the 42 postpartum days the adult rule judges the observation:
     * 164/114 raises nothing, 185/100 a CAUTION recheck warning, never the obstetric URGENT one.
     */
    @org.junit.jupiter.params.ParameterizedTest(name = "{0}/{1} after 42 days -> adult warning {2}")
    @org.junit.jupiter.params.provider.CsvSource({"164,114,false", "179,119,false", "180,80,true", "120,120,true"})
    void after42DaysTheAdultRuleJudgesTheObservation(Integer systolic, Integer diastolic, boolean warns) {
        // Born 60 days ago: past the 42 days; an adult mother (1990), so the adult rule judges.
        com.example.hms.service.vitals.SevereHypertensionFixtures.bornOn(deliveryRecordRepository, patientId,
            hospitalId, LocalDateTime.now().minusDays(60));
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .systolicBpMmHg(systolic)
            .diastolicBpMmHg(diastolic)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        verify(observationRepository).save(any(PostpartumObservation.class));
        assertThat(response.getAlerts()).noneMatch(a -> "postpartum-severe-hypertension".equals(a.getCode()));
        java.util.List<com.example.hms.payload.dto.clinical.postpartum.PostpartumAlertDTO> adult = response.getAlerts().stream()
            .filter(a -> "adult-severe-hypertension".equals(a.getCode()))
            .toList();
        assertEquals(warns ? 1 : 0, adult.size());
        if (warns) {
            assertEquals(PostpartumAlertSeverity.CAUTION, adult.get(0).getSeverity());
            assertTrue(adult.get(0).getMessage().contains("recontrôler la tension au repos"), adult.get(0).getMessage());
        }
    }

    /**
     * r54 review P2 (provisional, coordinator's decision of 2026-11-15): a mother under 18 past the 42
     * days is covered by the adult thresholds (she has given birth): 200/130 on day 50 warns.
     */
    @Test
    void aTeenMotherPast42DaysIsJudgedByTheAdultThresholds() {
        patient.setDateOfBirth(LocalDate.now().minusYears(16));
        com.example.hms.service.vitals.SevereHypertensionFixtures.bornOn(deliveryRecordRepository, patientId,
            hospitalId, LocalDateTime.now().minusDays(50));
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .systolicBpMmHg(200)
            .diastolicBpMmHg(130)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertThat(response.getAlerts()).anySatisfy(a -> {
            assertEquals("adult-severe-hypertension", a.getCode());
            assertEquals(PostpartumAlertSeverity.CAUTION, a.getSeverity());
        });
    }

    @Test
    void recordObservationReusesExistingPlanAndSchedulesImmediateFollowUp() {
        PostpartumCarePlan existingPlan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        existingPlan.setId(UUID.randomUUID());
        existingPlan.setImmediateObservationTarget(4);
        existingPlan.setImmediateObservationsCompleted(0);
        existingPlan.setActive(true);
        existingPlan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(existingPlan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .educationCompleted(Boolean.TRUE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertNotNull(response);
        assertTrue(response.getAlerts().isEmpty(), "Should not trigger alerts for stable observation");
        assertEquals(1, existingPlan.getImmediateObservationsCompleted(), "Immediate observation count should increment");
        assertFalse(existingPlan.isImmediateWindowCompleted(), "Immediate window should remain open");
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, existingPlan.getActivePhase());
        assertNotNull(existingPlan.getNextDueAt(), "Next due check should be scheduled");
        assertEquals(existingPlan.getNextDueAt(), response.getSchedule().getNextDueAt());
        assertEquals(observationTime.plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES), existingPlan.getNextDueAt());
    verify(notificationService, never()).created(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordObservationEscalatesToEnhancedMonitoringOnInfectionAlert() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        plan.setId(UUID.randomUUID());
        plan.setImmediateWindowCompleted(true);
        plan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
        plan.setShiftFrequencyMinutes(PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES);

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .temperatureCelsius(38.5)
            .foulLochiaOdor(Boolean.TRUE)
            .painScore(8)
            .moodStatus(PostpartumMoodStatus.TEARFUL)
            .supportStatus(PostpartumSupportStatus.LIMITED)
            .educationCompleted(Boolean.TRUE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertNotNull(response);
        assertFalse(response.getAlerts().isEmpty(), "Expected infection alert");
        int expectedFrequency = Math.max(PostpartumCarePlan.MIN_SHIFT_FREQUENCY_MINUTES,
            PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES / 2);
        assertEquals(expectedFrequency, plan.getShiftFrequencyMinutes(),
            "Shift frequency should tighten during enhanced monitoring");
        assertEquals(PostpartumSchedulePhase.ENHANCED_MONITORING, plan.getActivePhase());
    assertTrue(plan.isMentalHealthReferralOutstanding(), "Psychosocial referral should be tracked");
    assertTrue(plan.isSocialSupportReferralOutstanding(), "Support referral should be tracked");
    assertTrue(plan.isPainFollowupOutstanding(), "Pain follow-up should be tracked");

        assertTrue(response.getAlerts().stream().anyMatch(alert -> alert.getType() == PostpartumAlertType.INFECTION));
        verify(notificationService, times(1)).created(any(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordObservationCompletesImmediateWindowWhenStabilized() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        plan.setId(UUID.randomUUID());
        plan.setImmediateObservationTarget(2);
        plan.setImmediateObservationsCompleted(1);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);

        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        int requestedFrequency = 360;
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .stabilizationConfirmed(Boolean.TRUE)
            .shiftFrequencyMinutes(requestedFrequency)
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .educationCompleted(Boolean.TRUE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertNotNull(response);
        assertTrue(plan.isImmediateWindowCompleted(), "Immediate window should be closed after stabilization");
        assertEquals(PostpartumSchedulePhase.SHIFT_BASELINE, plan.getActivePhase(),
            "Plan should transition to shift baseline monitoring");
        assertEquals(requestedFrequency, plan.getShiftFrequencyMinutes());
        assertEquals(observationTime.plusMinutes(requestedFrequency), plan.getNextDueAt());
        assertTrue(response.getSchedule().isImmediateWindowComplete());
    }

    @Test
    void recordObservationWithNullRequestThrowsBusinessException() {
        assertThrows(BusinessException.class, () -> service.recordObservation(patientId, null, recorderId));
        verify(observationRepository, never()).save(any());
    }

    @Test
    void getRecentObservationsWithoutALocalPlanReadTheReadableSet() {
        // E9 #59d — no local care plan: the observations span the policy's
        // readable set at the database.
        UUID otherHospitalId = UUID.randomUUID();
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId, otherHospitalId));
        when(observationRepository.findByPatient_IdAndHospital_IdInOrderByObservationTimeDesc(
                eq(patientId), eq(Set.of(hospitalId, otherHospitalId)), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of());

        assertThat(service.getRecentObservations(patientId, hospitalId, null, 5)).isEmpty();
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(), eq(Map.of()), anyString());
    }

    @Test
    void aCriticalAlertThatClosesTheImmediateWindowLandsInEnhancedMonitoring() {
        // QA D104: after a critical alert the checks fell back to every 480 min.
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .build();
        plan.setId(UUID.randomUUID());
        plan.setImmediateObservationTarget(2);
        plan.setImmediateObservationsCompleted(1);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .stabilizationConfirmed(Boolean.TRUE)
            .temperatureCelsius(38.5)
            .foulLochiaOdor(Boolean.TRUE)
            .educationCompleted(Boolean.TRUE)
            .build();

        service.recordObservation(patientId, request, recorderId);

        assertEquals(PostpartumSchedulePhase.ENHANCED_MONITORING, plan.getActivePhase());
        assertTrue(plan.getShiftFrequencyMinutes() < PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES);
    }

    /* ── QA D1181 (round 22): a note that checks nothing leaves the clock ── */

    @Test
    void aMoodOnlyObservationLeavesTheOverdueFifteenMinuteCheckOverdue_D1181() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        plan.setDeliveryOccurredAt(LocalDateTime.now().minusMinutes(90));
        plan.setImmediateObservationTarget(8);
        plan.setImmediateObservationsCompleted(4);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        LocalDateTime overdueSince = LocalDateTime.now().minusMinutes(20).truncatedTo(ChronoUnit.SECONDS);
        plan.setNextDueAt(overdueSince);
        plan.setOverdueSince(overdueSince);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(1))
            .moodStatus(PostpartumMoodStatus.CALM)
            .psychosocialNotes("Calme, entourée")
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertEquals(4, plan.getImmediateObservationsCompleted(), "a mood is not one of the checks");
        assertEquals(overdueSince, plan.getNextDueAt(), "a mood does not move the next check");
        assertEquals(overdueSince, plan.getOverdueSince(), "the missed check stays overdue");
        assertFalse(plan.isImmediateWindowCompleted());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, plan.getActivePhase());
        assertTrue(response.getSchedule().isOverdue());
        verify(observationRepository).save(any(PostpartumObservation.class));
    }

    @Test
    void anEducationNoteAfterTheWindowClosesNeverClosesItOrMovesTheShiftCheck_D1181() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        // Past the two-hour window with 1/8 checks: a note used to close the window.
        plan.setDeliveryOccurredAt(LocalDateTime.now().minusHours(4));
        plan.setImmediateObservationTarget(8);
        plan.setImmediateObservationsCompleted(1);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        LocalDateTime due = LocalDateTime.now().minusMinutes(100).truncatedTo(ChronoUnit.SECONDS);
        plan.setNextDueAt(due);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        service.recordObservation(patientId, PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(1))
            .educationTopics(Set.of(PostpartumEducationTopic.WARNING_SIGNS_FEVER))
            .educationNotes("Signes d'alerte expliqués")
            .build(), recorderId);

        assertFalse(plan.isImmediateWindowCompleted(), "a note does not close the immediate-recovery window");
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, plan.getActivePhase());
        assertEquals(due, plan.getNextDueAt());
        assertEquals(due, plan.getOverdueSince());
    }

    @Test
    void inTheShiftPhaseANoteLeavesTheNextCheckAndAnEscalatingNoteOnlyBringsItForward_D1181() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        plan.setDeliveryOccurredAt(LocalDateTime.now().minusHours(10));
        plan.setImmediateWindowCompleted(true);
        plan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
        plan.setShiftFrequencyMinutes(PostpartumCarePlan.DEFAULT_SHIFT_FREQUENCY_MINUTES);
        LocalDateTime due = LocalDateTime.now().minusMinutes(30).truncatedTo(ChronoUnit.SECONDS);
        plan.setNextDueAt(due);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        service.recordObservation(patientId, PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(2))
            .sleepStatus(com.example.hms.enums.PostpartumSleepQuality.INTERRUPTED)
            .painScore(3)
            .build(), recorderId);
        assertEquals(due, plan.getNextDueAt(), "sleep and pain are recorded, the check stays due");
        assertEquals(PostpartumSchedulePhase.SHIFT_BASELINE, plan.getActivePhase());

        // Chills alone raise the infection alert: monitoring is escalated, the overdue check is not pushed back.
        service.recordObservation(patientId, PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(1))
            .chillsOrRigors(Boolean.TRUE)
            .build(), recorderId);
        assertEquals(PostpartumSchedulePhase.ENHANCED_MONITORING, plan.getActivePhase());
        assertEquals(due, plan.getNextDueAt(), "an escalation brings the check forward, never back");
        assertEquals(due, plan.getOverdueSince());
    }

    /* ── QA D268 (round 5) ────────────────────────────────────────────── */

    @Test
    void aFutureDatedObservationIsRefusedAndTheScheduleIsUntouched_D268() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        LocalDateTime nextDue = LocalDateTime.now().plusMinutes(10);
        plan.setNextDueAt(nextDue);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().plusHours(24))
            .fundusTone(PostpartumFundusTone.FIRM)
            .build();

        assertThatThrownBy(() -> service.recordObservation(patientId, request, recorderId))
            .isInstanceOf(BusinessException.class)
            .extracting(e -> ((BusinessException) e).getMessageKey())
            .isEqualTo("maternity.postpartum.observationTime.future");
        verify(observationRepository, never()).save(any());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, plan.getActivePhase());
        assertEquals(nextDue, plan.getNextDueAt());
    }

    @Test
    void afterAHaemorrhageTheFifteenMinuteChecksOutliveTheTwoHourWindow_D268() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        plan.setDeliveryOccurredAt(LocalDateTime.now().minusHours(3));
        plan.setImmediateObservationsCompleted(2);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));
        when(observationRepository.existsHaemorrhageOnPlan(plan.getId())).thenReturn(true);

        LocalDateTime observationTime = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(observationTime)
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .build();

        service.recordObservation(patientId, request, recorderId);

        assertFalse(plan.isImmediateWindowCompleted());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, plan.getActivePhase());
        assertEquals(observationTime.plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES), plan.getNextDueAt());
    }

    /* CR-06 class: an alert never records the protocol as activated. */

    private PostpartumCarePlan planThreeHoursAfterBirth() {
        PostpartumCarePlan plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());
        plan.setDeliveryOccurredAt(LocalDateTime.now().minusHours(3));
        plan.setImmediateObservationsCompleted(2);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));
        return plan;
    }

    private PostpartumObservationRequestDTO.PostpartumObservationRequestDTOBuilder aCheckAt(LocalDateTime time) {
        return PostpartumObservationRequestDTO.builder().hospitalId(hospitalId).observationTime(time);
    }

    private void assertFifteenMinuteChecksContinue(PostpartumCarePlan plan, LocalDateTime time) {
        assertFalse(plan.isImmediateWindowCompleted());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, plan.getActivePhase());
        assertEquals(time.plusMinutes(PostpartumCarePlan.IMMEDIATE_INTERVAL_MINUTES), plan.getNextDueAt());
    }

    @Test
    void indicatorsWithASubmittedFalseStayFalseKeepTheAlertAndKeepTheChecks() {
        PostpartumCarePlan plan = planThreeHoursAfterBirth();
        LocalDateTime time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        PostpartumObservationResponseDTO response = service.recordObservation(patientId,
            aCheckAt(time).excessiveBleeding(true).hemorrhageProtocolActivated(false).build(), recorderId);

        assertFalse(response.isHemorrhageProtocolActivated());
        assertEquals(PostpartumAlertType.HEMORRHAGE, response.getAlerts().get(0).getType());
        assertFifteenMinuteChecksContinue(plan, time);
    }

    @Test
    void indicatorsWithNothingSubmittedStayFalseAndKeepTheChecks() {
        PostpartumCarePlan plan = planThreeHoursAfterBirth();
        LocalDateTime time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        PostpartumObservationResponseDTO response = service.recordObservation(patientId,
            aCheckAt(time).fundusTone(PostpartumFundusTone.BOGGY).build(), recorderId);

        assertFalse(response.isHemorrhageProtocolActivated());
        assertFifteenMinuteChecksContinue(plan, time);
    }

    @Test
    void aSubmittedTrueStaysTrueWithIndicators() {
        PostpartumCarePlan plan = planThreeHoursAfterBirth();
        LocalDateTime time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        PostpartumObservationResponseDTO response = service.recordObservation(patientId,
            aCheckAt(time).excessiveBleeding(true).hemorrhageProtocolActivated(true).build(), recorderId);

        assertTrue(response.isHemorrhageProtocolActivated());
        assertFifteenMinuteChecksContinue(plan, time);
    }

    @Test
    void aSubmittedTrueStaysTrueWithoutIndicatorsAndKeepsTheChecks() {
        PostpartumCarePlan plan = planThreeHoursAfterBirth();
        LocalDateTime time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        PostpartumObservationResponseDTO response = service.recordObservation(patientId,
            aCheckAt(time).fundusTone(PostpartumFundusTone.FIRM).hemorrhageProtocolActivated(true).build(), recorderId);

        assertTrue(response.isHemorrhageProtocolActivated());
        assertTrue(response.getAlerts().isEmpty());
        assertFifteenMinuteChecksContinue(plan, time);
    }

    @Test
    void noIndicatorsAndFalseMeanNoEnhancedSchedule() {
        PostpartumCarePlan plan = planThreeHoursAfterBirth();
        LocalDateTime time = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);

        PostpartumObservationResponseDTO response = service.recordObservation(patientId,
            aCheckAt(time).fundusTone(PostpartumFundusTone.FIRM).hemorrhageProtocolActivated(false).build(), recorderId);

        assertFalse(response.isHemorrhageProtocolActivated());
        assertTrue(response.getAlerts().isEmpty());
        assertTrue(plan.isImmediateWindowCompleted());
        assertEquals(PostpartumSchedulePhase.SHIFT_BASELINE, plan.getActivePhase());
    }

    @Test
    void indicatorsAtTheClosingStabilizationStillLandInEnhancedMonitoringWithAFalseFlag_D104() {
        PostpartumCarePlan plan = planThreeHoursAfterBirth();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId,
            aCheckAt(LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS)).excessiveBleeding(true)
                .stabilizationConfirmed(true).hemorrhageProtocolActivated(false).build(), recorderId);

        assertFalse(response.isHemorrhageProtocolActivated());
        assertEquals(PostpartumSchedulePhase.ENHANCED_MONITORING, plan.getActivePhase());
    }

    @Test
    void aSystolicOf900IsRefusedByValidation_D277() {
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId).systolicBpMmHg(900).build();
        try (jakarta.validation.ValidatorFactory factory = jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(request))
                .extracting(v -> v.getPropertyPath().toString())
                .containsExactly("systolicBpMmHg");
        }
    }

    /* ── QA D215 / D216 (round 4) ─────────────────────────────────────── */

    @Test
    void anObservationAfterANewDeliveryStartsAFreshPlanInsteadOfReusingTheOldOne() {
        LocalDateTime firstBirth = LocalDateTime.now().minusDays(2);
        LocalDateTime secondBirth = LocalDateTime.now().minusMinutes(30);
        PostpartumCarePlan oldPlan = PostpartumCarePlan.builder()
            .patient(patient)
            .hospital(hospital)
            .deliveryOccurredAt(firstBirth)
            .build();
        oldPlan.setId(UUID.randomUUID());
        oldPlan.setActive(true);
        oldPlan.setImmediateWindowCompleted(true);
        oldPlan.markActivePhase(PostpartumSchedulePhase.SHIFT_BASELINE);
        oldPlan.setShiftFrequencyMinutes(240);
        oldPlan.setOverdueSince(LocalDateTime.now().minusHours(5));
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(oldPlan));
        com.example.hms.model.labor.DeliveryRecord latest = com.example.hms.model.labor.DeliveryRecord.builder()
            .birthDateTime(secondBirth).build();
        when(deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, hospitalId))
            .thenReturn(Optional.of(latest));

        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now())
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        assertFalse(oldPlan.isActive(), "the earlier delivery's plan must be closed");
        ArgumentCaptor<PostpartumCarePlan> saved = ArgumentCaptor.forClass(PostpartumCarePlan.class);
        verify(carePlanRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        PostpartumCarePlan fresh = saved.getAllValues().stream().filter(p -> p != oldPlan).findFirst().orElseThrow();
        assertEquals(secondBirth, fresh.getDeliveryOccurredAt());
        assertEquals(PostpartumSchedulePhase.IMMEDIATE_RECOVERY, fresh.getActivePhase());
        assertFalse(fresh.isImmediateWindowCompleted());
        assertFalse(response.getSchedule().isOverdue(), "a check due in the future is not overdue");
    }

    /** QA D762, the exact replay: a postpartum check at birth -3 h returned 201 and counted. */
    @Test
    void anObservationBeforeTheBirthIsRefusedAndNothingCounts_D762() {
        LocalDateTime birth = LocalDateTime.now().minusHours(2);
        PostpartumCarePlan plan = PostpartumCarePlan.builder()
            .patient(patient).hospital(hospital).deliveryOccurredAt(birth).build();
        plan.setId(UUID.randomUUID());
        plan.setActive(true);
        plan.setImmediateObservationTarget(4);
        plan.setImmediateObservationsCompleted(0);
        plan.markActivePhase(PostpartumSchedulePhase.IMMEDIATE_RECOVERY);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.of(plan));

        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(birth.minusHours(3))
            .fundusTone(PostpartumFundusTone.FIRM)
            .lochiaAmount(PostpartumLochiaAmount.MODERATE)
            .build();

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
            () -> service.recordObservation(patientId, request, recorderId));
        assertEquals("maternity.postpartum.observationTime.beforeBirth", ex.getMessageKey());
        assertEquals(0, plan.getImmediateObservationsCompleted());
        verify(observationRepository, never()).save(any());
    }

    @Test
    void urgentPostpartumNotificationsAreFrenchWithATranslatedType() {
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .excessiveBleeding(true)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(message.capture(), anyString(), org.mockito.ArgumentMatchers.any());
        assertTrue(message.getValue().startsWith("Alerte post-partum (hémorragie)"), message.getValue());
        assertFalse(message.getValue().contains("HEMORRHAGE"), message.getValue());
        assertEquals("Évaluation des saignements", response.getAlerts().get(0).getTriggeredBy());
    }

    /** Policy 2026-11-20 (provisional, CRADLE add-on): shock index (pulse ÷ systolic) from 0.9 yellow, from 1.7 red. */
    @org.junit.jupiter.params.ParameterizedTest(name = "pulse {0} over {1} -> {2}")
    @org.junit.jupiter.params.provider.CsvSource({
        "89,100,", "90,100,postpartum-shock", "169,100,postpartum-shock", "170,100,postpartum-severe-shock"
    })
    void policy20261120_theShockIndexAfterBirth(int pulse, int systolic, String expectedCode) {
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        PostpartumObservationRequestDTO request = PostpartumObservationRequestDTO.builder()
            .hospitalId(hospitalId)
            .observationTime(LocalDateTime.now().minusMinutes(5))
            .pulseBpm(pulse)
            .systolicBpMmHg(systolic)
            .build();

        PostpartumObservationResponseDTO response = service.recordObservation(patientId, request, recorderId);

        java.util.List<com.example.hms.payload.dto.clinical.postpartum.PostpartumAlertDTO> shock = response.getAlerts().stream()
            .filter(alert -> alert.getCode() != null && alert.getCode().contains("shock")).toList();
        if (expectedCode == null) {
            assertThat(shock).isEmpty();
        } else {
            assertThat(shock).singleElement().satisfies(alert -> {
                assertThat(alert.getCode()).isEqualTo(expectedCode);
                assertThat(String.valueOf(alert.getSeverity()))
                    .isEqualTo("postpartum-severe-shock".equals(expectedCode) ? "URGENT" : "CAUTION");
                assertThat(alert.getMessage()).contains("Indice de choc").contains("Règle provisoire");
            });
        }
    }
}
