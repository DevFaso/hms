package com.example.hms.service.vitals;

import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.payload.dto.NewbornVitalAlertDTO;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.service.NotificationService;
import com.example.hms.service.i18n.NotificationLocalizer;
import com.example.hms.service.maternity.NewbornAlertRules;
import com.example.hms.service.maternity.ObstetricAlertEscalationWorker;
import com.example.hms.utility.NewsScoreCalculator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * QA D1564: the one judgement of a recorded vital set, for every path that stores one (nurse
 * station, triage, the vitals API and the patient portal behind it). Before, the nurse station
 * judged a 4-day-old with adult limits (HR 80, 35.0 °C and 37.8 °C saved as not significant),
 * triage judged nothing, and only maternity's newborn assessment knew the newborn bands.
 *
 * <p>A newborn (under {@value #NEWBORN_BELOW_DAYS} days from the date of birth) is judged with
 * {@link NewbornAlertRules}, the newborn assessment's own rules: no number is copied here. Any
 * URGENT or CAUTION finding marks the set clinically significant, and each URGENT finding notifies
 * the recorder, the stay's responsible physician and the visit's clinician with the newborn
 * assessment's own notice. Nothing here ever refuses a reading (house rule: a newborn's values
 * warn and escalate, they never block saving).
 *
 * <p>PROVISIONAL: the newborn bands are the user-supplied sourced values held in
 * {@link NewbornAlertRules}; they await the neonatal lead's approval before they may be called
 * clinically approved.
 *
 * <p>Every other age keeps the rules it had: the nurse station's per-vital limits and a NEWS2
 * total of 5 or more (NEWS2 itself only applies from 16 years).
 *
 * <p>QA D1890 and r54 (PROVISIONAL, 2026-11-14/15, pending the obstetric and clinical leads'
 * approval): a severe blood pressure under the shared rule ({@link SevereHypertension}, the rule the
 * postpartum observation and the BPA panel also use) marks the set significant. The obstetric rule
 * (pregnancy, 42 days after a birth) comes back as an URGENT {@code postpartumAlerts} warning and
 * notifies the same people; the adult rule comes back in {@code hypertensionAlerts}, a WARNING that
 * asks for a recheck, URGENT (and notified) only when the recorder ticked a sign of organ damage.
 * It never refuses the set.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VitalSignJudgement {

    /** The neonatal period: under 28 days of life. */
    public static final int NEWBORN_BELOW_DAYS = 28;

    /** The newborn assessment's own notice and type: the same message reaches the same inbox. */
    static final String NOTICE_KEY = "newborn.alert.notification";
    static final String NOTIFICATION_TYPE = "MATERNITY_ALERT";
    private static final String ALERT_MSG_PREFIX = "newborn.alert.msg.";
    private static final String TYPE_PREFIX = "newborn.alert.type.";

    private final NewbornAssessmentRepository newbornAssessmentRepository;
    private final News2EscalationService news2EscalationService;
    private final NotificationService notificationService;
    private final MessageSource messageSource;
    /** QA D1592: a baby registered from a delivery knows its birth before any assessment is recorded. */
    private final com.example.hms.repository.DeliveryNewbornRepository deliveryNewbornRepository;
    /** QA D1890, r54 (provisional): the shared severe-blood-pressure rule. */
    private final SevereHypertension severeHypertension;

    /** The postpartum warning's notice and type label (the postpartum observation's own). */
    static final String POSTPARTUM_NOTICE_KEY = "postpartum.alert.notification";
    static final String POSTPARTUM_TYPE_KEY = "postpartum.alert.type.HYPERTENSION";
    /** r54 review: a pregnant patient's notice is headed as a pregnancy alert, never "postpartum". */
    static final String PREGNANCY_NOTICE_KEY = "vitals.alert.notification.pregnancy";

    /** The adult URGENT warning's notice and type label (r54). */
    static final String HYPERTENSION_NOTICE_KEY = "vitals.alert.notification";
    static final String HYPERTENSION_TYPE_KEY = "vitals.alert.type.HYPERTENSION";
    static final String HYPERTENSION_NOTIFICATION_TYPE = "SEVERE_HYPERTENSION";

    /**
     * What the judgement found: whether the newborn rules applied, and their findings; and the
     * shared severe-blood-pressure verdict (QA D1890, r54).
     */
    public record Judgement(boolean newborn, List<NewbornAlertRules.Finding> newbornFindings,
                            SevereHypertension.Verdict bloodPressure, MaternalEarlyWarning.Score maternal) {
        public Judgement {
            newbornFindings = newbornFindings == null ? List.of() : List.copyOf(newbornFindings);
            bloodPressure = bloodPressure == null ? SevereHypertension.Verdict.NONE : bloodPressure;
        }

        public Judgement(boolean newborn, List<NewbornAlertRules.Finding> newbornFindings,
                         SevereHypertension.Verdict bloodPressure) {
            this(newborn, newbornFindings, bloodPressure, null);
        }

        public Judgement(boolean newborn, List<NewbornAlertRules.Finding> newbornFindings) {
            this(newborn, newbornFindings, SevereHypertension.Verdict.NONE, null);
        }

        /**
         * Policy 2026-11-20: the maternal early warning alone, for a reading a maternity screen charted.
         * That screen raised its own pressure and newborn alerts, so neither is carried a second time.
         */
        public Judgement maternalOnly() {
            return new Judgement(false, List.of(), SevereHypertension.Verdict.NONE, maternal);
        }

        /** The maternal warning marks the set: a review is due, or a CRADLE add-on alert is lit. */
        public boolean maternalSignificant() {
            return maternal != null && (maternal.needsEscalation() || !maternal.alerts().isEmpty());
        }

        /** The obstetric rule raised (pregnancy, or 42 days after a birth): URGENT. */
        public boolean postpartumSevereHypertension() {
            return bloodPressure.obstetric();
        }

        /** The adult rule raised: WARNING, or URGENT with a sign of organ damage. */
        public boolean adultSevereHypertension() {
            return bloodPressure.adult();
        }

        static Judgement notNewborn() {
            return new Judgement(false, List.of());
        }

        /** URGENT or CAUTION: worth a clinician's look. A reference-range notice alone is not. */
        public boolean newbornSignificant() {
            return newbornFindings.stream().anyMatch(f -> f.severity() == NewbornAlertSeverity.URGENT
                || f.severity() == NewbornAlertSeverity.CAUTION);
        }
    }

    /**
     * Judges the set before it is saved and marks it clinically significant when it is. Never
     * clears a flag the caller already set, and never refuses anything.
     */
    public Judgement judge(PatientVitalSign vital) {
        if (vital == null) {
            return Judgement.notNewborn();
        }
        Patient patient = vital.getPatient();
        LocalDateTime at = vital.getRecordedAt() != null ? vital.getRecordedAt() : LocalDateTime.now();
        LocalDate dateOfBirth = patient == null ? null : patient.getDateOfBirth();
        Judgement judgement;
        boolean significant;
        if (isNewborn(dateOfBirth, at)) {
            NewbornAlertRules.Reading reading = new NewbornAlertRules.Reading(vital.getTemperatureCelsius(),
                vital.getHeartRateBpm(), vital.getRespiratoryRateBpm(), vital.getSpo2Percent(),
                vital.getBloodGlucoseMgDl(), null, vital.getSystolicBpMmHg(), vital.getDiastolicBpMmHg(),
                NewbornAlertRules.hoursSinceBirth(null, dateOfBirth, at),
                preterm(patient, vital.getHospital()),
                // A persistent rate is not read from earlier vital sets: the first reading
                // alerts and asks for a recheck, as the newborn assessment's first one does.
                false);
            judgement = new Judgement(true, NewbornAlertRules.evaluate(reading));
            significant = judgement.newbornSignificant();
        } else {
            // QA D1890, r54: a severe pressure under the shared rule is significant and warns; never refuses.
            SevereHypertension.Verdict bloodPressure = severeHypertension == null || patient == null
                ? SevereHypertension.Verdict.NONE
                : severeHypertension.evaluate(patient,
                    com.example.hms.service.recordaccess.CrossHospitalReachRecorder.hospitalIdOf(vital.getHospital()),
                    vital.getSystolicBpMmHg(), vital.getDiastolicBpMmHg(), at,
                    !com.example.hms.enums.OrganDamageSymptom.parse(vital.getOrganDamageSymptoms()).isEmpty());
            // Policy 2026-11-20 (provisional): a pregnant or postpartum woman's set is scored with the
            // maternal early warning; a level that needs a review, or a CRADLE alert, is significant.
            MaternalEarlyWarning.Score maternal = maternalEarlyWarning == null || patient == null ? null
                : maternalEarlyWarning.assess(patient,
                    com.example.hms.service.recordaccess.CrossHospitalReachRecorder.hospitalIdOf(vital.getHospital()),
                    // A raised warning resting on another hospital's record is accounted once: the pressure
                    // warning of this reading already did it when it raised.
                    vital, !bloodPressure.raised());
            judgement = new Judgement(false, List.of(), bloodPressure, maternal);
            significant = judgement.bloodPressure().raised() || outsideAgeRanges(vital)
                || judgement.maternalSignificant();
        }
        if (significant) {
            vital.setClinicallySignificant(true);
        }
        return judgement;
    }

    /**
     * After the save: the NEWS2 escalation every path had, then the newborn notices. Never throws
     * for a notice that could not be created: the vital set is the record.
     */
    public void escalate(PatientVitalSign saved, Judgement judgement, UUID patientId, UUID hospitalId,
                         String recorderUsername, String visitPhysicianUsername) {
        escalate(saved, judgement, patientId, hospitalId, recorderUsername, visitPhysicianUsername, false);
    }

    /**
     * Review of #1237: the same, for a maternity reading. {@code news2CoveredByMaternityAlert} is true only
     * when an URGENT maternity alert already paged the team for this very reading; the NEWS2 page is then
     * not sent twice. Otherwise it fires at its usual trigger (a safety net: in labour it is the only page
     * for a low pressure or a fast pulse).
     */
    public void escalate(PatientVitalSign saved, Judgement judgement, UUID patientId, UUID hospitalId,
                         String recorderUsername, String visitPhysicianUsername, boolean news2CoveredByMaternityAlert) {
        // Policy 2026-11-20 (provisional): for a pregnant or postpartum woman the NEWS2 page is replaced
        // by the maternal early-warning escalation. NEWS2 is not validated for her and never pages.
        MaternalEarlyWarning.Score maternal = judgement == null ? null : judgement.maternal();
        if (maternal == null && !news2CoveredByMaternityAlert) {
            news2EscalationService.escalateIfNeeded(saved, patientId, hospitalId, recorderUsername,
                visitPhysicianUsername);
        }
        Set<String> paged = Set.of();
        if (judgement != null && patientId != null && hospitalId != null) {
            paged = escalateBloodPressure(saved, judgement.bloodPressure(), patientId, hospitalId, recorderUsername,
                visitPhysicianUsername);
        }
        if (maternal != null && !news2CoveredByMaternityAlert && maternalEarlyWarning != null) {
            // One reading never pages a person twice: whoever the severe-pressure notice reached is skipped.
            maternalEarlyWarning.escalate(saved, maternal, patientId, hospitalId, recorderUsername,
                visitPhysicianUsername, paged);
        }
        if (judgement == null || !judgement.newborn() || patientId == null || hospitalId == null) {
            return;
        }
        List<NewbornAlertRules.Finding> urgent = judgement.newbornFindings().stream()
            .filter(f -> f.severity() == NewbornAlertSeverity.URGENT)
            .toList();
        if (urgent.isEmpty()) {
            return;
        }
        Set<String> recipients = news2EscalationService.recipients(patientId, hospitalId, recorderUsername,
            visitPhysicianUsername);
        String fullName = saved != null && saved.getPatient() != null
            ? Objects.toString(saved.getPatient().getFullName(), "") : "";
        Object patientArg = fullName.isBlank() ? NotificationLocalizer.key("patient.fallback.generic") : fullName;
        for (NewbornAlertRules.Finding finding : urgent) {
            // QA D665: each reader gets the notice in her own language.
            Object[] args = {NotificationLocalizer.key(TYPE_PREFIX + finding.type().name()), patientArg,
                ObstetricAlertEscalationWorker.alertMessageArg(ALERT_MSG_PREFIX + finding.code(), null, "")};
            for (String recipient : recipients) {
                try {
                    notificationService.createNotification(NOTICE_KEY, args, recipient, NOTIFICATION_TYPE);
                } catch (RuntimeException ex) {
                    log.warn("Newborn vitals notice not created: {}", ex.getClass().getSimpleName());
                }
            }
        }
    }

    /**
     * Obstetric: always URGENT, notified. Adult: only the URGENT one (a sign of organ damage) is notified.
     * Returns who the notice reached, so the maternal escalation of the same reading skips them.
     */
    private Set<String> escalateBloodPressure(PatientVitalSign saved, SevereHypertension.Verdict bloodPressure,
                                              UUID patientId, UUID hospitalId, String recorderUsername,
                                              String visitPhysicianUsername) {
        if (bloodPressure.obstetric()
            || (bloodPressure.adult() && bloodPressure.level() == SevereHypertension.Level.URGENT)) {
            return notifySevereHypertension(saved, patientId, hospitalId, recorderUsername, visitPhysicianUsername,
                bloodPressure.scope(), bloodPressure.messageKey());
        }
        return Set.of();
    }

    /**
     * QA D1890, r54: the notice (type, patient, warning) to the recorder, the stay's responsible
     * physician and the visit's clinician, each in her language. Never throws.
     */
    private Set<String> notifySevereHypertension(PatientVitalSign saved, UUID patientId, UUID hospitalId,
                                                 String recorderUsername, String visitPhysicianUsername,
                                                 SevereHypertension.Scope scope, String messageKey) {
        Set<String> recipients = news2EscalationService.recipients(patientId, hospitalId, recorderUsername,
            visitPhysicianUsername);
        String fullName = saved != null && saved.getPatient() != null
            ? Objects.toString(saved.getPatient().getFullName(), "") : "";
        Object patientArg = fullName.isBlank() ? NotificationLocalizer.key("patient.fallback.generic") : fullName;
        // Postpartum: the postpartum observation's own notice; pregnancy: a pregnancy notice (r54
        // review: never headed "postpartum" for a woman still pregnant); adult: the vitals notice.
        Object[] args = {NotificationLocalizer.key(scope == SevereHypertension.Scope.ADULT
                ? HYPERTENSION_TYPE_KEY : POSTPARTUM_TYPE_KEY), patientArg,
            ObstetricAlertEscalationWorker.alertMessageArg(messageKey, null, "")};
        Set<String> reached = new java.util.LinkedHashSet<>();
        for (String recipient : recipients) {
            try {
                if (scope == SevereHypertension.Scope.POSTPARTUM) {
                    notificationService.createNotification(POSTPARTUM_NOTICE_KEY, args, recipient, NOTIFICATION_TYPE);
                } else if (scope == SevereHypertension.Scope.PREGNANT) {
                    notificationService.createNotification(PREGNANCY_NOTICE_KEY, args, recipient, NOTIFICATION_TYPE);
                } else {
                    notificationService.createNotification(HYPERTENSION_NOTICE_KEY, args, recipient,
                        HYPERTENSION_NOTIFICATION_TYPE);
                }
                reached.add(recipient);
            } catch (RuntimeException ex) {
                log.warn("Severe blood-pressure notice not created: {}", ex.getClass().getSimpleName());
            }
        }
        return reached;
    }

    /** Policy 2026-11-20: the maternal early warning (setter-injected; absent, every path is as before). */
    private MaternalEarlyWarningService maternalEarlyWarning;

    @org.springframework.beans.factory.annotation.Autowired
    void setMaternalEarlyWarning(MaternalEarlyWarningService maternalEarlyWarning) {
        this.maternalEarlyWarning = maternalEarlyWarning;
    }

    static final String MATERNAL_ALERT_TYPE = "MATERNAL_EARLY_WARNING";
    static final String MEWS_MESSAGE_PREFIX = "vitals.alert.msg.mews.";
    static final String MATERNAL_ALERT_MESSAGE_PREFIX = "vitals.alert.msg.maternal.";

    /**
     * Policy 2026-11-20 (provisional): what the recorder of a pregnant or postpartum woman's set reads
     * after the save, in her language: the level of concern when a review is due, and each shock-index
     * alert (the severe pressure keeps its own warning, {@link #postpartumAlerts}). Empty otherwise.
     */
    public List<NewbornVitalAlertDTO> maternalAlerts(Judgement judgement) {
        MaternalEarlyWarning.Score score = judgement == null ? null : judgement.maternal();
        if (score == null || maternalEarlyWarning == null) {
            return List.of();
        }
        java.util.Locale locale = LocaleContextHolder.getLocale();
        MaternalEarlyWarningRules rules = maternalEarlyWarning.rules();
        List<NewbornVitalAlertDTO> alerts = new java.util.ArrayList<>();
        // The level the set is answered at: a concern or a RED add-on alert has already raised it.
        MaternalEarlyWarning.Tier tier = score.responseTier();
        if (tier != MaternalEarlyWarning.Tier.LOW) {
            String code = "mews-" + tier.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            Integer review = MaternalEarlyWarning.reviewWithinMinutes(rules, tier);
            alerts.add(NewbornVitalAlertDTO.builder()
                .code(code)
                .severity(tier == MaternalEarlyWarning.Tier.HIGH ? "URGENT" : "WARNING")
                .type(MATERNAL_ALERT_TYPE)
                .message(messageSource.getMessage(MEWS_MESSAGE_PREFIX + tier.name(),
                    new Object[]{score.total(), review == null ? 0 : review,
                        MaternalEarlyWarning.observationInterval(rules, score, java.time.Duration.ofHours(12)).toMinutes()},
                    code, locale))
                .build());
        }
        for (MaternalEarlyWarning.HardAlert alert : score.alerts()) {
            if (alert == MaternalEarlyWarning.HardAlert.SEVERE_HYPERTENSION) {
                continue;
            }
            String code = "maternal-" + alert.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
            alerts.add(NewbornVitalAlertDTO.builder()
                .code(code)
                .severity(alert.light() == MaternalEarlyWarning.Light.RED ? "URGENT" : "WARNING")
                .type(MATERNAL_ALERT_TYPE)
                .message(messageSource.getMessage(MATERNAL_ALERT_MESSAGE_PREFIX + alert.name(), null, code, locale))
                .build());
        }
        return alerts;
    }

    /**
     * QA D1890, r54: the obstetric severe-blood-pressure warning (pregnancy, or 42 days after a
     * birth) as the recorder reads it, in her language. Empty when the set raised none.
     */
    public List<NewbornVitalAlertDTO> postpartumAlerts(Judgement judgement) {
        if (judgement == null || !judgement.postpartumSevereHypertension()) {
            return List.of();
        }
        return List.of(bloodPressureAlert(judgement.bloodPressure()));
    }

    /**
     * r54: the adult severe-blood-pressure warning (WARNING, or URGENT with a sign of organ damage)
     * as the recorder reads it, in her language. Empty when the set raised none.
     */
    public List<NewbornVitalAlertDTO> hypertensionAlerts(Judgement judgement) {
        if (judgement == null || !judgement.adultSevereHypertension()) {
            return List.of();
        }
        return List.of(bloodPressureAlert(judgement.bloodPressure()));
    }

    private NewbornVitalAlertDTO bloodPressureAlert(SevereHypertension.Verdict verdict) {
        java.util.Locale locale = LocaleContextHolder.getLocale();
        return NewbornVitalAlertDTO.builder()
            .code(verdict.alertCode())
            .severity(verdict.level().name())
            .type(com.example.hms.enums.PostpartumAlertType.HYPERTENSION.name())
            .message(messageSource.getMessage(verdict.messageKey(), null, verdict.alertCode(), locale))
            .build();
    }

    /** The findings as the recorder reads them, in her language. Empty for anyone not a newborn. */
    public List<NewbornVitalAlertDTO> alerts(Judgement judgement) {
        if (judgement == null || !judgement.newborn()) {
            return List.of();
        }
        java.util.Locale locale = LocaleContextHolder.getLocale();
        return judgement.newbornFindings().stream()
            .map(f -> NewbornVitalAlertDTO.builder()
                .code(f.code())
                .severity(f.severity().name())
                .type(f.type().name())
                .message(messageSource.getMessage(ALERT_MSG_PREFIX + f.code(), null, f.code(), locale))
                .build())
            .toList();
    }

    /** Under 28 days of life at the time of the reading; never for an unknown date of birth. */
    public static boolean isNewborn(LocalDate dateOfBirth, LocalDateTime at) {
        if (dateOfBirth == null || at == null) {
            return false;
        }
        long days = ChronoUnit.DAYS.between(dateOfBirth, at.toLocalDate());
        return days >= 0 && days < NEWBORN_BELOW_DAYS;
    }

    /** Preterm when this hospital's delivery record for the baby says so; unknown reads as term. */
    private boolean preterm(Patient patient, Hospital hospital) {
        if (patient == null || patient.getId() == null) {
            return false;
        }
        UUID hospitalId = hospital == null ? null : hospital.getId();
        // Scoped in the query: an earlier assessment at another hospital must not hide this one's.
        java.util.Optional<NewbornAssessment> birth = hospitalId == null
            ? newbornAssessmentRepository.findFirstByPatient_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(patient.getId())
            : newbornAssessmentRepository.findFirstByPatient_IdAndHospital_IdAndDeliveryRecordIsNotNullOrderByAssessmentTimeAsc(
                patient.getId(), hospitalId);
        return birth
            .map(NewbornAssessment::getDeliveryRecord)
            .or(() -> deliveryNewbornRepository.findByNewbornPatient_Id(patient.getId())
                .filter(link -> hospitalId == null
                    || (link.getHospital() != null && hospitalId.equals(link.getHospital().getId())))
                .map(com.example.hms.model.labor.DeliveryNewborn::getDeliveryRecord))
            .map(DeliveryRecord::getGestationalAgeWeeksAtBirth)
            .filter(weeks -> weeks < NewbornAlertRules.PRETERM_BELOW_WEEKS)
            .isPresent();
    }

    /**
     * The rules every age past the neonatal period had at the nurse station: the per-vital limits,
     * or a NEWS2 total of 5 or more where NEWS2 applies (P3 #25b).
     */
    static boolean outsideAgeRanges(PatientVitalSign v) {
        return !perVitalFlags(v).isEmpty()
            || (NewsScoreCalculator.applies(v) && NewsScoreCalculator.score(v).total() >= 5);
    }

    /** A value below its rule's floor. */
    public static final String LOW = "LOW";
    /** A value above its rule's ceiling. */
    public static final String HIGH = "HIGH";
    /** A newborn finding: the newborn rules give no direction to show. */
    public static final String ABNORMAL = "ABNORMAL";

    /**
     * Polish 2026-11-13: which values of a set the rules above judge out of range, for a reader
     * that shows each value (the after-visit summary). Keyed by the vital's field name. The same
     * rules as {@link #judge}: the newborn rules' URGENT and CAUTION findings under 28 days of life,
     * else the per-vital limits. A value no rule judges (a weight, a height, a pain score, a
     * diastolic below any limit) never gets an entry; a NEWS2 total flags the set, not a value.
     */
    public static java.util.Map<String, String> valueFlags(PatientVitalSign v, LocalDate dateOfBirth) {
        if (v == null) {
            return java.util.Map.of();
        }
        LocalDateTime at = v.getRecordedAt() != null ? v.getRecordedAt() : LocalDateTime.now();
        if (!isNewborn(dateOfBirth, at)) {
            return perVitalFlags(v);
        }
        // Preterm and a persistent rate only change reference notices (INFO), which flag nothing here.
        NewbornAlertRules.Reading reading = new NewbornAlertRules.Reading(v.getTemperatureCelsius(),
            v.getHeartRateBpm(), v.getRespiratoryRateBpm(), v.getSpo2Percent(), v.getBloodGlucoseMgDl(), null,
            v.getSystolicBpMmHg(), v.getDiastolicBpMmHg(), NewbornAlertRules.hoursSinceBirth(null, dateOfBirth, at),
            false, false);
        java.util.Map<String, String> flags = new java.util.LinkedHashMap<>();
        for (NewbornAlertRules.Finding f : NewbornAlertRules.evaluate(reading)) {
            if (f.severity() != NewbornAlertSeverity.URGENT && f.severity() != NewbornAlertSeverity.CAUTION) {
                continue;
            }
            for (String field : newbornFields(f)) {
                flags.put(field, ABNORMAL);
            }
        }
        return flags;
    }

    /** The value(s) a newborn finding is about, from its type and, for the heart, its code. */
    private static List<String> newbornFields(NewbornAlertRules.Finding f) {
        return switch (f.type()) {
            case THERMOREGULATION -> List.of("temperatureCelsius");
            case RESPIRATORY -> List.of("respiratoryRateBpm");
            case OXYGENATION -> List.of("spo2Percent");
            case GLUCOSE -> List.of("bloodGlucoseMgDl");
            case CARDIOVASCULAR -> f.code() != null && f.code().contains("pressure")
                ? List.of("systolicBpMmHg", "diastolicBpMmHg") : List.of("heartRateBpm");
            default -> List.of();
        };
    }

    /** The nurse station's per-vital limits, one entry per value outside them. */
    private static java.util.Map<String, String> perVitalFlags(PatientVitalSign v) {
        java.util.Map<String, String> flags = new java.util.LinkedHashMap<>();
        range(flags, "heartRateBpm", v.getHeartRateBpm(), 40, 150);
        if (v.getSpo2Percent() != null && v.getSpo2Percent() < 90) {
            flags.put("spo2Percent", LOW);
        }
        range(flags, "respiratoryRateBpm", v.getRespiratoryRateBpm(), 8, 30);
        range(flags, "systolicBpMmHg", v.getSystolicBpMmHg(), 80, 200);
        if (v.getDiastolicBpMmHg() != null && v.getDiastolicBpMmHg() > 120) {
            flags.put("diastolicBpMmHg", HIGH);
        }
        Double temperature = v.getTemperatureCelsius();
        if (temperature != null && (temperature < 35.0 || temperature > 40.0)) {
            flags.put("temperatureCelsius", temperature < 35.0 ? LOW : HIGH);
        }
        range(flags, "bloodGlucoseMgDl", v.getBloodGlucoseMgDl(), 50, 400);
        return flags;
    }

    private static void range(java.util.Map<String, String> flags, String field, Integer value, int min, int max) {
        if (value != null && (value < min || value > max)) {
            flags.put(field, value < min ? LOW : HIGH);
        }
    }
}
