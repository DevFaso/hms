package com.example.hms.service.vitals;

import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.repository.PatientVitalSignRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.NewbornAlertType;
import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.model.labor.LaborAlert;
import com.example.hms.model.neonatal.NewbornAssessmentAlert;
import com.example.hms.model.postpartum.PostpartumObservationAlert;

/**
 * QA D2580 (r65): the readings a maternity screen records are charted where the chart reads.
 *
 * <p>A partograph entry, a postpartum observation and a newborn assessment each kept their
 * vital signs in their own table, while « Dossier médical › Signes vitaux », the storyboard and
 * every early-warning score read {@code clinical.patient_vital_signs} only. A doctor answering a
 * CRITICAL maternal alert found « NEWS2 0 Risque faible » on the mother's chart and nothing at
 * all on the baby's. Each of those writes now also charts one vital-sign row, in the same
 * transaction, tagged with its source so the chart can say where it came from.
 *
 * <p>The row is judged by the shared {@link VitalSignJudgement} (so its « significatif » flag
 * matches what the nurse station would set for the same values) and escalated for NEWS2 like
 * every vitals write, but without its judgement: the maternity service already raised its own
 * severe-pressure and newborn alerts for these values, and a second notice per reading would
 * double them. {@code MaternityVitalChartingGuardTest} keeps every entity
 * that stores a vital sign on this list.
 */
@Service
@RequiredArgsConstructor
public class MaternityVitalCharting {

    public static final String SOURCE_LABOR = "LABOR_PARTOGRAPH";
    public static final String SOURCE_POSTPARTUM = "POSTPARTUM";
    public static final String SOURCE_NEWBORN = "NEWBORN_ASSESSMENT";

    private final PatientVitalSignRepository vitalSignRepository;
    private final VitalSignJudgement vitalSignJudgement;

