package com.example.hms.service.transfusion;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.escalation.UrgentAlerts;
import com.example.hms.service.maternity.ObstetricAlertEscalationService;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Scorecard K4: an overdue countersignature of an emergency release (O positive or incompatible) keeps escalating
 * until it is signed. It goes on the one ladder: the laboratory directors told by the release notices, then the
 * covering clinician, the médecin-chef, an incident, and reminders that never stop; with no laboratory director
 * (the alert names nobody) its first notice goes to the médecin-chef. Only the signature closes it
 * ({@code ObstetricAlertCurrency}). It tells and reminds: who may sign is the release rule's to say, and nothing
 * here changes it. The release notices raise the alert built here, in their own transaction, with the mark on the
 * decision.
 */
final class OverdueCountersignLadder {

    private final String sourceType;
    private final String alertType;

    OverdueCountersignLadder(String sourceType, String alertType) {
        this.sourceType = sourceType;
        this.alertType = alertType;
    }

    /**
     * The alert that puts the decision {@code decisionId} on the ladder, naming the laboratory directors the
     * release notices told (by username; none when there is no director).
     *
     * @param sentenceArgs the alert sentence's arguments: the unit number and the deadline in hours
     * @param issuer       who issued the release: an acknowledgement counts only from somebody else
     * @param directors    the laboratory directors the release notices told, by user id
     */
    ObstetricAlertEscalationService.UrgentAlert alertFor(UserRepository userRepository, UrgentAlerts.Subject subject,
                                                         UUID decisionId, Object[] sentenceArgs, UUID issuer,
                                                         List<UUID> directors) {
        List<String> named = userRepository.findAllById(directors).stream()
            .map(User::getUsername).filter(Objects::nonNull).toList();
        return UrgentAlerts.of(subject, sourceType, decisionId, alertType, sentenceArgs, issuer)
            .withNamedRecipients(named);
    }
}
