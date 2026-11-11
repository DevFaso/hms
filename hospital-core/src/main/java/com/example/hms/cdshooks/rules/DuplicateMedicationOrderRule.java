package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.service.pharmacy.OrderedDrugIdentity;
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
 * <p>Window is the last seven days against any order in force (QA D1515:
 * signed, not withdrawn or rejected) and any proposal (QA D1523: a draft or
 * an order waiting for its signature, another order the patient may soon be
 * on); the card names the other order's status.
 * The engine pre-fetches {@code activePrescriptions} so this rule does
 * no I/O.
 */
@Component
public class DuplicateMedicationOrderRule implements CdsRule {

    private static final String ID = "duplicate-medication-order";
    private static final String SOURCE_LABEL = "HMS Duplicate-Order Check";

    /** Card wording in the caller's language (D61); English when built outside Spring. */
    private com.example.hms.cdshooks.CdsCardText text = com.example.hms.cdshooks.CdsCardText.ENGLISH;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCardText(com.example.hms.cdshooks.CdsCardText text) {
        this.text = text;
    }
    static final Duration LOOKBACK = Duration.ofDays(7);

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
            if (matches(proposedRx, existingRx, proposedName, existing.getMedicationName())
                    || sameDrug(context, existing)) {
                return List.of(buildCard(existing, context.hospitalId()));
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

    /**
     * QA D1478: the identity the other checks read, for a name that shares no letters with the order
     * it repeats: Doliprane over Paracétamol. The same catalogue item; a name of the proposed item; or,
     * for paracetamol, the orders its own check reads as the drug. A combination is not this drug.
     */
    private static boolean sameDrug(CdsRuleContext context, Prescription existing) {
        MedicationCatalogItem item = context.proposedCatalogItem();
        if (item != null && item.getId() != null && item.getId().equals(existing.getMedicationCatalogItemId())) {
            return true;
        }
        String existingName = existing.getMedicationName();
        if (item != null && OrderedDrugIdentity.agrees(existingName, item)) {
            return !ParacetamolIdentity.isCombinationName(existingName);
        }
        ParacetamolFacts facts = context.paracetamolFacts();
        return facts.applies() && !facts.proposedCombination()
            && (ParacetamolIdentity.nameIsParacetamolAlone(existingName)
                || (existing.getId() != null && facts.otherOrders().stream()
                    .anyMatch(other -> existing.getId().equals(other.order().getId()))));
    }

    private CdsCard buildCard(Prescription existing, UUID hospitalId) {
        String summary = text.get("cds.duplicate.summary",
            "Possible duplicate order: {0} was prescribed on {1}",
            existing.getMedicationName(),
            // QA D1114: the hospital's day the order was written, not the server's UTC day.
            // QA D1121: the request's hospital id (the active orders are loaded for it),
            // never the order's LAZY hospital, which a rule outside a session cannot read.
            text.date(existing.getCreatedAt(), text.zoneOf(hospitalId)));
        String detail = text.get("cds.duplicate.detail",
            "An active prescription for the same medication exists within the past "
                + "{0} days (status: {1}). Verify the new order is intended.",
            String.valueOf(LOOKBACK.toDays()), text.enumWord(existing.getStatus()));
        return new CdsCard(
            summary, detail, CdsCard.Indicator.WARNING,
            new Source(text.get("cds.source.duplicateOrder", SOURCE_LABEL), null, null),
            null, null, null, UUID.randomUUID().toString()
        );
    }
}
