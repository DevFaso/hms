package com.example.hms.service.pro;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.pro.ProResponseSource;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.ProResponseMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.pro.ProInstrument;
import com.example.hms.model.pro.ProInstrumentItem;
import com.example.hms.model.pro.ProInstrumentOption;
import com.example.hms.model.pro.ProResponse;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.pro.ProResponseCreateDTO;
import com.example.hms.payload.dto.pro.ProResponseDTO;
import com.example.hms.payload.dto.pro.ProScreeningSummaryDTO;
import com.example.hms.payload.dto.pro.ProSelfReportDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PostpartumCarePlanRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.pro.ProResponseRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Pageable;

@ExtendWith(MockitoExtension.class)
class ProResponseServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 4, 10, 0);

    @Mock private ProResponseRepository responseRepository;
    @Mock private ProInstrumentService instrumentService;
    @Mock private ProScreeningEscalationService escalationService;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PostpartumCarePlanRepository carePlanRepository;
    @Mock private AuditEventLogService auditService;
    @Mock private RoleValidator roleValidator;

    private ProResponseService service;

    private UUID hospitalId;
    private UUID patientId;
    private UUID userId;
    private Hospital hospital;
    private Patient patient;
    private ProInstrument instrument;
    private PostpartumCarePlan plan;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        // Real mapper: the answers round-trip and the two views are part of the contract.
        service = new ProResponseService(responseRepository, instrumentService, escalationService,
            patientRepository, hospitalRepository, staffRepository, carePlanRepository,
            new ProResponseMapper(new ObjectMapper()), auditService, roleValidator, clock);

        hospitalId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        userId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("General");

        patient = new Patient();
        patient.setId(patientId);
        patient.setFirstName("Awa");
        patient.setLastName("Traore");
        PatientHospitalRegistration reg = new PatientHospitalRegistration();
        reg.setHospital(hospital);
        reg.setActive(true);
        Set<PatientHospitalRegistration> regs = new HashSet<>();
        regs.add(reg);
        patient.setHospitalRegistrations(regs);

        // Two items scored 0..1, item 2 critical, threshold 2 (made-up fixture, not an instrument).
        instrument = ProInstrument.builder().code("EPDS").name("Fixture").maxScore(2)
            .positiveThreshold(2).criticalItemNo(2).build();
        instrument.setId(UUID.randomUUID());
        for (int itemNo = 1; itemNo <= 2; itemNo++) {
            ProInstrumentItem item = ProInstrumentItem.builder().instrument(instrument).itemNo(itemNo).build();
            item.getOptions().add(ProInstrumentOption.builder().item(item).optionNo(1).score(0).build());
            item.getOptions().add(ProInstrumentOption.builder().item(item).optionNo(2).score(1).build());
            instrument.getItems().add(item);
        }

        plan = PostpartumCarePlan.builder().patient(patient).hospital(hospital).build();
        plan.setId(UUID.randomUUID());

        lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        lenient().when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        lenient().when(instrumentService.requireActive("EPDS")).thenReturn(instrument);
        lenient().when(roleValidator.getCurrentUserId()).thenReturn(userId);
        lenient().when(responseRepository.saveAndFlush(any(ProResponse.class))).thenAnswer(inv -> {
            ProResponse r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId(UUID.randomUUID());
            }
            return r;
        });
    }

    private void clinicianAt(UUID activeHospital) {
        when(roleValidator.requireActiveHospitalId()).thenReturn(activeHospital);
    }

    private void openPlan() {
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(
            patientId, hospitalId)).thenReturn(Optional.of(plan));
    }

    private void noPlan() {
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdAndActiveTrueOrderByCreatedAtDesc(
            patientId, hospitalId)).thenReturn(Optional.empty());
    }

    /** The /me surface resolves the plan across hospitals, not against a caller tenant. */
    private void openPlanForSelf(PostpartumCarePlan... plans) {
        when(carePlanRepository.findByPatient_IdAndActiveTrue(patientId)).thenReturn(List.of(plans));
    }

    private static ProResponseCreateDTO answers(Map<Integer, Integer> answers) {
        return ProResponseCreateDTO.builder().instrumentCode("EPDS").answers(answers).build();
    }

    private ProResponse stored(boolean critical, boolean acknowledged) {
        ProResponse r = ProResponse.builder()
            .instrument(instrument).patient(patient).hospital(hospital).carePlan(plan)
            .source(ProResponseSource.STAFF_ADMINISTERED)
            .administeredAt(NOW.minusHours(1))
            .answers("{\"1\":2,\"2\":2}")
            .totalScore(2).maxScore(2).instrumentVersion("fixture-1")
            .answeredItems(2).totalItems(2).complete(true)
            .screenPositive(true).criticalItemScore(critical ? 1 : 0).criticalItemPositive(critical)
            .build();
        r.setId(UUID.randomUUID());
        if (acknowledged) {
            r.setAcknowledgedAt(NOW.minusMinutes(10));
            r.setAcknowledgedByDisplay("Dr Zongo");
        }
        return r;
    }

    // ── record ────────────────────────────────────────────────────────

    @Nested
    class Record {

        @Test
        void scoresLinksThePlanAndRaisesTheReferralFlagOnAPositiveScreen() {
            clinicianAt(hospitalId);
            openPlan();

            ProResponseDTO dto = service.record(patientId, answers(Map.of(1, 2, 2, 1)));

            assertThat(dto.getTotalScore()).isEqualTo(1);
            assertThat(dto.getMaxScore()).isEqualTo(2);
            assertThat(dto.isScreenPositive()).isFalse();
            assertThat(dto.getCarePlanId()).isEqualTo(plan.getId());
            assertThat(dto.getSource()).isEqualTo(ProResponseSource.STAFF_ADMINISTERED);
            assertThat(dto.getRecordedByUserId()).isEqualTo(userId);
            assertThat(dto.getAnswers()).containsExactly(Map.entry(1, 2), Map.entry(2, 1));
            assertThat(dto.getAdministeredAt()).isEqualTo(NOW);
            // Negative screen: the referral flag is left alone.
            assertThat(plan.isMentalHealthReferralOutstanding()).isFalse();
            verify(carePlanRepository, never()).save(any());

            ProResponseDTO positive = service.record(patientId, answers(Map.of(1, 2, 2, 2)));

            assertThat(positive.isScreenPositive()).isTrue();
            assertThat(positive.isCriticalItemPositive()).isTrue();
            assertThat(plan.isMentalHealthReferralOutstanding()).isTrue();
            verify(carePlanRepository).save(plan);
        }

        @Test
        void auditCarriesNeitherAnswersNorScoreAndTheCareTeamIsNotified() {
            clinicianAt(hospitalId);
            openPlan();

            service.record(patientId, answers(Map.of(1, 2, 2, 2)));

            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditService).logEvent(audit.capture());
            assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PRO_RESPONSE_RECORDED);
            assertThat(audit.getValue().getEventDescription())
                .isEqualTo("PRO response recorded (EPDS, STAFF_ADMINISTERED)");
            assertThat(audit.getValue().getPatientId()).isEqualTo(patientId);
            ArgumentCaptor<ProResponse> notified = ArgumentCaptor.forClass(ProResponse.class);
            verify(escalationService).notifyOnRecord(notified.capture());
            assertThat(notified.getValue().isCriticalItemPositive()).isTrue();
        }

        @Test
        void auditFailureDoesNotUndoTheRecord() {
            clinicianAt(hospitalId);
            openPlan();
            when(auditService.logEvent(any())).thenThrow(new IllegalStateException("audit sink down"));

            ProResponseDTO dto = service.record(patientId, answers(Map.of(1, 1, 2, 1)));

            assertThat(dto.getId()).isNotNull();
            verify(escalationService).notifyOnRecord(any());
        }

        @Test
        void recordsWithoutAPlanWhenNoneIsOpen() {
            clinicianAt(hospitalId);
            noPlan();

            ProResponseDTO dto = service.record(patientId, answers(Map.of(1, 2, 2, 2)));

            assertThat(dto.getCarePlanId()).isNull();
            verify(carePlanRepository, never()).save(any());
        }

        @Test
        void refusesAnAdministrationTimeInTheFuture() {
            clinicianAt(hospitalId);
            openPlan();
            ProResponseCreateDTO dto = answers(Map.of(1, 1, 2, 1));
            dto.setAdministeredAt(NOW.plusMinutes(6));

            assertThatThrownBy(() -> service.record(patientId, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("cannot be in the future");
            verify(responseRepository, never()).saveAndFlush(any());
        }

        @Test
        void explicitHospitalWinsOverTheCallersActiveOne() {
            ProResponseCreateDTO dto = answers(Map.of(1, 1, 2, 1));
            dto.setHospitalId(hospitalId);
            noPlan();

            ProResponseDTO saved = service.record(patientId, dto);

            assertThat(saved.getHospitalId()).isEqualTo(hospitalId);
            verify(roleValidator, never()).requireActiveHospitalId();
        }

        @Test
        void superAdminInGlobalViewFallsBackToThePatientsPrimaryHospital() {
            clinicianAt(null);
            patient.setHospitalId(hospitalId);
            noPlan();

            ProResponseDTO saved = service.record(patientId, answers(Map.of(1, 1, 2, 1)));

            assertThat(saved.getHospitalId()).isEqualTo(hospitalId);
        }

        @Test
        void foreignPatientCollapsesToTheSameNotFoundAsAMissingOne() {
            UUID otherHospital = UUID.randomUUID();
            clinicianAt(otherHospital);
            UUID missing = UUID.randomUUID();
            when(patientRepository.findById(missing)).thenReturn(Optional.empty());

            ProResponseCreateDTO dto = answers(Map.of(1, 1, 2, 1));
            Throwable foreign = catchThrowable(() -> service.record(patientId, dto));
            Throwable absent = catchThrowable(() -> service.record(missing, dto));

            assertThat(foreign).isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("patient.notfound");
            // An attacker probing ids must not be able to tell "exists elsewhere" from "does not exist".
            assertThat(absent).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage(foreign.getMessage());
            verify(responseRepository, never()).saveAndFlush(any());
        }
    }

    // ── history ───────────────────────────────────────────────────────

    @Test
    void historyDefaultsToTheEpdsAndClampsTheLimit() {
        clinicianAt(hospitalId);
        when(responseRepository.findByPatient_IdAndHospital_IdAndInstrument_CodeOrderByAdministeredAtDesc(
            eq(patientId), eq(hospitalId), eq("EPDS"), any(Pageable.class)))
            .thenReturn(List.of(stored(false, false)));

        List<ProResponseDTO> history = service.history(patientId, null, null, 5000);

        assertThat(history).hasSize(1);
        assertThat(history.get(0).getAnswers()).containsEntry(1, 2);
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
        verify(responseRepository).findByPatient_IdAndHospital_IdAndInstrument_CodeOrderByAdministeredAtDesc(
            eq(patientId), eq(hospitalId), eq("EPDS"), page.capture());
        assertThat(page.getValue().getPageSize()).isEqualTo(ProResponseService.MAX_HISTORY);
    }

    // ── acknowledge ───────────────────────────────────────────────────

    @Nested
    class Acknowledge {

        @Test
        void stampsWhoActedAndWhatWasDone() {
            clinicianAt(hospitalId);
            ProResponse response = stored(true, false);
            when(responseRepository.findByIdAndPatient_IdAndHospital_Id(response.getId(), patientId, hospitalId))
                .thenReturn(Optional.of(response));
            User user = new User();
            user.setFirstName("Mariam");
            user.setLastName("Zongo");
            Staff staff = Staff.builder().build();
            staff.setUser(user);
            when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId)).thenReturn(Optional.of(staff));

            ProResponseDTO dto = service.acknowledge(patientId, response.getId(), null,
                "  Called the mother; referred to psychiatry.  ");

            assertThat(dto.getAcknowledgedAt()).isEqualTo(NOW);
            assertThat(dto.getAcknowledgedByDisplay()).isEqualTo("Mariam Zongo");
            assertThat(dto.getAcknowledgementNote()).isEqualTo("Called the mother; referred to psychiatry.");
            assertThat(response.getAcknowledgedByUserId()).isEqualTo(userId);
            ArgumentCaptor<AuditEventRequestDTO> audit = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
            verify(auditService).logEvent(audit.capture());
            assertThat(audit.getValue().getEventType()).isEqualTo(AuditEventType.PRO_ALERT_ACKNOWLEDGED);
            assertThat(audit.getValue().getEventDescription()).doesNotContain("psychiatry");
        }

        @Test
        void onlyACriticalResponseHasAnythingToAcknowledge() {
            clinicianAt(hospitalId);
            ProResponse response = stored(false, false);
            when(responseRepository.findByIdAndPatient_IdAndHospital_Id(response.getId(), patientId, hospitalId))
                .thenReturn(Optional.of(response));

            assertThatThrownBy(() -> service.acknowledge(patientId, response.getId(), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Only a safety-item-positive response");
        }

        @Test
        void aSecondAcknowledgementIsRefusedNotOverwritten() {
            clinicianAt(hospitalId);
            ProResponse response = stored(true, true);
            when(responseRepository.findByIdAndPatient_IdAndHospital_Id(response.getId(), patientId, hospitalId))
                .thenReturn(Optional.of(response));

            assertThatThrownBy(() -> service.acknowledge(patientId, response.getId(), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("already acknowledged by Dr Zongo");
            verify(responseRepository, never()).saveAndFlush(any());
        }

        @Test
        void aConcurrentAcknowledgementSurfacesAsReloadAndRetry() {
            clinicianAt(hospitalId);
            ProResponse response = stored(true, false);
            when(responseRepository.findByIdAndPatient_IdAndHospital_Id(response.getId(), patientId, hospitalId))
                .thenReturn(Optional.of(response));
            when(staffRepository.findByUserIdAndHospitalId(userId, hospitalId)).thenReturn(Optional.empty());
            when(responseRepository.saveAndFlush(response))
                .thenThrow(new OptimisticLockingFailureException("stale"));

            assertThatThrownBy(() -> service.acknowledge(patientId, response.getId(), null, null))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("reload and retry");
        }

        @Test
        void aResponseFromAnotherHospitalIsNotFound() {
            clinicianAt(hospitalId);
            UUID responseId = UUID.randomUUID();
            when(responseRepository.findByIdAndPatient_IdAndHospital_Id(responseId, patientId, hospitalId))
                .thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.acknowledge(patientId, responseId, null, null))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Screening response not found.");
        }
    }

    // ── summaryForCarePlan ────────────────────────────────────────────

    @Nested
    class SummaryForCarePlan {

        @Test
        void dueWhenTheInstrumentIsLoadedAndTheOpenPlanHasNoScreenYet() {
            when(instrumentService.isAvailable("EPDS")).thenReturn(true);
            when(responseRepository.findFirstByCarePlan_IdAndInstrument_CodeOrderByAdministeredAtDesc(
                plan.getId(), "EPDS")).thenReturn(Optional.empty());

            ProScreeningSummaryDTO summary = service.summaryForCarePlan(plan);

            assertThat(summary.isInstrumentAvailable()).isTrue();
            assertThat(summary.isDue()).isTrue();
            assertThat(summary.getLastResponseId()).isNull();
        }

        @Test
        void notDueWhenTheInstrumentIsNotLoaded() {
            when(instrumentService.isAvailable("EPDS")).thenReturn(false);
            when(responseRepository.findFirstByCarePlan_IdAndInstrument_CodeOrderByAdministeredAtDesc(
                plan.getId(), "EPDS")).thenReturn(Optional.empty());

            ProScreeningSummaryDTO summary = service.summaryForCarePlan(plan);

            assertThat(summary.isInstrumentAvailable()).isFalse();
            assertThat(summary.isDue()).isFalse();
        }

        @Test
        void notDueOnAClosedPlan() {
            when(instrumentService.isAvailable("EPDS")).thenReturn(true);
            plan.setActive(false);
            when(responseRepository.findFirstByCarePlan_IdAndInstrument_CodeOrderByAdministeredAtDesc(
                plan.getId(), "EPDS")).thenReturn(Optional.empty());

            assertThat(service.summaryForCarePlan(plan).isDue()).isFalse();
        }

        @Test
        void carriesTheLastResultAndWhetherTheAlertIsStillOpen() {
            when(instrumentService.isAvailable("EPDS")).thenReturn(true);
            ProResponse last = stored(true, false);
            when(responseRepository.findFirstByCarePlan_IdAndInstrument_CodeOrderByAdministeredAtDesc(
                plan.getId(), "EPDS")).thenReturn(Optional.of(last));

            ProScreeningSummaryDTO summary = service.summaryForCarePlan(plan);

            assertThat(summary.isDue()).isFalse();
            assertThat(summary.getLastResponseId()).isEqualTo(last.getId());
            assertThat(summary.getLastTotalScore()).isEqualTo(2);
            assertThat(summary.getMaxScore()).isEqualTo(2);
            assertThat(summary.getLastScreenPositive()).isTrue();
            assertThat(summary.getLastCriticalItemPositive()).isTrue();
            assertThat(summary.isEscalationOpen()).isTrue();
        }

        @Test
        void copesWithNoPlan() {
            when(instrumentService.isAvailable("EPDS")).thenReturn(true);

            ProScreeningSummaryDTO summary = service.summaryForCarePlan(null);

            assertThat(summary.isDue()).isFalse();
            verify(responseRepository, never())
                .findFirstByCarePlan_IdAndInstrument_CodeOrderByAdministeredAtDesc(any(), any());
        }
    }

    // ── patient self-service ──────────────────────────────────────────

    @Nested
    class SelfService {

        @Test
        void overviewOffersInstrumentsOnlyWhileAPlanIsOpenAndNeverShowsAScore() {
            openPlanForSelf(plan);
            when(instrumentService.listActive()).thenReturn(List.of(instrument));
            when(instrumentService.languagesOf(instrument)).thenReturn(List.of("en", "fr"));
            ProResponse notified = stored(true, false);
            notified.setNotifiedAt(NOW.minusMinutes(59));
            when(responseRepository.findByPatient_IdOrderByAdministeredAtDesc(eq(patientId), any(Pageable.class)))
                .thenReturn(List.of(notified));

            ProSelfReportDTO overview = service.overviewForSelf(patient);

            assertThat(overview.getAvailable()).hasSize(1);
            assertThat(overview.getAvailable().get(0).getCode()).isEqualTo("EPDS");
            assertThat(overview.getAvailable().get(0).getLanguages()).containsExactly("en", "fr");
            assertThat(overview.getHistory()).hasSize(1);
            ProSelfReportDTO.Entry entry = overview.getHistory().get(0);
            assertThat(entry.isFollowUpPlanned()).isTrue();
            assertThat(entry.isCareTeamAlerted()).isTrue();
        }

        @Test
        void careTeamAlertedIsAPromiseNotAnInferenceFromTheScore() {
            openPlanForSelf();
            // Critical answer, but nobody could be notified: the patient must not be told somebody was.
            when(responseRepository.findByPatient_IdOrderByAdministeredAtDesc(eq(patientId), any(Pageable.class)))
                .thenReturn(List.of(stored(true, false)));

            ProSelfReportDTO overview = service.overviewForSelf(patient);

            assertThat(overview.getHistory().get(0).isCareTeamAlerted()).isFalse();
        }

        @Test
        void overviewOffersNothingWithoutAnOpenPlanButStillShowsHistory() {
            openPlanForSelf();
            when(responseRepository.findByPatient_IdOrderByAdministeredAtDesc(eq(patientId), any(Pageable.class)))
                .thenReturn(List.of());

            ProSelfReportDTO overview = service.overviewForSelf(patient);

            assertThat(overview.getAvailable()).isEmpty();
            assertThat(overview.getHistory()).isEmpty();
            verify(instrumentService, never()).listActive();
        }

        @Test
        void selfReportIsRecordedAsPatientReportedWithNoRecorder() {
            openPlanForSelf(plan);
            openPlan();

            ProSelfReportDTO.Entry entry = service.recordForSelf(patient, answers(Map.of(1, 2, 2, 1)));

            assertThat(entry.getId()).isNotNull();
            assertThat(entry.isFollowUpPlanned()).isFalse();
            ArgumentCaptor<ProResponse> saved = ArgumentCaptor.forClass(ProResponse.class);
            verify(responseRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getSource()).isEqualTo(ProResponseSource.PATIENT_REPORTED);
            assertThat(saved.getValue().getRecordedByUserId()).isNull();
            assertThat(saved.getValue().getCarePlan()).isSameAs(plan);
            assertThat(saved.getValue().getHospital()).isSameAs(hospital);
            assertThat(saved.getValue().getMaxScore()).isEqualTo(2);
            verify(escalationService).notifyOnRecord(saved.getValue());
        }

        @Test
        void selfReportTakesNeitherAHospitalNorATimeFromThePatient() {
            openPlanForSelf(plan);
            openPlan();
            ProResponseCreateDTO dto = answers(Map.of(1, 2, 2, 2));
            dto.setHospitalId(UUID.randomUUID());
            dto.setAdministeredAt(NOW.minusDays(3));

            service.recordForSelf(patient, dto);

            ArgumentCaptor<ProResponse> saved = ArgumentCaptor.forClass(ProResponse.class);
            verify(responseRepository).saveAndFlush(saved.capture());
            // The open plan picks the hospital; the server clock stamps the time.
            assertThat(saved.getValue().getHospital()).isSameAs(hospital);
            assertThat(saved.getValue().getAdministeredAt()).isEqualTo(NOW);
        }

        @Test
        void thePlanPicksTheHospitalNotThePatientsPrimaryOne() {
            // First registered at a health post; the postpartum plan lives at the district hospital.
            patient.setHospitalId(UUID.randomUUID());
            openPlanForSelf(plan);
            openPlan();

            service.recordForSelf(patient, answers(Map.of(1, 1, 2, 1)));

            ArgumentCaptor<ProResponse> saved = ArgumentCaptor.forClass(ProResponse.class);
            verify(responseRepository).saveAndFlush(saved.capture());
            assertThat(saved.getValue().getHospital()).isSameAs(hospital);
        }

        @Test
        void aPlanAtAHospitalThePatientIsNotRegisteredWithDoesNotCount() {
            Hospital elsewhere = new Hospital();
            elsewhere.setId(UUID.randomUUID());
            PostpartumCarePlan foreign = PostpartumCarePlan.builder().patient(patient).hospital(elsewhere).build();
            foreign.setId(UUID.randomUUID());
            openPlanForSelf(foreign);
            ProResponseCreateDTO dto = answers(Map.of(1, 1, 2, 1));

            assertThatThrownBy(() -> service.recordForSelf(patient, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No screening is open for you");
            verify(responseRepository, never()).saveAndFlush(any());
        }

        @Test
        void selfReportIsRefusedWithoutAnOpenPlan() {
            openPlanForSelf();
            ProResponseCreateDTO dto = answers(Map.of(1, 1, 2, 1));

            assertThatThrownBy(() -> service.recordForSelf(patient, dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("No screening is open for you");
            verify(responseRepository, never()).saveAndFlush(any());
        }
    }
}
