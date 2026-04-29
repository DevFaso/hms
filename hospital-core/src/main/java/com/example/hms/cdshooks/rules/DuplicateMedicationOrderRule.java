package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.Prescription;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Fires when the proposed prescription duplicates a recently active
 * order for the same patient. Two strategies, in order:
 *
 * <ol>
 *   <li><strong>RxNorm match</strong> — when both sides have a
 *       resolved RxNorm, an exact match wins.</li>
 *   <li><strong>Generic-name fallback</strong> — when RxNorm is
 *       missing on either side, compare the lower-cased
 *       {@code medicationName}. Substring match is intentionally
 *       loose so "Amoxicillin 500 mg" duplicates "amoxicillin"
 *       even when the historical row was freetext.</li>
 * </ol>
 *
 * <p>Window is the last seven days against any prescription that has
 * not been fully terminal (CANCELLED / DISCONTINUED / PARTNER_REJECTED).
 * The engine pre-fetches {@code activePrescriptions} so this rule does
 * no I/O.
 */
@Component
public class DuplicateMedicationOrderRule implements CdsRule {

    private static final String ID = "duplicate-medication-order";
    private static final String SOURCE_LABEL = "HMS Duplicate-Order Check";
    private static final Duration LOOKBACK = Duration.ofDays(7);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<CdsCard> evaluate(CdsRuleContext context) {
        String proposedRx = context.proposedRxnormCode();
        String proposedName = context.proposedMedicationNameLower();
        if (proposedRx == null && proposedName.isEmpty()) return List.of();

        LocalDateTime cutoff = LocalDateTime.now().minus(LOOKBACK);
        for (int i = 0; i < context.activePrescriptions().size(); i++) {
            Prescription existing = context.activePrescriptions().get(i);
            if (existing == null || existing.getCreatedAt() == null
                || existing.getCreatedAt().isBefore(cutoff)) continue;

            String existingRx = i < context.activePrescriptionRxnorms().size()
                ? context.activePrescriptionRxnorms().get(i)
                : null;
            if (matches(proposedRx, existingRx, proposedName, existing.getMedicationName())) {
                return List.of(buildCard(existing));
            }
        }
        return List.of();
    }

    private static boolean matches(String proposedRx, String existingRx,
                                   String proposedNameLower, String existingName) {
        if (proposedRx != null && existingRx != null && proposedRx.equals(existingRx)) {
            return true;
        }
        if (proposedNameLower.isEmpty() || existingName == null) return false;
        String existingLower = existingName.trim().toLowerCase(Locale.ROOT);
        return existingLower.contains(proposedNameLower)
            || proposedNameLower.contains(existingLower);
    }

    private CdsCard buildCard(Prescription existing) {
        String summary = "Possible duplicate order: "
            + existing.getMedicationName()
            + " was prescribed on "
            + existing.getCreatedAt().toLocalDate();
        String detail =
            "An active prescription for the same medication exists within the past "
                + LOOKBACK.toDays() + " days (status: " + existing.getStatus()
                + "). Verify the new order is intended.";
        return new CdsCard(
            summary, detail, CdsCard.Indicator.WARNING,
            new Source(SOURCE_LABEL, null, null),
            null, null, null, UUID.randomUUID().toString()
        );
    }
}
