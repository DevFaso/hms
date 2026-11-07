package com.example.hms.service;

import com.example.hms.i18n.EnglishMessageBundles;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.highrisk.HighRiskPregnancyCarePlan;
import com.example.hms.payload.dto.prenatal.PrenatalRescheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleRequestDTO;
import com.example.hms.payload.dto.prenatal.PrenatalScheduleResponseDTO;
import com.example.hms.payload.dto.prenatal.PrenatalVisitRecommendationDTO;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.HighRiskPregnancyCarePlanRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The visit cadence comes from what clinicians recorded, for every caller: every role that may call
 * {@code POST /prenatal/schedule}, box ticked or not, against every recorded state. Thirty weeks on the
 * fixed clock, so the two cadences are the planner's own: weekly from 28 weeks, or every 4, then 2,
 * then 1. What differs by role is only what the response says.
 */
@ExtendWith(EnglishMessageBundles.class)
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PrenatalRecordedRiskCadenceTest {

    private static final LocalDate LMP = LocalDate.of(2023, 9, 4);
    private static final LocalDateTime IN_THIS_PREGNANCY = LMP.plusWeeks(10).atStartOfDay();
    private static final LocalDateTime ON_THE_LMP = LMP.atStartOfDay();
    private static final LocalDateTime BEFORE_THIS_PREGNANCY = LMP.minusDays(1).atTime(23, 59);
    private static final List<Integer> STANDARD = List.of(30, 34, 36, 37, 38, 39, 40);
    private static final List<Integer> DENSER = List.of(30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40);
    private static final String HIGH_RISK_ALERT = "High-risk";

    private static final String RECEPTIONIST = "ROLE_RECEPTIONIST";
    private static final String HOSPITAL_ADMIN = "ROLE_HOSPITAL_ADMIN";
    private static final List<String> READERS =
        List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_SUPER_ADMIN");

    /** Every recorded state of the table, and whether it makes this pregnancy high risk at this hospital. */
    enum Recorded {
        NOTHING(false),
        HISTORY_LOW(false),
        HISTORY_MODERATE(false),
        HISTORY_HIGH(true),
        HISTORY_HIGH_ON_THE_LMP(true),
        HISTORY_HIGH_ANY_CASE(true),
        /** Scored in an earlier pregnancy and not touched since. */
        HISTORY_HIGH_BEFORE_THIS_PREGNANCY(false),
        /** A later edit wrote a version with no score: the HIGH is still the assessment in force. */
        HISTORY_HIGH_THEN_AN_UNSCORED_EDIT(true),
        /** A later score replaces it. */
        HISTORY_HIGH_THEN_SCORED_LOW(false),
        HISTORY_HIGH_THEN_SCORED_MODERATE(false),
        HISTORY_AT_ANOTHER_HOSPITAL(false),
        ACTIVE_PLAN(true),
        ACTIVE_PLAN_WITH_NO_LEVEL(true),
        ACTIVE_PLAN_MODERATE(false),
        PLAN_ON_ANOTHER_PREGNANCY(false),
        PLAN_AT_ANOTHER_HOSPITAL(false),
        HISTORY_LOW_WITH_AN_ACTIVE_PLAN(true);

        final boolean highRisk;

        Recorded(boolean highRisk) {
            this.highRisk = highRisk;
        }
    }

    @Mock private AppointmentRepository appointmentRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private AppointmentService appointmentService;
    @Mock private NotificationService notificationService;
    @Mock private DeliveryRecordRepository deliveryRecordRepository;
    @Mock private LaborEpisodeRepository laborEpisodeRepository;
    @Mock private MaternalHistoryRepository maternalHistoryRepository;
    @Mock private HighRiskPregnancyCarePlanRepository carePlanRepository;

    private PrenatalSchedulingServiceImpl service;
    private final UUID hospitalId = UUID.randomUUID();
    private final UUID otherHospitalId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new PrenatalSchedulingServiceImpl(appointmentRepository, patientRepository, hospitalRepository,
            staffRepository, appointmentService, notificationService,
            Clock.fixed(Instant.parse("2024-04-01T10:00:00Z"), ZoneId.of("UTC")), TestMessageSources.bundles(),
            deliveryRecordRepository, laborEpisodeRepository, maternalHistoryRepository, carePlanRepository);

        Hospital hospital = Hospital.builder().name("Central").code("CENTRAL").build();
        hospital.setId(hospitalId);
        Patient patient = Patient.builder().firstName("Awa").lastName("Kabore")
            .dateOfBirth(LocalDate.of(1994, 2, 8)).build();
        patient.setId(patientId);
        patient.getHospitalRegistrations().add(PatientHospitalRegistration.builder()
            .patient(patient).hospital(hospital).mrn("MRN-1").active(true).build());
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(appointmentRepository.findByHospital_IdAndPatient_Id(hospitalId, patientId)).thenReturn(List.of());
        HospitalContextHolder.setContext(HospitalContext.builder().principalUsername("caller")
            .activeHospitalId(hospitalId).permittedHospitalIds(Set.of(hospitalId)).build());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    private static MaternalHistory history(int version, String risk, LocalDateTime touchedAt) {
        MaternalHistory history = MaternalHistory.builder().versionNumber(version).riskCategory(risk).build();
        history.setUpdatedAt(touchedAt);
        return history;
    }

    private static HighRiskPregnancyCarePlan plan(String level, LocalDateTime createdAt) {
        HighRiskPregnancyCarePlan plan = HighRiskPregnancyCarePlan.builder().riskLevel(level).build();
        plan.setCreatedAt(createdAt);
        return plan;
    }

    /** Stubs by hospital, as the real queries filter: a row elsewhere comes back only if it is asked for. */
    private void given(Recorded recorded) {
        List<MaternalHistory> here = new ArrayList<>();
        List<MaternalHistory> elsewhere = new ArrayList<>();
        HighRiskPregnancyCarePlan planHere = null;
        HighRiskPregnancyCarePlan planElsewhere = null;
        switch (recorded) {
            case NOTHING -> { /* no record at all */ }
            case HISTORY_LOW -> here.add(history(1, "LOW", IN_THIS_PREGNANCY));
            case HISTORY_MODERATE -> here.add(history(1, "MODERATE", IN_THIS_PREGNANCY));
            case HISTORY_HIGH -> here.add(history(1, "HIGH", IN_THIS_PREGNANCY));
            case HISTORY_HIGH_ON_THE_LMP -> here.add(history(1, "HIGH", ON_THE_LMP));
            case HISTORY_HIGH_ANY_CASE -> here.add(history(1, " high ", IN_THIS_PREGNANCY));
            case HISTORY_HIGH_BEFORE_THIS_PREGNANCY -> here.add(history(1, "HIGH", BEFORE_THIS_PREGNANCY));
            case HISTORY_HIGH_THEN_AN_UNSCORED_EDIT -> here.addAll(List.of(
                history(2, null, IN_THIS_PREGNANCY.plusWeeks(1)), history(1, "HIGH", IN_THIS_PREGNANCY)));
            case HISTORY_HIGH_THEN_SCORED_LOW -> here.addAll(List.of(
                history(2, "LOW", IN_THIS_PREGNANCY.plusWeeks(1)), history(1, "HIGH", IN_THIS_PREGNANCY)));
            case HISTORY_HIGH_THEN_SCORED_MODERATE -> here.addAll(List.of(
                history(2, "MODERATE", IN_THIS_PREGNANCY.plusWeeks(1)), history(1, "HIGH", IN_THIS_PREGNANCY)));
            case HISTORY_AT_ANOTHER_HOSPITAL -> elsewhere.add(history(1, "HIGH", IN_THIS_PREGNANCY));
            case ACTIVE_PLAN -> planHere = plan("HIGH", IN_THIS_PREGNANCY);
            case ACTIVE_PLAN_WITH_NO_LEVEL -> planHere = plan(null, IN_THIS_PREGNANCY);
            case ACTIVE_PLAN_MODERATE -> planHere = plan("MODERATE", IN_THIS_PREGNANCY);
            case PLAN_ON_ANOTHER_PREGNANCY -> planHere = plan("HIGH", BEFORE_THIS_PREGNANCY);
            case PLAN_AT_ANOTHER_HOSPITAL -> planElsewhere = plan("HIGH", IN_THIS_PREGNANCY);
            case HISTORY_LOW_WITH_AN_ACTIVE_PLAN -> {
                here.add(history(1, "LOW", IN_THIS_PREGNANCY));
                planHere = plan("HIGH", IN_THIS_PREGNANCY);
            }
        }
        when(maternalHistoryRepository.findByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(
            patientId, Set.of(hospitalId))).thenReturn(here);
        when(maternalHistoryRepository.findByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(
            patientId, Set.of(otherHospitalId))).thenReturn(elsewhere);
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc(
            patientId, Set.of(hospitalId))).thenReturn(Optional.ofNullable(planHere));
        when(carePlanRepository.findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc(
            patientId, Set.of(otherHospitalId))).thenReturn(Optional.ofNullable(planElsewhere));
    }

    private PrenatalScheduleResponseDTO schedule(String role, boolean box) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("caller", "p", role));
        return service.generateSchedule(PrenatalScheduleRequestDTO.builder().patientId(patientId).hospitalId(hospitalId)
            .lastMenstrualPeriodDate(LMP).highRisk(box).build(), Locale.US, "caller");
    }

    private static List<Integer> weeks(PrenatalScheduleResponseDTO response) {
        return response.getRecommendations().stream().map(PrenatalVisitRecommendationDTO::getGestationalWeek).toList();
    }

    private static boolean alerted(PrenatalScheduleResponseDTO response) {
        return response.getAlerts().stream().anyMatch(alert -> alert.contains(HIGH_RISK_ALERT));
    }

    private static List<Arguments> everyCombination() {
        List<String> roles = new ArrayList<>(READERS);
        roles.add(HOSPITAL_ADMIN);
        roles.add(RECEPTIONIST);
        List<Arguments> all = new ArrayList<>();
        for (String role : roles) {
            for (boolean box : List.of(true, false)) {
                for (Recorded recorded : Recorded.values()) {
                    all.add(Arguments.of(role, box, recorded));
                }
            }
        }
        return all;
    }

    @ParameterizedTest(name = "{0}, box {1}, recorded {2}")
    @MethodSource("everyCombination")
    @DisplayName("the cadence follows the record for every caller; the response says only what the role may read")
    void theTable(String role, boolean box, Recorded recorded) throws Exception {
        given(recorded);

        PrenatalScheduleResponseDTO response = schedule(role, box);

        boolean desk = RECEPTIONIST.equals(role);
        boolean countedBox = box && !desk;
        assertThat(weeks(response)).isEqualTo(countedBox || recorded.highRisk ? DENSER : STANDARD);

        Boolean expectedFlag;
        if (desk) {
            expectedFlag = null;
        } else if (READERS.contains(role)) {
            expectedFlag = box || recorded.highRisk;
        } else {
            expectedFlag = box ? Boolean.TRUE : null;
        }
        assertThat(response.getHighRisk()).isEqualTo(expectedFlag);
        assertThat(alerted(response)).isEqualTo(Boolean.TRUE.equals(expectedFlag));
        if (expectedFlag == null) {
            assertThat(new ObjectMapper().findAndRegisterModules().writeValueAsString(response))
                .doesNotContain("highRisk").doesNotContain(HIGH_RISK_ALERT);
        }
    }

    @Test
    @DisplayName("the desk's response differs from a no-risk one by the visits alone")
    void forTheDeskOnlyTheCadenceChanges() {
        given(Recorded.NOTHING);
        PrenatalScheduleResponseDTO noRisk = schedule(RECEPTIONIST, false);
        given(Recorded.HISTORY_HIGH);
        PrenatalScheduleResponseDTO recorded = schedule(RECEPTIONIST, false);

        assertThat(recorded.getRecommendations()).hasSizeGreaterThan(noRisk.getRecommendations().size());
        assertThat(recorded.getAlerts()).isEqualTo(noRisk.getAlerts());
        assertThat(recorded.getHighRisk()).isNull();
        assertThat(recorded.getExistingAppointments()).isEqualTo(noRisk.getExistingAppointments());
        assertThat(recorded.getEstimatedDueDate()).isEqualTo(noRisk.getEstimatedDueDate());
        assertThat(recorded.getCurrentGestationalWeek()).isEqualTo(noRisk.getCurrentGestationalWeek());
        assertThat(recorded.getCompletedContacts()).isEqualTo(noRisk.getCompletedContacts());
        assertThat(recorded.getRecommendations())
            .allSatisfy(visit -> assertThat(visit.getRecommendation() + visit.getNotes()).doesNotContainIgnoringCase("risk"));
    }

    @Test
    @DisplayName("only the requested hospital's records are asked for")
    void noOtherHospitalIsAskedFor() {
        given(Recorded.NOTHING);
        schedule("ROLE_DOCTOR", false);

        verify(maternalHistoryRepository, never())
            .findByPatient_IdAndHospital_IdInOrderByVersionNumberDescRecordedDateDesc(patientId, Set.of(otherHospitalId));
        verify(carePlanRepository, never())
            .findFirstByPatient_IdAndHospital_IdInAndActiveTrueOrderByCreatedAtDesc(patientId, Set.of(otherHospitalId));
        verify(maternalHistoryRepository, never()).findCurrentByPatientId(any());
        verify(carePlanRepository, never()).findFirstByPatient_IdAndActiveTrueOrderByCreatedAtDesc(any());
    }

    @Test
    @DisplayName("moving one booked visit plans no cadence, so it reads no risk and says none")
    void reschedulingReadsNoRisk() {
        com.example.hms.model.Appointment appointment = mock(com.example.hms.model.Appointment.class);
        Hospital hospital = Hospital.builder().build();
        hospital.setId(hospitalId);
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        when(appointment.getId()).thenReturn(UUID.randomUUID());
        when(appointment.getHospital()).thenReturn(hospital);
        when(appointment.getPatient()).thenReturn(patient);
        when(appointmentRepository.findById(any())).thenReturn(Optional.of(appointment));
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("caller", "p", RECEPTIONIST));

        service.reschedulePrenatalAppointment(PrenatalRescheduleRequestDTO.builder()
            .appointmentId(UUID.randomUUID()).newAppointmentDate(LMP.plusWeeks(32))
            .newStartTime(LocalTime.of(9, 0)).build(), Locale.US, "caller");

        verifyNoInteractions(maternalHistoryRepository, carePlanRepository);
    }

    @ParameterizedTest(name = "\"{0}\" reads high: {1}")
    @CsvSource(nullValues = "NULL", value = {
        "NULL,true", "'  ',true", "HIGH,true", "high,true", "Critical,true", "Haut risque,true", "Risque élevé,true",
        "Alto riesgo,true", "Moderate to high,true", "modéré-élevé,true", "Under review,true",
        "LOW,false", "MODERATE,false", "moderate,false", "Risque modéré,false", "Faible,false", "bas,false",
        "Riesgo moderado,false", "Medium,false"})
    @DisplayName("a care plan counts unless its free-text level names something below high and nothing high")
    void aPlansFreeTextLevel(String level, boolean high) {
        assertThat(PrenatalSchedulingServiceImpl.planLevelReadsHigh(level)).isEqualTo(high);
    }
}
