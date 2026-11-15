package com.example.hms.service.maternity;

import com.example.hms.enums.BabyOutcome;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.neonatal.NewbornAssessment;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.NewbornAssessmentRepository;
import com.example.hms.repository.ObstetricAlertEscalationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Provisional rule (user decision 2026-11-14, pending clinical approval, approvals register): the Apgar and preterm
 * neonatal-care alerts are for a baby who may need neonatal care, so they are not raised or chased for a CONFIRMED
 * stillborn baby. Per baby: a stillborn twin never silences the alerts of a live-born twin. The maternal, stillbirth
 * review and bereavement alerts are never touched here.
 *
 * <ul>
 *   <li>Confirmed stillborn: the recorded outcome is stillborn and nothing recorded for that baby shows a sign of
 *       life (an Apgar score above 0, a heart rate or a respiratory rate).</li>
 *   <li>Unknown or conflicting: an outcome left unknown, or a stillborn outcome against a recorded sign of life, is
 *       never silenced. The neonatal alerts stand and the conflict is flagged to the maternity team
 *       ({@link #OUTCOME_CONFLICT_ALERT}).</li>
 * </ul>
 * Apgar decides nothing here about resuscitation: it is only read as a recorded sign of life that contradicts a
 * stillborn outcome.
 */
@Component
@RequiredArgsConstructor
public class StillbornNeonatalAlerts {

    /** The delivery's neonatal-care alerts this rule governs. */
    public static final String APGAR_CRITICAL = "labor-apgar-critical";
    public static final String APGAR_LOW = "labor-apgar-low";
    public static final String PRETERM = "labor-preterm";
    public static final Set<String> NEONATAL_CARE_CODES = Set.of(APGAR_CRITICAL, APGAR_LOW, PRETERM);
    /** URGENT, to the maternity team: an unknown outcome, or a stillborn one against a recorded sign of life. */
    public static final String OUTCOME_CONFLICT_ALERT = "labor-outcome-conflict";

    private final DeliveryNewbornRepository deliveryNewbornRepository;
    private final NewbornAssessmentRepository newbornAssessmentRepository;
    private final ObstetricAlertEscalationRepository escalationRepository;

    /**
     * What the delivery's own neonatal-care alerts should do.
     *
     * @param suppress true only when every baby is a confirmed stillbirth (the delivery's single Apgar and gestation
     *                 then speak for no live baby)
     * @param conflict true when a baby's outcome is unknown, or a stillborn baby has a recorded sign of life
     */
    public record Assessment(boolean suppress, boolean conflict) {
    }

    public Assessment assess(DeliveryRecord delivery) {
        int babies = Math.max(delivery.getNumberOfInfants(), 1);
        boolean anyLive = false;
        boolean conflict = false;
        for (int order = 1; order <= babies; order++) {
            BabyOutcome outcome = delivery.outcomeOf(order);
            if (outcome == null) {
                conflict = true;
            } else if (outcome == BabyOutcome.LIVE) {
                anyLive = true;
            } else if (registeredBabyShowedLife(delivery, order)) {
                conflict = true;
            }
        }
        // The delivery's own Apgar, and any sign of life recorded on the mother's chart for this delivery, belong to no
        // baby in particular: they can only contradict a delivery with no live baby.
        if (!anyLive && deliverySignOfLife(delivery)) {
            conflict = true;
        }
        return new Assessment(!anyLive && !conflict, conflict);
    }

    /**
     * The reminders of a newborn's own neonatal-care alert stop once that baby is a confirmed stillbirth (its delivery
     * records it stillborn, none of its assessments shows a sign of life, and the delivery's Apgar shows none either
     * when no baby of that delivery was born alive). Per baby, through its birth order.
     */
    public boolean confirmedStillborn(UUID newbornPatientId) {
        if (newbornPatientId == null) {
            return false;
        }
        return deliveryNewbornRepository.findByNewbornPatient_Id(newbornPatientId)
            .map(link -> confirmedStillborn(link, newbornPatientId))
            .orElse(false);
    }

    /**
     * The newborn Apgar alerts of an assessment being recorded (before it is saved) are not raised for a confirmed
     * stillborn baby: the same per-baby rule as {@link #confirmedStillborn}, plus this assessment's own reading.
     * <ul>
     *   <li>A registered baby (its own chart, or recorded there from the mother's): its own birth order decides; a
     *       live twin keeps its alerts.</li>
     *   <li>Otherwise (the mother's chart, the baby not registered) the assessment cannot name its baby: only a
     *       delivery whose every baby is a confirmed stillbirth ({@link #assess} suppresses) is silenced.</li>
     *   <li>A sign of life in this assessment, or one recorded earlier for this baby or for this delivery (its Apgar,
     *       or the mother's chart, when no baby was born alive), or an unknown or conflicting outcome, always
     *       alerts.</li>
     * </ul>
     */
    public boolean confirmedStillbornAssessment(NewbornAssessment assessment) {
        if (assessment == null || showsSignOfLife(assessment)) {
            return false;
        }
        UUID patientId = assessment.getPatient() == null ? null : assessment.getPatient().getId();
        Optional<DeliveryNewborn> link = patientId == null ? Optional.empty()
            : deliveryNewbornRepository.findByNewbornPatient_Id(patientId);
        if (link.isPresent()) {
            return confirmedStillborn(link.get(), patientId);
        }
        DeliveryRecord delivery = assessment.getDeliveryRecord();
        return delivery != null && assess(delivery).suppress();
    }

    /** The sign-of-life reading of one assessment, as {@code NewbornAssessmentRepository.anySignOfLifeRecorded}. */
    static boolean showsSignOfLife(NewbornAssessment assessment) {
        return positive(assessment.getApgarOneMinute()) || positive(assessment.getApgarFiveMinute())
            || positive(assessment.getApgarTenMinute()) || positive(assessment.getHeartRateBpm())
            || positive(assessment.getRespirationsPerMin());
    }

    private boolean confirmedStillborn(DeliveryNewborn link, UUID newbornPatientId) {
        if (link.getDeliveryRecord() == null) {
            return false;
        }
        DeliveryRecord delivery = link.getDeliveryRecord();
        // Rex on #1152: the same sign-of-life rule as assess(). A sign of life recorded for the delivery (its Apgar
        // above 0, or a neonatal assessment on the mother's chart), with no live-born baby it could belong to,
        // contradicts the stillborn outcome: the outcome is uncertain, nothing is closed.
        return delivery.outcomeOf(link.getBirthOrder()) == BabyOutcome.STILLBORN
            && !newbornAssessmentRepository.anySignOfLifeRecorded(newbornPatientId)
            && !(!anyLiveBorn(delivery) && deliverySignOfLife(delivery));
    }

    /** Whether the delivery records at least one of its babies as born alive. */
    static boolean anyLiveBorn(DeliveryRecord delivery) {
        int babies = Math.max(delivery.getNumberOfInfants(), 1);
        for (int order = 1; order <= babies; order++) {
            if (delivery.outcomeOf(order) == BabyOutcome.LIVE) {
                return true;
            }
        }
        return false;
    }

    /** Whether this delivery alert was ever escalated: a correction re-evaluates, it never replays a notice. */
    public boolean alreadyNotified(UUID deliveryId, String code) {
        String messageKey = NOTIFIED_KEYS.get(code);
        return deliveryId != null && messageKey != null && escalationRepository
            .existsBySourceTypeAndSourceIdAndAlertMessageKey(ObstetricAlertEscalationService.SOURCE_DELIVERY,
                deliveryId, messageKey);
    }

    /** The key each governed alert's escalation row stores (labor.alert.msg.&lt;code&gt;). */
    private static final java.util.Map<String, String> NOTIFIED_KEYS = java.util.Map.of(
        APGAR_CRITICAL, "labor.alert.msg.labor-apgar-critical",
        APGAR_LOW, "labor.alert.msg.labor-apgar-low",
        PRETERM, "labor.alert.msg.labor-preterm");

    private boolean registeredBabyShowedLife(DeliveryRecord delivery, int order) {
        if (delivery.getId() == null) {
            return false;
        }
        return deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId()).stream()
            .filter(link -> link.getBirthOrder() == order && link.getNewbornPatient() != null)
            .anyMatch(link -> newbornAssessmentRepository.anySignOfLifeRecorded(link.getNewbornPatient().getId()));
    }

    /**
     * A sign of life recorded for the delivery but for no baby in particular: its own Apgar above 0, or (Rex on
     * #1152) an earlier neonatal assessment of this delivery recorded on the mother's chart. The one shared check of
     * {@link #assess}, {@link #confirmedStillborn} and {@link #confirmedStillbornAssessment}.
     */
    boolean deliverySignOfLife(DeliveryRecord delivery) {
        return positive(delivery.getApgarOneMinute()) || positive(delivery.getApgarFiveMinute())
            || (delivery.getId() != null && newbornAssessmentRepository.anyUnattributedSignOfLifeRecorded(delivery.getId()));
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
