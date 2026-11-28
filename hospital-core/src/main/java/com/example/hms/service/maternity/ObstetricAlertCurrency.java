package com.example.hms.service.maternity;

import com.example.hms.enums.LaborAlertSeverity;
import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.NewbornAlertSeverity;
import com.example.hms.enums.PostpartumAlertSeverity;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.model.labor.LaborPartographEntry;
import com.example.hms.model.labor.ObstetricAlertEscalation;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.model.postpartum.PostpartumCarePlan;
import com.example.hms.model.postpartum.PostpartumObservation;
import com.example.hms.repository.LaborPartographEntryRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.PostpartumObservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * QA D605: whether an URGENT obstetric alert still stands, read from the
 * clinical record that raised it. Reminders stop once it no longer does:
 * <ul>
 *   <li>Partograph: the labour episode is no longer active (delivered,
 *       transferred, closed), or a later entry re-measured the values behind
 *       the alert and raised no URGENT alert of the same type.</li>
 *   <li>Newborn: a later assessment of the same baby re-measured the values
 *       behind the alert and raised no URGENT alert of the same type.</li>
 *   <li>Postpartum: the care plan is closed, or a later observation of the
 *       plan re-measured the values behind the alert and raised no URGENT
 *       alert of the same type.</li>
 *   <li>Delivery: it stands until acknowledged or the cap, except the
 *       stillbirth alert of a delivery whose babies were all corrected to
 *       live (QA D1714, Rex on #1144), and a neonatal-care or outcome-conflict
 *       alert a corrected outcome took off the record (provisional, 2026-11-14).</li>
 *   <li>A newborn's Apgar alert stops once that baby is a confirmed stillbirth
 *       (provisional, 2026-11-14).</li>
 *   <li>An adult HIGH NEWS2 (QA D3862): a later complete vital set of the
 *       patient at the hospital scored below HIGH.</li>
 *   <li>A newborn's finding on a vital set, an adult's urgent hypertension and a maternal early warning
 *       (QA D4103): a later vital set that re-measured the values and that the raising rule no longer judges
 *       urgent ({@link com.example.hms.service.vitals.VitalSetAlertCurrency}).</li>
 * </ul>
 * QA D658 (High, patient safety): a later record that did not measure a
 * value says nothing about it. Only the newest later record that re-measured
 * every value the source measured for that alert type decides; later records
 * that left one of them blank are skipped. A type whose trigger cannot be
 * re-measured (a checkbox, a catch-all category) never closes on its own.
 * A source that cannot be read keeps the alert standing: silence must never
 * come from missing data. Called inside the worker's transaction.
 */
@Component
@RequiredArgsConstructor
public class ObstetricAlertCurrency {

    private final LaborPartographEntryRepository partographEntryRepository;
    private final NewbornAssessmentRepository newbornAssessmentRepository;
    private final PostpartumObservationRepository postpartumObservationRepository;
    /** The delivery's stillbirth alert code; its escalation closes once no baby of the delivery is stillborn. */
    public static final String STILLBIRTH_ALERT = "labor-stillbirth";
    /** The key the escalation row stores for that alert's sentence (labor.alert.msg.&lt;code&gt;). */
    static final String STILLBIRTH_ALERT_MESSAGE_KEY = "labor.alert.msg.labor-stillbirth";

    /** Rex on #1144: a corrected baby outcome can withdraw a delivery's stillbirth alert. */
    private final com.example.hms.repository.DeliveryRecordRepository deliveryRecordRepository;
    /** Provisional (2026-11-14): no Apgar or preterm reminder for a confirmed stillborn baby, per baby. */
    private final StillbornNeonatalAlerts stillbornNeonatalAlerts;

    /** @return true when the alert no longer warrants a reminder */
    public boolean noLongerUrgent(ObstetricAlertEscalation row) {
        if (row.getSourceType() == null || row.getSourceId() == null || row.getAlertType() == null) {
            return false;
        }
        return switch (row.getSourceType()) {
            case ObstetricAlertEscalationService.SOURCE_PARTOGRAPH -> partographResolved(row);
            case ObstetricAlertEscalationService.SOURCE_NEWBORN -> newbornResolved(row);
            case ObstetricAlertEscalationService.SOURCE_POSTPARTUM -> postpartumResolved(row);
            case ObstetricAlertEscalationService.SOURCE_DELIVERY -> deliveryResolved(row);
            case ObstetricAlertEscalationService.SOURCE_NEWS2 -> news2Resolved(row);
            case ObstetricAlertEscalationService.SOURCE_NEWBORN_VITAL_SIGN -> vitalSets != null
                && vitalSets.newbornFindingResolved(row.getSourceId(), row.getPatientId(), row.getHospitalId(),
                    row.getAlertType());
            case ObstetricAlertEscalationService.SOURCE_URGENT_HYPERTENSION -> vitalSets != null
                && vitalSets.urgentHypertensionResolved(row.getSourceId(), row.getPatientId(), row.getHospitalId());
            case ObstetricAlertEscalationService.SOURCE_VITAL_SIGN -> vitalSets != null
                && vitalSets.maternalWarningResolved(row.getSourceId(), row.getPatientId(), row.getHospitalId());
            case ObstetricAlertEscalationService.SOURCE_POSITIVE_CULTURE -> cultureNoLongerPositive(row);
            case ObstetricAlertEscalationService.SOURCE_O_POSITIVE_COUNTERSIGN -> oPositiveCountersigned(row);
            case ObstetricAlertEscalationService.SOURCE_INCOMPATIBLE_COUNTERSIGN -> incompatibleCountersigned(row);
            // CLOSED_BY_A_PERSON_ONLY, and a source this class has never heard of: the alert stands.
            default -> false;
        };
    }

    /**
     * QA D4103 (r81): the sources with no record that could say the alert is over. A labour transfer's notice and
     * a severe transfusion reaction rest on an event, not on a value somebody can measure again: only a person
     * closes them. Every other source of the ladder has its rule in {@link #noLongerUrgent}
     * ({@code UrgentAlertClosingRuleGuardTest}: a new source must be given a rule, or be listed here with its
     * reason).
     */
    public static final java.util.Set<String> CLOSED_BY_A_PERSON_ONLY = java.util.Set.of(
        ObstetricAlertEscalationService.SOURCE_TRANSFER, ObstetricAlertEscalationService.SOURCE_TRANSFUSION_REACTION);

    /** QA D4103: the alerts raised from a vital set (setter-injected; absent, such an alert stands). */
    private com.example.hms.service.vitals.VitalSetAlertCurrency vitalSets;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVitalSets(com.example.hms.service.vitals.VitalSetAlertCurrency vitalSets) {
        this.vitalSets = vitalSets;
    }

    /**
     * Scorecard K4: the records behind the alerts that are not obstetric (setter-injected; absent, the alert
     * stands).
     */
    private com.example.hms.repository.MicroCultureResultRepository cultureRepository;
    private com.example.hms.repository.OPositiveReleaseDecisionRepository oPositiveDecisions;
    private com.example.hms.repository.IncompatibleReleaseDecisionRepository incompatibleDecisions;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setUrgentAlertRecords(com.example.hms.repository.MicroCultureResultRepository cultureRepository,
                               com.example.hms.repository.OPositiveReleaseDecisionRepository oPositiveDecisions,
                               com.example.hms.repository.IncompatibleReleaseDecisionRepository incompatibleDecisions) {
        this.cultureRepository = cultureRepository;
        this.oPositiveDecisions = oPositiveDecisions;
        this.incompatibleDecisions = incompatibleDecisions;
    }

    /** A culture corrected to something other than growth no longer has a positive to acknowledge. */
    private boolean cultureNoLongerPositive(ObstetricAlertEscalation row) {
        return cultureRepository != null && cultureRepository.findById(row.getSourceId())
            .map(culture -> culture.getGrowthResult() != com.example.hms.enums.MicroGrowthResult.GROWTH)
            .orElse(false);
    }

    /** An overdue countersignature stands until the release is countersigned, and not a moment longer. */
    private boolean oPositiveCountersigned(ObstetricAlertEscalation row) {
        return oPositiveDecisions != null && oPositiveDecisions.findById(row.getSourceId())
            .map(decision -> !decision.countersignOwed()).orElse(false);
    }

    private boolean incompatibleCountersigned(ObstetricAlertEscalation row) {
        return incompatibleDecisions != null && incompatibleDecisions.findById(row.getSourceId())
            .map(decision -> !decision.countersignOwed()).orElse(false);
    }

    /** QA D3862: the vital sets, read for a HIGH NEWS2 (setter-injected; absent, the alert stands). */
    private com.example.hms.repository.PatientVitalSignRepository vitalSignRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setVitalSignRepository(com.example.hms.repository.PatientVitalSignRepository vitalSignRepository) {
        this.vitalSignRepository = vitalSignRepository;
    }

    /**
     * QA D3862: a HIGH NEWS2 no longer stands once a later vital set of the patient at the same hospital measured
     * every NEWS2 parameter (a complete score: D658, a set that left one out says nothing) and scored below HIGH,
     * and no reading at or after that one scored HIGH.
     *
     * <p>Review of #1339 (Rex P1, twice): HIGH, then a complete LOW, then an incomplete HIGH (its repeat alert held
     * back by the open one) closed the alert on the LOW; and a complete LOW with the SAME recorded time as a HIGH
     * (the vitals API keeps the time it is given, so duplicates and back-dated sets exist) could win the tie. The rule
     * reads recorded times only, never the order rows arrived in, and fails safe: a complete reading below HIGH closes
     * the alert only when it is strictly later than every HIGH reading since the alert, complete or not (a partial
     * total already at 7 is HIGH whatever the missing values). A tie goes to HIGH. A partial reading below HIGH says
     * nothing.
     */
    private boolean news2Resolved(ObstetricAlertEscalation row) {
        if (vitalSignRepository == null) {
            return false;
        }
        Optional<com.example.hms.model.PatientVitalSign> found = vitalSignRepository.findById(row.getSourceId());
        if (found.isEmpty() || found.get().getRecordedAt() == null) {
            return false;
        }
        com.example.hms.model.PatientVitalSign source = found.get();
        List<com.example.hms.model.PatientVitalSign> since = vitalSignRepository.findWithinRange(row.getPatientId(),
                row.getHospitalId(), source.getRecordedAt(), null, org.springframework.data.domain.PageRequest.of(0, 200))
            .stream()
            .filter(later -> !Objects.equals(later.getId(), source.getId()))
            .filter(later -> later.getRecordedAt() != null && !later.getRecordedAt().isBefore(source.getRecordedAt()))
            .toList();
        return closedBy(source.getRecordedAt(), since);
    }

    /**
     * Whether a complete reading below HIGH is strictly later than the alert and than every HIGH reading in
     * {@code since} (complete or not). Order-free: only recorded times are compared, and a tie goes to HIGH.
     */
    static boolean closedBy(java.time.LocalDateTime alertAt, List<com.example.hms.model.PatientVitalSign> since) {
        java.time.LocalDateTime latestHigh = alertAt;
        java.time.LocalDateTime latestCompleteLow = null;
        for (com.example.hms.model.PatientVitalSign reading : since) {
            com.example.hms.utility.NewsScoreCalculator.NewsScoreResult score =
                com.example.hms.utility.NewsScoreCalculator.score(reading);
            java.time.LocalDateTime at = reading.getRecordedAt();
            if (score.riskBand() == com.example.hms.utility.NewsScoreCalculator.RiskBand.HIGH) {
                latestHigh = at.isAfter(latestHigh) ? at : latestHigh;
            } else if (score.complete() && (latestCompleteLow == null || at.isAfter(latestCompleteLow))) {
                latestCompleteLow = at;
            }
        }
        return latestCompleteLow != null && latestCompleteLow.isAfter(latestHigh);
    }

    private boolean partographResolved(ObstetricAlertEscalation row) {
        Optional<LaborPartographEntry> found = partographEntryRepository.findById(row.getSourceId());
        if (found.isEmpty() || found.get().getEpisode() == null) {
            return false;
        }
        LaborPartographEntry source = found.get();
        LaborEpisode episode = source.getEpisode();
        if (episode.getStatus() != null && episode.getStatus() != LaborStatus.ACTIVE) {
            return true;
        }
        if (source.getObservationTime() == null) {
            return false;
        }
        String type = row.getAlertType();
        List<Object> measuredBefore = partographValues(type, source);
        return partographEntryRepository
            .findByEpisode_IdAndObservationTimeAfterOrderByObservationTimeDesc(episode.getId(), source.getObservationTime())
            .stream()
            .filter(later -> !Objects.equals(later.getId(), source.getId()))
            .filter(later -> remeasured(measuredBefore, partographValues(type, later)))
            .findFirst()
            .map(later -> later.getAlerts().stream().noneMatch(a -> a.getSeverity() == LaborAlertSeverity.URGENT
                && a.getType() != null && a.getType().name().equals(type)))
            .orElse(false);
    }

    /**
     * Rex on #1144: the stillbirth alert of a delivery whose babies were all corrected to live no longer stands (the
     * correction withdrew it from the record). Every other delivery alert stands until acknowledged or the cap.
     */
    private boolean deliveryResolved(ObstetricAlertEscalation row) {
        String withdrawable = withdrawableCode(row.getAlertMessageKey());
        if (withdrawable != null) {
            // Provisional (2026-11-14): a corrected outcome took this alert off the record (a confirmed stillbirth,
            // or an outcome conflict resolved). The row closes; its history stays.
            return deliveryRecordRepository.findById(row.getSourceId())
                .map(delivery -> delivery.getAlerts().stream().noneMatch(a -> withdrawable.equals(a.getCode())))
                .orElse(false);
        }
        if (!STILLBIRTH_ALERT_MESSAGE_KEY.equals(row.getAlertMessageKey())) {
            return false;
        }
        return deliveryRecordRepository.findById(row.getSourceId())
            .map(delivery -> delivery.stillbornOrders().length == 0
                && delivery.getAlerts().stream().noneMatch(a -> STILLBIRTH_ALERT
                    .equals(a.getCode())))
            .orElse(false);
    }

    /** The neonatal-care and outcome-conflict alerts a correction can take off a delivery, or null. */
    private static String withdrawableCode(String messageKey) {
        if (messageKey == null || !messageKey.startsWith(MESSAGE_KEY_PREFIX)) {
            return null;
        }
        String code = messageKey.substring(MESSAGE_KEY_PREFIX.length());
        return StillbornNeonatalAlerts.NEONATAL_CARE_CODES.contains(code)
            || StillbornNeonatalAlerts.OUTCOME_CONFLICT_ALERT.equals(code) ? code : null;
    }

    private static final String MESSAGE_KEY_PREFIX = "labor.alert.msg.";

    private boolean newbornResolved(ObstetricAlertEscalation row) {
        Optional<NewbornAssessment> found = newbornAssessmentRepository.findById(row.getSourceId());
        if (found.isEmpty() || found.get().getPatient() == null || found.get().getAssessmentTime() == null) {
            return false;
        }
        NewbornAssessment source = found.get();
        // Provisional (2026-11-14): this baby's own Apgar reminder stops once it is a confirmed stillbirth (its birth
        // order is stillborn on the delivery, and nothing recorded for it shows a sign of life). Per baby: a twin's
        // outcome says nothing about this one.
        if ("APGAR".equals(row.getAlertType()) && stillbornNeonatalAlerts.confirmedStillborn(source.getPatient().getId())) {
            return true;
        }
        // Review of D1424: on the mother's chart a later reading of twin B said twin A had
        // recovered, and A's reminders stopped. Twins cannot be told apart here, so only a
        // person closes such an alert; another delivery's baby is another baby.
        com.example.hms.model.labor.DeliveryRecord delivery = source.getDeliveryRecord();
        if (delivery != null && delivery.getNumberOfInfants() > 1) {
            return false;
        }
        String type = row.getAlertType();
        List<Object> measuredBefore = newbornValues(type, source);
        return newbornAssessmentRepository
            .findByPatient_IdAndAssessmentTimeAfterOrderByAssessmentTimeDesc(
                source.getPatient().getId(), source.getAssessmentTime())
            .stream()
            .filter(later -> !Objects.equals(later.getId(), source.getId()))
            .filter(later -> delivery == null || (later.getDeliveryRecord() != null
                && Objects.equals(later.getDeliveryRecord().getId(), delivery.getId())))
            .filter(later -> remeasured(measuredBefore, newbornValues(type, later)))
            .findFirst()
            .map(later -> later.getAlerts().stream().noneMatch(a -> a.getSeverity() == NewbornAlertSeverity.URGENT
                && a.getType() != null && a.getType().name().equals(type)))
            .orElse(false);
    }

    private boolean postpartumResolved(ObstetricAlertEscalation row) {
        Optional<PostpartumObservation> found = postpartumObservationRepository.findById(row.getSourceId());
        if (found.isEmpty() || found.get().getCarePlan() == null) {
            return false;
        }
        PostpartumObservation source = found.get();
        PostpartumCarePlan plan = source.getCarePlan();
        if (!plan.isActive()) {
            return true;
        }
        String type = row.getAlertType();
        if (source.getObservationTime() == null || raisedByCheckbox(type, source)) {
            return false;
        }
        List<Object> measuredBefore = postpartumValues(type, source);
        return postpartumObservationRepository
            .findByCarePlan_IdAndObservationTimeAfterOrderByObservationTimeDesc(plan.getId(), source.getObservationTime())
            .stream()
            .filter(later -> !Objects.equals(later.getId(), source.getId()))
            .filter(later -> remeasured(measuredBefore, postpartumValues(type, later)))
            .findFirst()
            .map(later -> later.getAlerts().stream().noneMatch(a -> a.getSeverity() == PostpartumAlertSeverity.URGENT
                && a.getType() != null && a.getType().name().equals(type)))
            .orElse(false);
    }

    /**
     * True when the later record measured every value the source measured
     * for this alert type. A type with no measurable value, or a source that
     * measured none of them, never matches.
     */
    static boolean remeasured(List<Object> before, List<Object> later) {
        if (before.isEmpty() || before.size() != later.size() || before.stream().allMatch(Objects::isNull)) {
            return false;
        }
        for (int i = 0; i < before.size(); i++) {
            if (before.get(i) != null && later.get(i) == null) {
                return false;
            }
        }
        return true;
    }

    /** The values LaborServiceImpl evaluates for each URGENT partograph alert type. */
    private static List<Object> partographValues(String type, LaborPartographEntry e) {
        return switch (type) {
            case "FETAL_HEART_RATE" -> Arrays.asList(e.getFetalHeartRateBpm());
            case "LABOR_PROGRESS" -> Arrays.asList(e.getCervicalDilationCm());
            case "MATERNAL_VITALS" -> Arrays.asList(e.getSystolicBpMmHg(), e.getDiastolicBpMmHg(),
                e.getPulseBpm(), e.getTemperatureCelsius());
            default -> List.of();
        };
    }

    /** The values NewbornAssessmentServiceImpl evaluates for each URGENT newborn alert type. */
    private static List<Object> newbornValues(String type, NewbornAssessment a) {
        return switch (type) {
            case "APGAR" -> Arrays.asList(a.getApgarOneMinute(), a.getApgarFiveMinute(), a.getApgarTenMinute());
            case "THERMOREGULATION" -> Arrays.asList(a.getTemperatureCelsius());
            case "CARDIOVASCULAR" -> Arrays.asList(a.getHeartRateBpm());
            case "RESPIRATORY" -> Arrays.asList(a.getRespirationsPerMin());
            case "OXYGENATION" -> Arrays.asList(a.getOxygenSaturationPercent());
            case "GLUCOSE" -> Arrays.asList(a.getGlucoseMgDl());
            default -> List.of();
        };
    }

    /** The nullable values PostpartumCareServiceImpl evaluates for each URGENT postpartum type. */
    private static List<Object> postpartumValues(String type, PostpartumObservation o) {
        return switch (type) {
            case "HEMORRHAGE" -> Arrays.asList(o.getSystolicBpMmHg(), o.getPulseBpm(), o.getFundusTone(),
                o.getLochiaAmount(), o.getEstimatedBloodLossMl());
            case "INFECTION" -> Arrays.asList(o.getTemperatureCelsius());
            // QA D1890: a later observation that re-measured both pressures decides.
            case "HYPERTENSION" -> Arrays.asList(o.getSystolicBpMmHg(), o.getDiastolicBpMmHg());
            default -> List.of();
        };
    }

    /**
     * A checkbox finding (excessive bleeding, suspected atony, foul lochia,
     * uterine tenderness, chills) cannot be told apart from "not assessed" on
     * a later observation, so an alert it raised stands until acknowledged
     * or the cap.
     */
    private static boolean raisedByCheckbox(String type, PostpartumObservation o) {
        return switch (type) {
            case "HEMORRHAGE" -> o.isExcessiveBleeding() || o.isUterineAtonySuspected();
            case "INFECTION" -> o.isFoulLochiaOdor() || o.isUterineTenderness() || o.isChillsOrRigors();
            case "HYPERTENSION" -> false;
            default -> true;
        };
    }
}
