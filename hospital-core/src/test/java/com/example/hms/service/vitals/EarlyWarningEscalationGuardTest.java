package com.example.hms.service.vitals;

import com.example.hms.model.Admission;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import com.example.hms.service.maternity.ObstetricAlertEscalationService.UrgentAlert;
import com.example.hms.service.maternity.ObstetricAlertEscalationWorker;
import com.example.hms.utility.NewsScoreCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.MessageSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QA D3862 (r77): an adult NEWS2 of 15 (HIGH) reached the doctor as a bell only, with nothing to acknowledge and no
 * reminder; the D3304 fix had covered the maternal score alone. The class: every early-warning score that reaches its
 * high or critical band goes through ONE escalation path, {@link ObstetricAlertEscalationService} (a CRITICAL
 * in-basket item, reminders until acknowledged, the escalation row as its record).
 *
 * <p>The scores this platform computes, and the band each one escalates at:
 * <ul>
 *   <li>adult NEWS2 ({@link NewsScoreCalculator}): HIGH;</li>
 *   <li>maternal MEWS ({@link MaternalEarlyWarningService}): medium, high or a RED add-on alert (D3304, tested in
 *       {@code MaternalEarlyWarningServiceTest});</li>
 *   <li>newborn bands on a vital set ({@code NewbornAlertRules}, via {@link VitalSignJudgement}): an URGENT finding.
 *       The newborn assessment's own URGENT findings already go through the path.</li>
 * </ul>
 * No paediatric score exists between the newborn bands and NEWS2 (16 years). A new score class fails
 * {@link #everyScoreClassIsKnownToThisGuard} until it is wired and listed here.
 */
class EarlyWarningEscalationGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** Every class that computes an early-warning score or band, and how it reaches the escalation path. */
    private static final Map<String, String> SCORE_CLASSES = Map.of(
        "utility/NewsScoreCalculator.java", "News2EscalationService files HIGH as SOURCE_NEWS2",
        "service/vitals/MaternalEarlyWarning.java", "MaternalEarlyWarningService files medium/high/red as SOURCE_VITAL_SIGN",
        "service/vitals/MaternalEarlyWarningRules.java", "the maternal score's thresholds (see MaternalEarlyWarning)",
        "service/vitals/MaternalEarlyWarningService.java", "files medium/high/red as SOURCE_VITAL_SIGN",
        "service/maternity/NewbornAlertRules.java",
        "URGENT: NewbornAssessmentServiceImpl as SOURCE_NEWBORN, VitalSignJudgement as SOURCE_NEWBORN_VITAL_SIGN");

    private final AdmissionRepository admissionRepository = mock(AdmissionRepository.class);
    private final NotificationService notificationService = mock(NotificationService.class);
    private final ObstetricAlertEscalationService urgentAlerts = mock(ObstetricAlertEscalationService.class);
    private final MessageSource bundles = com.example.hms.i18n.TestMessageSources.bundles();
    private final News2EscalationService news2 = new News2EscalationService(admissionRepository, notificationService);
    private final UUID hospitalId = UUID.randomUUID();
    private Patient adult;

    @BeforeEach
    void setUp() {
        news2.setUrgentAlerts(urgentAlerts);
        news2.setLocalizer(new NotificationLocalizer(bundles));
        adult = new Patient();
        adult.setId(UUID.randomUUID());
        adult.setFirstName("Ousmane");
        adult.setLastName("Test");
        adult.setDateOfBirth(LocalDate.now().minusYears(45));
        User doctorUser = new User();
        doctorUser.setUsername("dr_a");
        Staff doctor = new Staff();
        doctor.setUser(doctorUser);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Admission stay = new Admission();
        stay.setHospital(hospital);
        stay.setAttendingPhysician(doctor);
        when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(adult.getId()), eq(hospitalId), any()))
            .thenReturn(List.of(stay));
    }

    /** NEWS2 15 like the r77 set: RR 32 (3), SpO2 85 on air (3), BP 82 (3), HR 125 (2), T 38.5 (1), new confusion (3). */
    private PatientVitalSign news2Of15(UUID id) {
        PatientVitalSign vital = PatientVitalSign.builder()
            .patient(adult).recordedAt(LocalDateTime.now())
            .respiratoryRateBpm(32).spo2Percent(85).onOxygen(false).systolicBpMmHg(82).heartRateBpm(125)
            .temperatureCelsius(38.5).consciousnessLevel(com.example.hms.enums.ConsciousnessLevel.NEW_CONFUSION)
            .build();
        vital.setId(id);
        User nurseUser = new User();
        nurseUser.setId(UUID.randomUUID());
        nurseUser.setUsername("nurse_a");
        Staff nurse = new Staff();
        nurse.setUser(nurseUser);
        vital.setRecordedByStaff(nurse);
        return vital;
    }

    @Test
    void aHighAdultNews2IsFiledAsAnUrgentAlertToAcknowledge() {
        UUID vitalId = UUID.randomUUID();
        PatientVitalSign vital = news2Of15(vitalId);

        NewsScoreCalculator.NewsScoreResult score =
            news2.escalateIfNeeded(vital, adult.getId(), hospitalId, "nurse_a", "dr_visit");

        assertThat(score.riskBand()).isEqualTo(NewsScoreCalculator.RiskBand.HIGH);
        ArgumentCaptor<UrgentAlert> filed = ArgumentCaptor.forClass(UrgentAlert.class);
        verify(urgentAlerts).raiseAfterCommit(filed.capture());
        UrgentAlert alert = filed.getValue();
        assertThat(alert.sourceType()).isEqualTo(ObstetricAlertEscalationService.SOURCE_NEWS2);
        assertThat(alert.sourceId()).isEqualTo(vitalId);
        assertThat(alert.alertType()).isEqualTo(News2EscalationService.ALERT_TYPE_HIGH);
        assertThat(alert.hospitalId()).isEqualTo(hospitalId);
        assertThat(alert.patientId()).isEqualTo(adult.getId());
        // The recorder is the documenter: she cannot acknowledge her own alert.
        assertThat(alert.documentedByUserId()).isEqualTo(vital.getRecordedByStaff().getUser().getId());
        // Those the bell already reached: the recorder, the stay's attending, the visit's clinician.
        assertThat(alert.alreadyNotified()).containsExactly("nurse_a", "dr_a", "dr_visit");
        assertThat(alert.alertMessageKey()).isEqualTo("news2.alert.msg.NEWS2_HIGH");
        assertThat(alert.alertMessage()).contains("NEWS2 15").contains("ÉLEVÉ");
        assertThat(alert.notificationText()).startsWith("Alerte NEWS2 (NEWS2, élevé) pour Ousmane Test");
    }

    @Test
    void aMediumAdultNews2StaysABellNotice() {
        // NEWS2 5: RR 22 (2), HR 115 (2), T 38.5 (1); the rest normal.
        PatientVitalSign medium = PatientVitalSign.builder()
            .patient(adult).recordedAt(LocalDateTime.now())
            .respiratoryRateBpm(22).spo2Percent(97).onOxygen(false).systolicBpMmHg(125).heartRateBpm(115)
            .temperatureCelsius(38.5).consciousnessLevel(com.example.hms.enums.ConsciousnessLevel.ALERT)
            .build();
        medium.setId(UUID.randomUUID());

        NewsScoreCalculator.NewsScoreResult score = news2.escalateIfNeeded(medium, adult.getId(), hospitalId, "nurse_a");

        assertThat(score.riskBand()).isEqualTo(NewsScoreCalculator.RiskBand.MEDIUM);
        verify(notificationService, atLeastOnce()).createNotification(eq("news2.escalation.medium"), any(), eq("dr_a"), any());
        verify(urgentAlerts, never()).raiseAfterCommit(any());
    }

    @Test
    void aChildsVitalSetFilesNothingUnderTheAdultScore() {
        adult.setDateOfBirth(LocalDate.now().minusYears(6));
        news2.escalateIfNeeded(news2Of15(UUID.randomUUID()), adult.getId(), hospitalId, "nurse_a");
        verify(urgentAlerts, never()).raiseAfterCommit(any());
    }

    @Test
    void anUrgentNewbornFindingOnAVitalSetIsFiledAsAnUrgentAlertToAcknowledge() {
        News2EscalationService recipients = mock(News2EscalationService.class);
        when(recipients.recipients(any(), any(), any(), any())).thenReturn(Set.of("nurse_a", "dr_a"));
        VitalSignJudgement judgement = new VitalSignJudgement(
            mock(com.example.hms.repository.NewbornAssessmentRepository.class), recipients, notificationService, bundles,
            mock(com.example.hms.repository.DeliveryNewbornRepository.class), null);
        judgement.setUrgentAlerts(urgentAlerts);
        Patient baby = new Patient();
        baby.setId(UUID.randomUUID());
        baby.setFirstName("Bebe");
        baby.setLastName("Test");
        baby.setDateOfBirth(LocalDate.now().minusDays(2));
        PatientVitalSign vital = PatientVitalSign.builder().patient(baby).recordedAt(LocalDateTime.now())
            .temperatureCelsius(35.0).heartRateBpm(190).respiratoryRateBpm(72).spo2Percent(88).build();
        vital.setId(UUID.randomUUID());

        VitalSignJudgement.Judgement judged = judgement.judge(vital);
        judgement.escalate(vital, judged, baby.getId(), hospitalId, "nurse_a", "dr_a");

        assertThat(judged.newborn()).isTrue();
        ArgumentCaptor<UrgentAlert> filed = ArgumentCaptor.forClass(UrgentAlert.class);
        verify(urgentAlerts, atLeastOnce()).raiseAfterCommit(filed.capture());
        assertThat(filed.getAllValues()).allSatisfy(alert -> {
            assertThat(alert.sourceType()).isEqualTo(ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN);
            assertThat(alert.sourceId()).isEqualTo(vital.getId());
            // QA D4105: the rule's sentence travels inside the sentence that adds the measured value.
            assertThat(alert.alertMessageKey())
                .isEqualTo(com.example.hms.service.escalation.UrgentAlertReading.WITH_READING_KEY);
            assertThat(com.example.hms.service.escalation.UrgentAlertReading.ruleKey(alert.alertMessageKey(), alert.alertMessageArgs())).startsWith("newborn.alert.msg.");
            assertThat(alert.alreadyNotified()).contains("dr_a");
        });
    }

    @Test
    void everyVitalSetEarlyWarningSourceIsAcceptedAndHasItsNoticeInEnglishAndFrench() {
        Set<String> vitalSetSources = ObstetricAlertEscalationService.VITAL_SET_EARLY_WARNING_SOURCES;
        // K4: an adult's urgent hypertension is read on a vital set too, and repeats like the scores do.
        assertThat(vitalSetSources).containsExactlyInAnyOrder(
            ObstetricAlertEscalationService.SOURCE_NEWS2, ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN,
            ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION);
        List<String> missing = new ArrayList<>();
        for (String source : List.of(ObstetricAlertEscalationService.SOURCE_NEWS2,
                ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN, ObstetricAlertEscalationService.SOURCE_VITAL_SIGN)) {
            assertThat(ObstetricAlertEscalationService.SOURCE_TYPES).contains(source);
            String key = ObstetricAlertEscalationWorker.firstNoticeKey(source);
            for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH)) {
                if (bundles.getMessage(key, new Object[]{"a", "b", "c"}, null, locale) == null) {
                    missing.add(source + ": " + key + " in " + locale);
                }
            }
        }
        for (String key : List.of("news2.alert.type.NEWS2_HIGH", "news2.alert.msg.NEWS2_HIGH")) {
            for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH)) {
                if (bundles.getMessage(key, new Object[]{7, 15}, null, locale) == null) {
                    missing.add(key + " in " + locale);
                }
            }
        }
        assertThat(missing).isEmpty();
        assertThat(ObstetricAlertEscalationWorker.firstNoticeKey(ObstetricAlertEscalationService.SOURCE_NEWS2))
            .as("an adult's NEWS2 is never headed as a labour alert").isEqualTo("news2.alert.notification");
    }

    @Test
    void everyScoreClassIsKnownToThisGuard() throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            files.filter(p -> p.toString().endsWith(".java"))
                .map(p -> MAIN.relativize(p).toString().replace('\\', '/'))
                .filter(name -> name.matches(".*/[^/]*(EarlyWarning|ScoreCalculator|WarningScore|Pews|PEWS|AlertRules)[^/]*\\.java"))
                .forEach(found::add);
        }
        assertThat(found).as("a new early-warning score: wire its high band to ObstetricAlertEscalationService, "
            + "test it here and list it in SCORE_CLASSES").containsExactlyInAnyOrderElementsOf(SCORE_CLASSES.keySet());
    }
}