    /** The mother's pulse, blood pressure and temperature from a partograph entry. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PatientVitalSign chartLaborPartographEntry(LaborPartographEntry entry) {
        if (entry == null || entry.getPatient() == null || !anyValue(entry.getPulseBpm(), entry.getSystolicBpMmHg(),
            entry.getDiastolicBpMmHg(), entry.getTemperatureCelsius())) {
            return null;
        }
        PatientHospitalRegistration registration = entry.getEpisode() != null ? entry.getEpisode().getRegistration() : null;
        PatientVitalSign vital = base(entry.getPatient(), entry.getHospital(), registration, entry.getRecordedByStaff(),
            entry.getObservationTime(), SOURCE_LABOR);
        vital.setHeartRateBpm(entry.getPulseBpm());
        vital.setSystolicBpMmHg(entry.getSystolicBpMmHg());
        vital.setDiastolicBpMmHg(entry.getDiastolicBpMmHg());
        vital.setTemperatureCelsius(entry.getTemperatureCelsius());
        List<LaborAlert> alerts = entry.getAlerts() == null ? List.of() : entry.getAlerts();
        // Review of #1241: a partograph entry also carries the baby's and the labour's alerts. Only an URGENT
        // alert about the mother's own values paged for HER; a fetal bradycardia or the action line must not
        // silence her maternal early-warning page. (Any URGENT alert still covers the NEWS2 page, as before.)
        return save(vital,
            alerts.stream().anyMatch(a -> a.getSeverity() == LaborAlertSeverity.CAUTION
                || a.getSeverity() == LaborAlertSeverity.URGENT),
            new VitalSignJudgement.MaternityCover(
                alerts.stream().anyMatch(a -> a.getSeverity() == LaborAlertSeverity.URGENT),
                alerts.stream().anyMatch(a -> a.getSeverity() == LaborAlertSeverity.URGENT
                    && a.getType() == com.example.hms.enums.LaborAlertType.MATERNAL_VITALS)));
    }

    /** The mother's postpartum vital signs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PatientVitalSign chartPostpartumObservation(PostpartumObservation observation) {
        // Review of #1237: a correction replaces the observation it supersedes, so the earlier reading's charted
        // row goes (the observation itself keeps its history in its own table).
        if (observation != null && observation.getSupersedesObservation() != null) {
            removeCharted(observation.getSupersedesObservation());
        }
        if (observation == null || observation.getPatient() == null || !anyValue(observation.getTemperatureCelsius(),
            observation.getSystolicBpMmHg(), observation.getDiastolicBpMmHg(), observation.getPulseBpm(),
            observation.getRespirationsPerMin(), observation.getPainScore())) {
            return null;
        }
        PatientVitalSign vital = base(observation.getPatient(), observation.getHospital(), observation.getRegistration(),
            observation.getRecordedByStaff(), observation.getObservationTime(), SOURCE_POSTPARTUM);
        vital.setTemperatureCelsius(observation.getTemperatureCelsius());
        vital.setSystolicBpMmHg(observation.getSystolicBpMmHg());
        vital.setDiastolicBpMmHg(observation.getDiastolicBpMmHg());
        vital.setHeartRateBpm(observation.getPulseBpm());
        vital.setRespiratoryRateBpm(observation.getRespirationsPerMin());
        vital.setPainScore(observation.getPainScore());
        // Policy 2026-11-20 (provisional): « saignement excessif » on the observation is the maternal
        // early warning's additional concern "significant vaginal bleeding". It raises the level; no points.
        if (observation.isExcessiveBleeding()) {
            vital.setMaternalConcerns(com.example.hms.enums.MaternalConcern.join(
                List.of(com.example.hms.enums.MaternalConcern.VAGINAL_BLEEDING)));
        }
        List<PostpartumObservationAlert> alerts = observation.getAlerts() == null ? List.of() : observation.getAlerts();
        // Every postpartum alert is about the mother: an URGENT one covers both pages.
        return save(vital,
            alerts.stream().anyMatch(a -> a.getSeverity() == PostpartumAlertSeverity.CAUTION
                || a.getSeverity() == PostpartumAlertSeverity.URGENT),
            VitalSignJudgement.MaternityCover.of(
                alerts.stream().anyMatch(a -> a.getSeverity() == PostpartumAlertSeverity.URGENT)));
    }

    /**
     * The baby's vital signs, on the baby's own chart. An assessment that could not be placed on a
     * registered newborn's chart (it stayed on the mother's) is not charted: a neonate's heart rate
     * on the mother's vitals would be worse than none.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public PatientVitalSign chartNewbornAssessment(NewbornAssessment assessment, boolean onNewbornChart) {
        if (!onNewbornChart || assessment == null || assessment.getPatient() == null
            || !anyValue(assessment.getTemperatureCelsius(), assessment.getHeartRateBpm(),
                assessment.getRespirationsPerMin(), assessment.getSystolicBpMmHg(), assessment.getDiastolicBpMmHg(),
                assessment.getOxygenSaturationPercent(), assessment.getGlucoseMgDl())) {
            return null;
        }
        PatientVitalSign vital = base(assessment.getPatient(), assessment.getHospital(), assessment.getRegistration(),
            assessment.getRecordedByStaff(), assessment.getAssessmentTime(), SOURCE_NEWBORN);
        vital.setTemperatureCelsius(assessment.getTemperatureCelsius());
        vital.setHeartRateBpm(assessment.getHeartRateBpm());
        vital.setRespiratoryRateBpm(assessment.getRespirationsPerMin());
        vital.setSystolicBpMmHg(assessment.getSystolicBpMmHg());
        vital.setDiastolicBpMmHg(assessment.getDiastolicBpMmHg());
        vital.setSpo2Percent(assessment.getOxygenSaturationPercent());
        vital.setBloodGlucoseMgDl(assessment.getGlucoseMgDl());
        // An Apgar notice is about the birth, not this reading: it neither flags nor covers the row.
        List<NewbornAssessmentAlert> alerts = assessment.getAlerts().stream()
            .filter(a -> a.getType() != NewbornAlertType.APGAR).toList();
        return save(vital,
            alerts.stream().anyMatch(a -> a.getSeverity() == NewbornAlertSeverity.CAUTION
                || a.getSeverity() == NewbornAlertSeverity.URGENT),
            VitalSignJudgement.MaternityCover.of(
                alerts.stream().anyMatch(a -> a.getSeverity() == NewbornAlertSeverity.URGENT)));
    }

    private PatientVitalSign base(Patient patient, Hospital hospital, PatientHospitalRegistration registration,
                                  Staff staff, LocalDateTime at, String source) {
        UserRoleHospitalAssignment assignment = staff != null ? staff.getAssignment() : null;
        if (assignment != null && (assignment.getHospital() == null || hospital == null
            || !hospital.getId().equals(assignment.getHospital().getId()))) {
            assignment = null;
        }
        return PatientVitalSign.builder()
            .patient(patient)
            .hospital(hospital)
            .registration(registration)
            .recordedByStaff(staff)
            .recordedByAssignment(assignment)
            .recordedAt(at != null ? at : LocalDateTime.now(java.time.ZoneId.systemDefault()))
            .source(source)
            .build();
    }

    /** The charted row of an observation a correction superseded, if it was charted. */
    private void removeCharted(PostpartumObservation superseded) {
        if (superseded.getPatient() == null || superseded.getHospital() == null
            || superseded.getObservationTime() == null) {
            return;
        }
        List<PatientVitalSign> rows = vitalSignRepository.findByPatient_IdAndHospital_IdAndSourceAndRecordedAt(
            superseded.getPatient().getId(), superseded.getHospital().getId(), SOURCE_POSTPARTUM,
            superseded.getObservationTime());
        if (!rows.isEmpty()) {
            vitalSignRepository.deleteAll(rows);
        }
    }

