package com.example.hms.service.impl;

import com.example.hms.enums.NewbornDangerSignObservation;
import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.NewbornFollowUpAction;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.NewbornAssessmentMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import java.util.Map;
import java.util.Set;
import com.example.hms.i18n.TestMessageSources;

@ExtendWith(MockitoExtension.class)
class NewbornAssessmentServiceImplTest {

    @Mock
    private com.example.hms.service.support.PatientRegistrationGuard patientRegistrationGuard;

    @Mock
    private NewbornAssessmentRepository assessmentRepository;
    @Mock
    private PatientRepository patientRepository;
    @Mock
    private PatientHospitalRegistrationRepository registrationRepository;
    @Mock
    private HospitalRepository hospitalRepository;
    @Mock
    private com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;
    @Mock
    private com.example.hms.repository.DeliveryNewbornRepository deliveryNewbornRepository;
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

    private NewbornAssessmentServiceImpl service;
    private UUID patientId;
    private UUID hospitalId;
    private UUID recorderId;
    private Patient patient;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        service = new NewbornAssessmentServiceImpl(
            assessmentRepository,
            patientRepository, patientRegistrationGuard,
            registrationRepository,
            hospitalRepository,
            deliveryRecordRepository,
            deliveryNewbornRepository,
            staffRepository,
            userRepository,
            notificationService,
            obstetricAlertEscalation,
            new NewbornAssessmentMapper(),
            recordAccessPolicy,
            reachRecorder,
            TestMessageSources.bundles(),
            new com.example.hms.service.maternity.StillbornNeonatalAlerts(deliveryNewbornRepository,
                assessmentRepository, org.mockito.Mockito.mock(
                    com.example.hms.repository.ObstetricAlertEscalationRepository.class))
        );

        patientId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        recorderId = UUID.randomUUID();

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Test");
        patient.setLastName("Baby");
        patient.setDateOfBirth(LocalDate.now());
        patient.setHospitalId(hospitalId);

        hospital = Hospital.builder()
            .name("Demo")
            .code("DEMO")
            .build();
        hospital.setId(hospitalId);

