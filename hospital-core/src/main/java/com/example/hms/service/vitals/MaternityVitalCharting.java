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
 * matches what the nurse station would set for the same values) but never escalated: the
 * maternity service already raised its own alerts for these values, and a second notice per
 * reading would double every alert. {@code MaternityVitalChartingGuardTest} keeps every entity
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
        return save(vital, entry.getAlerts() != null && !entry.getAlerts().isEmpty());
    }

    /** The mother's postpartum vital signs. */
    @Transactional(propagation = Propagation.MANDATORY)
    public PatientVitalSign chartPostpartumObservation(PostpartumObservation observation) {
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
        return save(vital, observation.getAlerts() != null && !observation.getAlerts().isEmpty());
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
        return save(vital, !assessment.getAlerts().isEmpty());
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
            .recordedAt(at != null ? at : LocalDateTime.now())
            .source(source)
            .build();
    }

    private PatientVitalSign save(PatientVitalSign vital, boolean alerted) {
        vitalSignJudgement.judge(vital);
        if (alerted) {
            vital.setClinicallySignificant(true);
        }
        return vitalSignRepository.save(vital);
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