    /**
     * @param alerted a CAUTION or URGENT maternity alert was raised on this reading (an INFO notice is not)
     * @param cover which URGENT maternity alert was raised on it: the team was already paged for this
     *              reading, so NEWS2 does not page a second time (review of #1237), nor the maternal early
     *              warning when the alert was about the mother's own condition (review of #1241)
     */
    private PatientVitalSign save(PatientVitalSign vital, boolean alerted, VitalSignJudgement.MaternityCover cover) {
        // QA D1077: the measure rules every vitals write holds (these rows carry no child-only measure).
        com.example.hms.service.support.PatientMeasureRules.requireVitalSignApplicable(
            vital, null, java.time.LocalDate.now(java.time.ZoneId.systemDefault()));
        VitalSignJudgement.Judgement judged = vitalSignJudgement.judge(vital);
        if (alerted) {
            vital.setClinicallySignificant(true);
        }
        PatientVitalSign saved = vitalSignRepository.save(vital);
        // The early-warning escalation every vitals write has, except when an URGENT maternity alert already
        // paged for this reading: one reading never pages twice. For the mother it is the maternal early
        // warning (policy 2026-11-20, provisional; it replaces the NEWS2 page, and in labour it is the page
        // for a low pressure or a fast pulse); for the baby, NEWS2 never applied. Only the maternal warning
        // of the judgement is passed, so the severe-pressure and newborn notices are not sent a second time.
        Staff staff = saved.getRecordedByStaff();
        String recorder = staff != null && staff.getUser() != null ? staff.getUser().getUsername() : null;
        vitalSignJudgement.escalate(saved, judged == null ? null : judged.maternalOnly(), saved.getPatient().getId(),
            saved.getHospital() != null ? saved.getHospital().getId() : null, recorder, null, cover);
        return saved;
    }

    private static boolean anyValue(Object... values) {
        for (Object v : values) {
            if (v != null) {
                return true;
            }
        }
        return false;
    }
}
