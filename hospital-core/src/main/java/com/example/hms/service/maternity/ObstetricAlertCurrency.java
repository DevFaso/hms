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

import java.util.Objects;
import java.util.Optional;

/**
 * QA D605: whether an URGENT obstetric alert still stands, read from the
 * clinical record that raised it. Reminders stop once it no longer does:
 * <ul>
 *   <li>Partograph: the labour episode is no longer active (delivered,
 *       transferred, closed), or a later entry of the episode was charted
 *       without an URGENT alert of the same type.</li>
 *   <li>Newborn: a later assessment of the same baby carries no URGENT alert
 *       of the same type.</li>
 *   <li>Postpartum: the care plan is closed, or a later observation of the
 *       plan carries no URGENT alert of the same type.</li>
 *   <li>Delivery: nothing later supersedes a delivery record, so it stands
 *       until acknowledged or the cap.</li>
 * </ul>
 * A source that cannot be read keeps the alert standing: silence must never
 * come from missing data. Called inside the worker's transaction.
 */
@Component
@RequiredArgsConstructor
public class ObstetricAlertCurrency {

    private final LaborPartographEntryRepository partographEntryRepository;
    private final NewbornAssessmentRepository newbornAssessmentRepository;
    private final PostpartumObservationRepository postpartumObservationRepository;

    /** @return true when the alert no longer warrants a reminder */
    public boolean noLongerUrgent(ObstetricAlertEscalation row) {
        if (row.getSourceType() == null || row.getSourceId() == null) {
            return false;
        }
        return switch (row.getSourceType()) {
            case ObstetricAlertEscalationService.SOURCE_PARTOGRAPH -> partographResolved(row);
            case ObstetricAlertEscalationService.SOURCE_NEWBORN -> newbornResolved(row);
            case ObstetricAlertEscalationService.SOURCE_POSTPARTUM -> postpartumResolved(row);
            default -> false;
        };
    }

    private boolean partographResolved(ObstetricAlertEscalation row) {
        Optional<LaborPartographEntry> source = partographEntryRepository.findById(row.getSourceId());
        if (source.isEmpty() || source.get().getEpisode() == null) {
            return false;
        }
        LaborEpisode episode = source.get().getEpisode();
        if (episode.getStatus() != null && episode.getStatus() != LaborStatus.ACTIVE) {
            return true;
        }
        return partographEntryRepository.findFirstByEpisode_IdOrderByObservationTimeDesc(episode.getId())
            .filter(latest -> !Objects.equals(latest.getId(), row.getSourceId()))
            .filter(latest -> isLater(latest.getObservationTime(), source.get().getObservationTime()))
            .map(latest -> latest.getAlerts().stream().noneMatch(a -> a.getSeverity() == LaborAlertSeverity.URGENT
                && a.getType() != null && a.getType().name().equals(row.getAlertType())))
            .orElse(false);
    }

    private boolean newbornResolved(ObstetricAlertEscalation row) {
        Optional<NewbornAssessment> source = newbornAssessmentRepository.findById(row.getSourceId());
        if (source.isEmpty() || source.get().getPatient() == null) {
            return false;
        }
        return newbornAssessmentRepository.findFirstByPatient_IdOrderByAssessmentTimeDesc(source.get().getPatient().getId())
            .filter(latest -> !Objects.equals(latest.getId(), row.getSourceId()))
            .filter(latest -> isLater(latest.getAssessmentTime(), source.get().getAssessmentTime()))
            .map(latest -> latest.getAlerts().stream().noneMatch(a -> a.getSeverity() == NewbornAlertSeverity.URGENT
                && a.getType() != null && a.getType().name().equals(row.getAlertType())))
            .orElse(false);
    }

    private boolean postpartumResolved(ObstetricAlertEscalation row) {
        Optional<PostpartumObservation> source = postpartumObservationRepository.findById(row.getSourceId());
        if (source.isEmpty() || source.get().getCarePlan() == null) {
            return false;
        }
        PostpartumCarePlan plan = source.get().getCarePlan();
        if (!plan.isActive()) {
            return true;
        }
        return postpartumObservationRepository.findFirstByCarePlan_IdOrderByObservationTimeDesc(plan.getId())
            .filter(latest -> !Objects.equals(latest.getId(), row.getSourceId()))
            .filter(latest -> isLater(latest.getObservationTime(), source.get().getObservationTime()))
            .map(latest -> latest.getAlerts().stream().noneMatch(a -> a.getSeverity() == PostpartumAlertSeverity.URGENT
                && a.getType() != null && a.getType().name().equals(row.getAlertType())))
            .orElse(false);
    }

    private static boolean isLater(java.time.LocalDateTime latest, java.time.LocalDateTime source) {
        return latest != null && source != null && latest.isAfter(source);
    }
}