        User recorder = User.builder()
            .username("nurse.neo")
            .email("nurse.neo@example.com")
            .passwordHash("hash")
            .build();
        recorder.setId(recorderId);

        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(userRepository.findById(recorderId)).thenReturn(Optional.of(recorder));
        lenient().when(notificationService.created(anyString(), anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(null);
        lenient().when(assessmentRepository.save(any(NewbornAssessment.class))).thenAnswer(invocation -> {
            NewbornAssessment assessment = invocation.getArgument(0);
            assessment.setId(UUID.randomUUID());
            return assessment;
        });
    }

    @Test
    void recordAssessmentWithLowApgarTriggersUrgentAlert() {
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .apgarOneMinute(5)
            .apgarFiveMinute(6)
            .temperatureCelsius(36.8)
            .build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        assertNotNull(response);
        assertEquals(2, response.getAlerts().size(), "Expected urgent alerts for low Apgar scores");
        assertEquals(NewbornAlertType.APGAR, response.getAlerts().get(0).getType());
        assertEquals(NewbornAlertSeverity.URGENT, response.getAlerts().get(0).getSeverity());
        assertEquals(NewbornAlertSeverity.URGENT, response.getAlerts().get(1).getSeverity());
        assertTrue(response.getFollowUpActions().contains(NewbornFollowUpAction.NICU_CONSULT));
        verify(notificationService, times(2)).created(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
        // QA D605: each URGENT neonatal alert reaches the on-call team too.
        verify(obstetricAlertEscalation, times(2)).raiseAfterCommit(org.mockito.ArgumentMatchers.argThat(a ->
            hospitalId.equals(a.hospitalId()) && com.example.hms.service.maternity.ObstetricAlertEscalationService.SOURCE_NEWBORN.equals(a.sourceType())
                && a.alertMessageKey() != null && a.alertMessageKey().startsWith("newborn.alert.msg.newborn-")));

        ArgumentCaptor<NewbornAssessment> assessmentCaptor = ArgumentCaptor.forClass(NewbornAssessment.class);
        verify(assessmentRepository).save(assessmentCaptor.capture());
        assertTrue(assessmentCaptor.getValue().isEscalationRecommended());
    }

    @Test
    void recordAssessmentStableDoesNotTriggerAlert() {
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .apgarOneMinute(8)
            .apgarFiveMinute(9)
            .temperatureCelsius(36.9)
            .heartRateBpm(140)
            .respirationsPerMin(48)
            .oxygenSaturationPercent(95)
            .glucoseMgDl(60)
            .build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        assertNotNull(response);
        assertTrue(response.getAlerts().isEmpty(), "No alerts expected for stable assessment");
        verify(notificationService, never()).created(anyString(), anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void recordAssessmentWithNullRequestThrowsBusinessException() {
        assertThrows(BusinessException.class, () -> service.recordAssessment(patientId, null, recorderId));
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void searchAssessmentsReturnsPagedResults() {
        when(assessmentRepository.findWithinRange(any(), any(), any(), any(), any())).thenReturn(List.of());
        List<NewbornAssessmentResponseDTO> results = service.searchAssessments(patientId, hospitalId, null, null, 0, 25);
        assertNotNull(results);
        verify(assessmentRepository).findWithinRange(any(), any(), any(), any(), any());
    }

    // ── delivery-record back-link (P1 #6) ──────────────────────────────────
    // V111 shipped the column, the FK and the @ManyToOne, and nothing ever set
    // or read them: a newborn assessment could not be traced back to the labour
    // it came from, which is the whole point of the back-link.

    private com.example.hms.model.labor.DeliveryRecord deliveryRecordAt(UUID atHospitalId) {
        Hospital owner = Hospital.builder().name("Owner").code("OWN").build();
        owner.setId(atHospitalId);
        com.example.hms.model.labor.DeliveryRecord delivery =
            new com.example.hms.model.labor.DeliveryRecord();
        delivery.setId(UUID.randomUUID());
        delivery.setHospital(owner);
        return delivery;
    }

    @Test
    void recordAssessmentLinksTheDeliveryRecordWhenSupplied() {
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordAt(hospitalId);
        when(deliveryRecordRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .deliveryRecordId(delivery.getId())
            .apgarOneMinute(8)
            .apgarFiveMinute(9)
            .build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        assertEquals(delivery.getId(), response.getDeliveryRecordId());

        ArgumentCaptor<NewbornAssessment> captor = ArgumentCaptor.forClass(NewbornAssessment.class);
        verify(assessmentRepository).save(captor.capture());
        assertNotNull(captor.getValue().getDeliveryRecord());
        assertEquals(delivery.getId(), captor.getValue().getDeliveryRecord().getId());
    }

    @Test
    void recordAssessmentWithoutDeliveryRecordStillSucceeds() {
        // A newborn transferred in already born, or one predating the L&D module,
        // has no delivery to link. The assessment is complete without it.
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .apgarOneMinute(8)
            .apgarFiveMinute(9)
            .build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        assertNull(response.getDeliveryRecordId());
        verify(deliveryRecordRepository, never()).findById(any());
    }

    @Test
    void recordAssessmentRejectsADeliveryRecordFromAnotherHospital() {
        com.example.hms.model.labor.DeliveryRecord foreign = deliveryRecordAt(UUID.randomUUID());
        when(deliveryRecordRepository.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .deliveryRecordId(foreign.getId())
            .apgarOneMinute(8)
            .apgarFiveMinute(9)
            .build();

        assertThrows(BusinessException.class,
            () -> service.recordAssessment(patientId, request, recorderId));
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void recordAssessmentRejectsAnUnknownDeliveryRecord() {
        UUID missing = UUID.randomUUID();
        when(deliveryRecordRepository.findById(missing)).thenReturn(Optional.empty());

        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .deliveryRecordId(missing)
            .apgarOneMinute(8)
            .apgarFiveMinute(9)
            .build();

        assertThrows(ResourceNotFoundException.class,
            () -> service.recordAssessment(patientId, request, recorderId));
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void getRecentAssessmentsReadTheReadableSet() {
        // E9 #59d — the read spans the policy's readable set at the database.
        UUID otherHospitalId = UUID.randomUUID();
        when(recordAccessPolicy.readableHospitalIds(any(), eq(patientId), eq(hospitalId)))
            .thenReturn(Set.of(hospitalId, otherHospitalId));
        when(assessmentRepository.findByPatient_IdAndHospital_IdInOrderByAssessmentTimeDesc(
                eq(patientId), eq(Set.of(hospitalId, otherHospitalId)), any(org.springframework.data.domain.Pageable.class)))
            .thenReturn(List.of());

        assertThat(service.getRecentAssessments(patientId, hospitalId, 5)).isEmpty();
        verify(reachRecorder).recordReach(eq(patientId), eq(hospitalId), any(), isNull(), eq(Map.of()), anyString());
    }

    // ── QA D161 (D103 family) and D131 ────────────────────────────────────
    // The maternity screen records the assessment on the MOTHER's chart: an
    // adult chart recorded as male must be refused, as every obstetric record is.

    @Test
    void recordAssessmentOnAnAdultMaleChartIsRefused() {
        patient.setDateOfBirth(LocalDate.of(1985, 5, 1));
        patient.setGender("M");
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(8).apgarFiveMinute(9).build();

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.recordAssessment(patientId, request, recorderId));
        assertEquals("maternity.patient.male", ex.getMessageKey());
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void recordAssessmentOnAMaleNeonateChartIsAccepted() {
        patient.setGender("M");
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(8).apgarFiveMinute(9).build();

        assertNotNull(service.recordAssessment(patientId, request, recorderId));
    }

    @Test
    void recordAssessmentOnTheMotherChartRefusesAnotherMothersDelivery() {
        patient.setDateOfBirth(LocalDate.of(1995, 5, 1));
        patient.setGender("F");
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordAt(hospitalId);
        Patient otherMother = new Patient();
        otherMother.setId(UUID.randomUUID());
        delivery.setPatient(otherMother);
        when(deliveryRecordRepository.findById(delivery.getId())).thenReturn(Optional.of(delivery));

        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).deliveryRecordId(delivery.getId())
            .apgarOneMinute(8).apgarFiveMinute(9).build();

        assertThrows(BusinessException.class,
            () -> service.recordAssessment(patientId, request, recorderId));
        verify(assessmentRepository, never()).save(any());
    }

    /** QA D517: on the mother's chart, no recorded delivery means no newborn to assess. */
    @Test
    void recordAssessmentOnTheMotherChartWithNoRecordedDeliveryIsRefused() {
        patient.setDateOfBirth(LocalDate.of(1995, 5, 1));
        patient.setGender("F");
        when(deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, hospitalId))
            .thenReturn(Optional.empty());
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(8).apgarFiveMinute(9).build();

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.recordAssessment(patientId, request, recorderId));
        assertEquals("maternity.newborn.noDelivery", ex.getMessageKey());
        verify(assessmentRepository, never()).save(any());
    }

    /** QA D517: on the mother's chart, the assessment links her latest delivery here. */
    @Test
    void recordAssessmentOnTheMotherChartLinksHerLatestDelivery() {
        patient.setDateOfBirth(LocalDate.of(1995, 5, 1));
        patient.setGender("F");
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordAt(hospitalId);
        delivery.setPatient(patient);
        delivery.setBirthDateTime(java.time.LocalDateTime.now().minusDays(2));
        when(deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, hospitalId))
            .thenReturn(Optional.of(delivery));
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(8).apgarFiveMinute(9).build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        assertEquals(delivery.getId(), response.getDeliveryRecordId());
    }

    /** QA D762, the exact replay: an assessment at birth -3 h on the mother's chart returned 201. */
    @Test
    void recordAssessmentBeforeTheBirthIsRefused() {
        patient.setDateOfBirth(LocalDate.of(1995, 5, 1));
        patient.setGender("F");
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordAt(hospitalId);
        delivery.setPatient(patient);
        java.time.LocalDateTime birth = java.time.LocalDateTime.now().minusHours(5);
        delivery.setBirthDateTime(birth);
        when(deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, hospitalId))
            .thenReturn(Optional.of(delivery));
        NewbornAssessmentRequestDTO early = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).assessmentTime(birth.minusHours(3)).apgarOneMinute(8).apgarFiveMinute(9).build();

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
            () -> service.recordAssessment(patientId, early, recorderId));
        assertEquals("maternity.newborn.assessmentTime.beforeBirth", ex.getMessageKey());
        verify(assessmentRepository, never()).save(any());

        NewbornAssessmentRequestDTO after = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).assessmentTime(birth.plusHours(1)).apgarOneMinute(8).apgarFiveMinute(9).build();
        assertNotNull(service.recordAssessment(patientId, after, recorderId));
    }

    /** QA D762 on the newborn's own chart: before its date of birth is before the birth. */
    @Test
    void recordAssessmentBeforeTheNeonateDateOfBirthIsRefused() {
        NewbornAssessmentRequestDTO early = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).assessmentTime(LocalDate.now().atStartOfDay().minusHours(3))
            .apgarOneMinute(8).apgarFiveMinute(9).build();

        BusinessException ex = org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
            () -> service.recordAssessment(patientId, early, recorderId));
        assertEquals("maternity.newborn.assessmentTime.beforeBirth", ex.getMessageKey());
    }

    /** QA D517: a delivery older than the neonatal period has no newborn left to assess. */
    @Test
    void recordAssessmentOnTheMotherChartRefusesADeliveryPastTheNeonatalPeriod() {
        patient.setDateOfBirth(LocalDate.of(1995, 5, 1));
        patient.setGender("F");
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordAt(hospitalId);
        delivery.setPatient(patient);
        delivery.setBirthDateTime(java.time.LocalDateTime.now().minusYears(3));
        when(deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, hospitalId))
            .thenReturn(Optional.of(delivery));
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(8).apgarFiveMinute(9).build();

        BusinessException ex = assertThrows(BusinessException.class,
            () -> service.recordAssessment(patientId, request, recorderId));
        assertEquals("maternity.newborn.noDelivery", ex.getMessageKey());
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void abnormalAssessmentAlertsCarryACreationTime() {
        // QA D131: the alert rows were inserted with a NULL created_at, so every
        // abnormal assessment failed to save.
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(5).apgarFiveMinute(6).build();

        service.recordAssessment(patientId, request, recorderId);

        ArgumentCaptor<NewbornAssessment> captor = ArgumentCaptor.forClass(NewbornAssessment.class);
        verify(assessmentRepository).save(captor.capture());
        assertTrue(!captor.getValue().getAlerts().isEmpty());
        captor.getValue().getAlerts().forEach(alert -> assertNotNull(alert.getCreatedAt()));
    }

    /* ── QA D216 (D172/D61): newborn alerts in French, no raw enum ──────── */

    @Test
    void urgentNewbornNotificationsAreFrenchWithATranslatedType() {
        NewbornAssessmentRequestDTO request = NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId)
            .glucoseMgDl(20)
            .build();

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, request, recorderId);

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).created(message.capture(), anyString(), org.mockito.ArgumentMatchers.any());
        assertTrue(message.getValue().startsWith("Alerte nouveau-né (glycémie)"), message.getValue());
        assertTrue(message.getValue().contains("Hypoglycémie critique"), message.getValue());
        assertFalse(message.getValue().contains("GLUCOSE"), message.getValue());
        assertEquals("Glycémie", response.getAlerts().get(0).getTriggeredBy());
    }

    private java.util.Set<String> codesOf(NewbornAssessmentResponseDTO response) {
        return response.getAlerts().stream().map(a -> a.getCode()).collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void d1424_extremesSaveAndEscalate() {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).heartRateBpm(1000).respirationsPerMin(200).glucoseMgDl(18).build(), recorderId);

        assertTrue(codesOf(response).containsAll(java.util.List.of("newborn-heart-rate", "newborn-check-heart-rate",
            "newborn-respiratory", "newborn-check-respiratory", "newborn-glucose")));
        assertTrue(response.isEscalationRecommended());
        verify(assessmentRepository).save(any(NewbornAssessment.class));
        verify(obstetricAlertEscalation, times(3)).raiseAfterCommit(any());
    }

    @Test
    void d1424_rr85SavesAndTriggersTheRespiratoryAlert() {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).respirationsPerMin(85).build(), recorderId);
        assertTrue(codesOf(response).contains("newborn-respiratory"));
        assertFalse(response.isRespiratorySupportInitiated(), "an alert is not support somebody started");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {37.6, 37.9})
    void cr04_aTemperatureAbove37_5SavesAndEscalatesLikeAnUrgentFinding(double temperature) {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).temperatureCelsius(temperature).build(), recorderId);

        verify(assessmentRepository).save(any(NewbornAssessment.class));
        assertTrue(codesOf(response).contains("newborn-temperature-who-danger"));
        assertTrue(response.isEscalationRecommended());
        verify(obstetricAlertEscalation).raiseAfterCommit(any());
    }

    @Test
    void cr04_37_5ItselfDoesNotEscalate() {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).temperatureCelsius(37.5).build(), recorderId);

        assertFalse(response.isEscalationRecommended());
        assertTrue(codesOf(response).isEmpty());
    }

    private NewbornAssessmentRequestDTO.NewbornAssessmentRequestDTOBuilder oneSign(String sign, NewbornDangerSignObservation observation) {
        NewbornAssessmentRequestDTO.NewbornAssessmentRequestDTOBuilder builder = NewbornAssessmentRequestDTO.builder().hospitalId(hospitalId);
        switch (sign) {
            case "poorFeeding" -> builder.poorFeeding(observation);
            case "convulsions" -> builder.convulsions(observation);
            case "severeChestIndrawing" -> builder.severeChestIndrawing(observation);
            default -> builder.absentSpontaneousMovement(observation);
        }
        return builder;
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "poorFeeding,newborn-danger-poor-feeding",
        "convulsions,newborn-danger-convulsions",
        "severeChestIndrawing,newborn-danger-chest-indrawing",
        "absentSpontaneousMovement,newborn-danger-no-movement"
    })
    void cr05_oneSignPresentAloneSavesAndEscalatesOnItsOwn(String sign, String code) {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId,
            oneSign(sign, NewbornDangerSignObservation.PRESENT).build(), recorderId);

        assertEquals(java.util.Set.of(code), codesOf(response));
        assertTrue(response.isEscalationRecommended());
        verify(assessmentRepository).save(any(NewbornAssessment.class));
        org.mockito.ArgumentCaptor<com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert> raised =
            org.mockito.ArgumentCaptor.forClass(com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert.class);
        verify(obstetricAlertEscalation).raiseAfterCommit(raised.capture());
        assertEquals("DANGER_SIGN", raised.getValue().alertType());
        assertEquals("newborn.alert.msg." + code, raised.getValue().alertMessageKey());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"poorFeeding", "convulsions", "severeChestIndrawing", "absentSpontaneousMovement"})
    void cr05_oneSignAbsentAloneSavesAndRaisesNothing(String sign) {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId,
            oneSign(sign, NewbornDangerSignObservation.ABSENT).build(), recorderId);

        assertTrue(codesOf(response).isEmpty());
        assertFalse(response.isEscalationRecommended());
        verify(obstetricAlertEscalation, never()).raiseAfterCommit(any());
    }

    @Test
    void cr05_allFourPresentRaiseFourAlerts() {
        NewbornDangerSignObservation present = NewbornDangerSignObservation.PRESENT;
        service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder().hospitalId(hospitalId)
            .poorFeeding(present).convulsions(present).severeChestIndrawing(present).absentSpontaneousMovement(present).build(), recorderId);
        verify(obstetricAlertEscalation, times(4)).raiseAfterCommit(any());
    }

    @Test
    void cr05_blankReadsNotAssessed_neverAbsent_andNeedsNoSign() {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).temperatureCelsius(36.8).build(), recorderId);

        assertEquals(NewbornDangerSignObservation.NOT_ASSESSED, response.getPoorFeeding());
        assertEquals(NewbornDangerSignObservation.NOT_ASSESSED, response.getConvulsions());
        assertEquals(NewbornDangerSignObservation.NOT_ASSESSED, response.getSevereChestIndrawing());
        assertEquals(NewbornDangerSignObservation.NOT_ASSESSED, response.getAbsentSpontaneousMovement());
        assertFalse(response.isEscalationRecommended());
    }

    @Test
    void cr05_freeTextIsNeverReadForASign() {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).examNotes("convulsions, poor feeding, severe chest indrawing, no spontaneous movement")
            .examNeurological("convulsions").followUpNotes("poor feeding").build(), recorderId);

        assertTrue(codesOf(response).isEmpty());
        assertEquals(NewbornDangerSignObservation.NOT_ASSESSED, response.getConvulsions());
    }

    /** The values that used to flip a flag, either side of every band the rules had. */
    private static java.util.stream.Stream<NewbornAssessmentRequestDTO.NewbornAssessmentRequestDTOBuilder> readingsThatUsedToFlipAFlag() {
        return java.util.stream.Stream.of(
            NewbornAssessmentRequestDTO.builder().temperatureCelsius(35.9),
            NewbornAssessmentRequestDTO.builder().temperatureCelsius(38.1),
            NewbornAssessmentRequestDTO.builder().respirationsPerMin(29),
            NewbornAssessmentRequestDTO.builder().respirationsPerMin(71),
            NewbornAssessmentRequestDTO.builder().glucoseMgDl(44),
            NewbornAssessmentRequestDTO.builder().glucoseValue(2.4).glucoseUnit(com.example.hms.enums.GlucoseUnit.MMOL_L));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("readingsThatUsedToFlipAFlag")
    void cr06_aSubmittedFalseStaysFalse_andTheRecommendationStays(NewbornAssessmentRequestDTO.NewbornAssessmentRequestDTOBuilder reading) {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, reading.hospitalId(hospitalId)
            .respiratorySupportInitiated(false).glucoseProtocolInitiated(false).thermoregulationSupportInitiated(false)
            .build(), recorderId);

        assertFalse(response.isRespiratorySupportInitiated());
        assertFalse(response.isGlucoseProtocolInitiated());
        assertFalse(response.isThermoregulationSupportInitiated());
        assertTrue(response.isEscalationRecommended(), "the alert still escalates");
        assertFalse(response.getFollowUpActions().isEmpty(), "the follow-up is still requested");
        assertFalse(response.getAlerts().isEmpty(), "the alert still stands");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("readingsThatUsedToFlipAFlag")
    void cr06_noSubmittedValueIsReadAsFalse(NewbornAssessmentRequestDTO.NewbornAssessmentRequestDTOBuilder reading) {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, reading.hospitalId(hospitalId).build(), recorderId);

        assertFalse(response.isRespiratorySupportInitiated());
        assertFalse(response.isGlucoseProtocolInitiated());
        assertFalse(response.isThermoregulationSupportInitiated());
    }

    @Test
    void cr06_aSubmittedTrueIsKept_withNoAlertAtAll() {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).temperatureCelsius(36.8)
            .respiratorySupportInitiated(true).glucoseProtocolInitiated(true).thermoregulationSupportInitiated(true)
            .build(), recorderId);

        assertTrue(response.isRespiratorySupportInitiated());
        assertTrue(response.isGlucoseProtocolInitiated());
        assertTrue(response.isThermoregulationSupportInitiated());
    }

    @Test
    void d1424_aValueInMmolIsStoredInMgDlAndJudgedAsEntered() {
        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).glucoseValue(2.59).glucoseUnit(com.example.hms.enums.GlucoseUnit.MMOL_L).build(), recorderId);
        assertEquals(47, response.getGlucoseMgDl());
        assertTrue(codesOf(response).stream().anyMatch(c -> c.startsWith("newborn-glucose-")),
            "2.59 mmol/L rounds to 47 mg/dL and is still below 2.6");
        assertFalse(response.isGlucoseProtocolInitiated(), "an alert is not a protocol somebody started");
    }

    private void previousReading(com.example.hms.model.labor.DeliveryRecord delivery, int heartRate) {
        NewbornAssessment previous = NewbornAssessment.builder().heartRateBpm(heartRate).build();
        previous.setDeliveryRecord(delivery);
        lenient().when(assessmentRepository
            .findFirstByPatient_IdAndHospital_IdAndHeartRateBpmIsNotNullAndAssessmentTimeLessThanEqualOrderByAssessmentTimeDesc(
                eq(patientId), eq(hospitalId), any()))
            .thenReturn(Optional.of(previous));
    }

    private com.example.hms.model.labor.DeliveryRecord onTheMothersChart(int infants) {
        patient.setDateOfBirth(LocalDate.of(1995, 5, 1));
        patient.setGender("F");
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordAt(hospitalId);
        delivery.setPatient(patient);
        delivery.setBirthDateTime(java.time.LocalDateTime.now().minusDays(2));
        delivery.setNumberOfInfants(infants);
        when(deliveryRecordRepository.findFirstByPatient_IdAndHospital_IdOrderByBirthDateTimeDesc(patientId, hospitalId))
            .thenReturn(Optional.of(delivery));
        return delivery;
    }

    private NewbornAssessmentResponseDTO heartRate165() {
        return service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).heartRateBpm(165).build(), recorderId);
    }

    @Test
    void d1424_aPreviousReadingOfConcernOnTheBabysOwnChartIsNamed() {
        previousReading(null, 170);
        assertTrue(codesOf(heartRate165()).contains("newborn-heart-rate-repeated"));
    }

    @Test
    void d1424_aNormalPreviousReadingOnlyAsksForARecheck() {
        previousReading(null, 140);
        assertTrue(codesOf(heartRate165()).contains("newborn-heart-rate"));
    }

    @Test
    void d1424_onTheMothersChartASingletonsPreviousReadingCounts() {
        previousReading(onTheMothersChart(1), 170);
        assertTrue(codesOf(heartRate165()).contains("newborn-heart-rate-repeated"));
    }

    @Test
    void d1424_aTwinsReadingProvesNothingAboutThisBaby() {
        previousReading(onTheMothersChart(2), 170);
        assertTrue(codesOf(heartRate165()).contains("newborn-heart-rate"));
    }

    @Test
    void d1424_aPreviousBabysReadingProvesNothingAboutThisOne() {
        onTheMothersChart(1);
        previousReading(deliveryRecordAt(hospitalId), 170);
        assertTrue(codesOf(heartRate165()).contains("newborn-heart-rate"));
    }

    /* ── QA D1592: the baby a delivery registered is assessed on its own chart ── */

    private Patient registeredBaby(com.example.hms.model.labor.DeliveryRecord delivery, int order) {
        Patient baby = new Patient();
        baby.setId(UUID.randomUUID());
        baby.setFirstName("Bébé");
        baby.setLastName("Traore");
        baby.setDateOfBirth(delivery.getBirthDateTime().toLocalDate());
        com.example.hms.model.labor.DeliveryNewborn link = com.example.hms.model.labor.DeliveryNewborn.builder()
            .deliveryRecord(delivery).newbornPatient(baby).motherPatient(patient).hospital(hospital)
            .birthOrder(order).build();
        lenient().when(deliveryNewbornRepository.findByNewbornPatient_Id(baby.getId())).thenReturn(Optional.of(link));
        return baby;
    }

    private void babiesOf(com.example.hms.model.labor.DeliveryRecord delivery, Patient... babies) {
        java.util.List<com.example.hms.model.labor.DeliveryNewborn> rows = new java.util.ArrayList<>();
        for (int i = 0; i < babies.length; i++) {
            rows.add(com.example.hms.model.labor.DeliveryNewborn.builder().deliveryRecord(delivery)
                .newbornPatient(babies[i]).motherPatient(patient).hospital(hospital).birthOrder(i + 1).build());
        }
        when(deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId())).thenReturn(rows);
    }

    @Test
    void d1592_fromTheMothersChartASingletonsAssessmentIsRecordedOnTheBabysChart() {
        com.example.hms.model.labor.DeliveryRecord delivery = onTheMothersChart(1);
        Patient baby = registeredBaby(delivery, 1);
        babiesOf(delivery, baby);

        service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(8).apgarFiveMinute(9).build(), recorderId);

        ArgumentCaptor<NewbornAssessment> saved = ArgumentCaptor.forClass(NewbornAssessment.class);
        verify(assessmentRepository).save(saved.capture());
        assertEquals(baby, saved.getValue().getPatient());
        assertEquals(delivery, saved.getValue().getDeliveryRecord());
        verify(patientRegistrationGuard).requireRegisteredAt(baby.getId(), hospitalId);
    }

    @Test
    void d1592_twinsMustNameTheBaby() {
        com.example.hms.model.labor.DeliveryRecord delivery = onTheMothersChart(2);
        babiesOf(delivery, registeredBaby(delivery, 1), registeredBaby(delivery, 2));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.recordAssessment(patientId,
            NewbornAssessmentRequestDTO.builder().hospitalId(hospitalId).apgarOneMinute(8).build(), recorderId));

        assertEquals("maternity.newborn.whichBaby", ex.getMessageKey());
        verify(assessmentRepository, never()).save(any());
    }

    @Test
    void d1592_twinsNamedBabyGetsTheRow() {
        com.example.hms.model.labor.DeliveryRecord delivery = onTheMothersChart(2);
        Patient second = registeredBaby(delivery, 2);
        babiesOf(delivery, registeredBaby(delivery, 1), second);

        service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder().hospitalId(hospitalId)
            .newbornPatientId(second.getId()).apgarOneMinute(8).build(), recorderId);

        ArgumentCaptor<NewbornAssessment> saved = ArgumentCaptor.forClass(NewbornAssessment.class);
        verify(assessmentRepository).save(saved.capture());
        assertEquals(second, saved.getValue().getPatient());
    }

    @Test
    void d1592_aBabyOfAnotherDeliveryIsRefused() {
        com.example.hms.model.labor.DeliveryRecord delivery = onTheMothersChart(1);
        babiesOf(delivery, registeredBaby(delivery, 1));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.recordAssessment(patientId,
            NewbornAssessmentRequestDTO.builder().hospitalId(hospitalId).newbornPatientId(UUID.randomUUID())
                .apgarOneMinute(8).build(), recorderId));

        assertEquals("maternity.newborn.notThisDelivery", ex.getMessageKey());
    }

    @Test
    void d1592_onTheBabysOwnChartTheDeliveryItWasRegisteredFromIsLinked() {
        com.example.hms.model.labor.DeliveryRecord delivery = deliveryRecordAt(hospitalId);
        delivery.setBirthDateTime(java.time.LocalDateTime.now().minusHours(5));
        com.example.hms.model.labor.DeliveryNewborn link = com.example.hms.model.labor.DeliveryNewborn.builder()
            .deliveryRecord(delivery).newbornPatient(patient).hospital(hospital).birthOrder(1).build();
        when(deliveryNewbornRepository.findByNewbornPatient_Id(patientId)).thenReturn(Optional.of(link));

        NewbornAssessmentResponseDTO response = service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).apgarOneMinute(8).build(), recorderId);

        assertEquals(delivery.getId(), response.getDeliveryRecordId());
    }
    // -- Provisional (2026-11-14): no Apgar alert, and so no first URGENT notice, for a confirmed stillborn baby --

    private com.example.hms.model.labor.DeliveryRecord registeredAt(int birthOrder, Integer deliveryApgarOneMinute,
                                                                  com.example.hms.enums.BabyOutcome... outcomes) {
        com.example.hms.model.labor.DeliveryRecord delivery = com.example.hms.model.labor.DeliveryRecord.builder()
            .hospital(hospital).numberOfInfants(outcomes.length).apgarOneMinute(deliveryApgarOneMinute)
            .birthDateTime(java.time.LocalDateTime.now().minusMinutes(10)).build();
        delivery.setId(UUID.randomUUID());
        for (int i = 0; i < outcomes.length; i++) {
            delivery.putBabyOutcome(i + 1, outcomes[i]);
        }
        lenient().when(deliveryNewbornRepository.findByNewbornPatient_Id(patientId)).thenReturn(Optional.of(
            com.example.hms.model.labor.DeliveryNewborn.builder().deliveryRecord(delivery).newbornPatient(patient)
                .hospital(hospital).birthOrder(birthOrder).build()));
        return delivery;
    }

    private NewbornAssessmentResponseDTO recordApgarZero() {
        return service.recordAssessment(patientId, NewbornAssessmentRequestDTO.builder()
            .hospitalId(hospitalId).assessmentTime(java.time.LocalDateTime.now().minusMinutes(5))
            .apgarOneMinute(0).apgarFiveMinute(0).build(), recorderId);
    }

    @Test
    void aConfirmedStillbornBabysApgarZeroRaisesNoApgarAlertOrNotice() {
        registeredAt(1, null, com.example.hms.enums.BabyOutcome.STILLBORN);

        NewbornAssessmentResponseDTO response = recordApgarZero();

        assertTrue(response.getAlerts().stream().noneMatch(a -> a.getType() == NewbornAlertType.APGAR));
        assertTrue(response.getFollowUpActions() == null
            || !response.getFollowUpActions().contains(NewbornFollowUpAction.NICU_CONSULT));
        verify(obstetricAlertEscalation, org.mockito.Mockito.never()).raiseAfterCommit(any());
        verify(notificationService, org.mockito.Mockito.never()).created(anyString(), anyString(), any());
    }

    @Test
    void aLiveTwinOfAStillbornBabyStillGetsItsApgarAlertAndNotice() {
        registeredAt(1, null, com.example.hms.enums.BabyOutcome.LIVE, com.example.hms.enums.BabyOutcome.STILLBORN);

        NewbornAssessmentResponseDTO response = recordApgarZero();

        assertEquals(2, response.getAlerts().stream().filter(a -> a.getType() == NewbornAlertType.APGAR
            && a.getSeverity() == NewbornAlertSeverity.URGENT).count());
        verify(obstetricAlertEscalation, times(2)).raiseAfterCommit(any());
    }

    @Test
    void anUncertainStillbirthStillAlerts() {
        // Stillborn, but the delivery's own Apgar shows a sign of life and no baby was born alive: uncertain.
        registeredAt(1, 4, com.example.hms.enums.BabyOutcome.STILLBORN);

        NewbornAssessmentResponseDTO response = recordApgarZero();

        assertEquals(2, response.getAlerts().stream().filter(a -> a.getType() == NewbornAlertType.APGAR
            && a.getSeverity() == NewbornAlertSeverity.URGENT).count());
        verify(obstetricAlertEscalation, times(2)).raiseAfterCommit(any());
    }
    @Test
    void onTheMothersChartAnUnregisteredConfirmedStillbornBabysApgarZeroRaisesNoNotice() {
        com.example.hms.model.labor.DeliveryRecord delivery = onTheMothersChart(1);
        delivery.putBabyOutcome(1, com.example.hms.enums.BabyOutcome.STILLBORN);

        NewbornAssessmentResponseDTO response = recordApgarZero();

        assertTrue(response.getAlerts().stream().noneMatch(a -> a.getType() == NewbornAlertType.APGAR));
        verify(obstetricAlertEscalation, org.mockito.Mockito.never()).raiseAfterCommit(any());
    }

    /** Rex on #1152: an earlier sign of life on the mother's chart for this delivery makes the stillbirth uncertain. */
    @Test
    void onTheMothersChartAnEarlierSignOfLifeKeepsAnUnregisteredStillbornBabysApgarAlert() {
        com.example.hms.model.labor.DeliveryRecord delivery = onTheMothersChart(1);
        delivery.putBabyOutcome(1, com.example.hms.enums.BabyOutcome.STILLBORN);
        when(assessmentRepository.anyUnattributedSignOfLifeRecorded(delivery.getId())).thenReturn(true);

        NewbornAssessmentResponseDTO response = recordApgarZero();

        assertEquals(2, response.getAlerts().stream().filter(a -> a.getType() == NewbornAlertType.APGAR
            && a.getSeverity() == NewbornAlertSeverity.URGENT).count());
        verify(obstetricAlertEscalation, times(2)).raiseAfterCommit(any());
    }
}
