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
            default -> false;
        };
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
            default -> true;
        };
    }
}
