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

    /** What the judgement found: whether the newborn rules applied, and their findings. */
    public record Judgement(boolean newborn, List<NewbornAlertRules.Finding> newbornFindings) {
        public Judgement {
            newbornFindings = newbornFindings == null ? List.of() : List.copyOf(newbornFindings);
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
            judgement = Judgement.notNewborn();
            significant = outsideAgeRanges(vital);
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
        news2EscalationService.escalateIfNeeded(saved, patientId, hospitalId, recorderUsername,
            visitPhysicianUsername);
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
