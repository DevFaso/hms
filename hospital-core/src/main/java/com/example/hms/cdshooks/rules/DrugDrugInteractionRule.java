package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.InteractionSeverity;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.repository.DrugInteractionRepository;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pairs the proposed prescription's RxNorm with each other order on the
 * patient (in force, or a proposal the patient may soon be on, written in
 * the duplicate check's look-back, QA D1523, whose status the card then
 * names), looks up
 * {@code clinical.drug_interactions}, and emits one card per hit.
 *
 * <p>RxNorm is the join key. When either side has no resolved RxNorm
 * (freetext prescription, no catalog match) the rule cannot reason and
 * stays silent — the allergy/duplicate rules cover the freetext path.
 *
 * <p>Severity → CDS Hooks Indicator:
 * <ul>
 *   <li>{@code CONTRAINDICATED} → {@code critical}</li>
 *   <li>{@code MAJOR}           → {@code critical}</li>
 *   <li>{@code MODERATE}        → {@code warning}</li>
 *   <li>{@code MINOR / UNKNOWN} → {@code info}</li>
 * </ul>
 */
@Component
public class DrugDrugInteractionRule implements CdsRule {

    private static final String ID = "drug-drug-interaction";
    private static final String SOURCE_LABEL = "HMS Drug-Drug Interaction Check";

    /** Card wording in the caller's language (D61); English when built outside Spring. */
    private com.example.hms.cdshooks.CdsCardText text = com.example.hms.cdshooks.CdsCardText.ENGLISH;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCardText(com.example.hms.cdshooks.CdsCardText text) {
        this.text = text;
    }

    private final DrugInteractionRepository repository;

    public DrugDrugInteractionRule(DrugInteractionRepository repository) {
        this.repository = repository;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public List<CdsCard> evaluate(CdsRuleContext context) {
        String proposed = context.proposedRxnormCode();
        if (proposed == null || proposed.isBlank()) return List.of();

        List<CdsCard> cards = new ArrayList<>();
        for (Map.Entry<String, PrescriptionStatus> other : otherOrders(context, proposed).entrySet()) {
            String existing = other.getKey();
            // proposed is always one side of the pair; findInteractionBetween is bidirectional.
            String pairKey = canonicalPair(proposed, existing);
            int sep = pairKey.indexOf('|');
            repository.findInteractionBetween(pairKey.substring(0, sep), pairKey.substring(sep + 1))
                .filter(DrugInteraction::isActive)
                .ifPresent(di -> cards.add(buildCard(di, other.getValue())));
        }
        return cards;
    }

    /**
     * The other orders' RxNorms, in their order, each once: mapped to {@code null} when an order in force
     * carries it, else to the status of the proposal (a draft, an order waiting for its signature) that
     * does, so the card says the other order is not signed yet (QA D1523). A proposal older than the
     * duplicate check's look-back is left out: an abandoned draft is not an order the patient may soon be
     * on, and must not hold every later order on a critical interaction. A position with no order beside
     * it counts as in force, as before.
     */
    private static Map<String, PrescriptionStatus> otherOrders(CdsRuleContext context, String proposed) {
        Map<String, PrescriptionStatus> others = new LinkedHashMap<>();
        List<Prescription> orders = context.activePrescriptions();
        List<String> rxnorms = context.activePrescriptionRxnorms();
        LocalDateTime cutoff = LocalDateTime.now(ZoneId.systemDefault()).minus(DuplicateMedicationOrderRule.LOOKBACK);
        for (int i = 0; i < rxnorms.size(); i++) {
            String rx = rxnorms.get(i);
            if (rx == null || rx.isBlank() || rx.equals(proposed)) {
                continue;
            }
            Prescription order = i < orders.size() ? orders.get(i) : null;
            if (order == null || order.getStatus() == null || !order.getStatus().isProposal()) {
                others.put(rx, null);
            } else if (!others.containsKey(rx)
                && (order.getCreatedAt() == null || !order.getCreatedAt().isBefore(cutoff))) {
                // containsKey, not putIfAbsent: a null value (in force) must not be replaced.
                others.put(rx, order.getStatus());
            }
        }
        return others;
    }

    private static String canonicalPair(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private CdsCard buildCard(DrugInteraction di, PrescriptionStatus proposalStatus) {
        String summary = text.get("cds.ddi.summary", "Drug-drug interaction: {0} ↔ {1} ({2})",
            // QA D1134: the severity agrees with "interaction" (feminine in French and Spanish).
            di.getDrug1Name(), di.getDrug2Name(), text.severityWord(di.getSeverity()));
        String detail = composeDetail(di);
        if (proposalStatus != null) {
            String proposal = text.get("cds.ddi.proposal",
                "The other order is not signed yet (status: {0}).", text.enumWord(proposalStatus));
            detail = detail == null ? proposal : detail + " " + proposal;
        }
        return new CdsCard(
            summary,
            detail,
            mapIndicator(di.getSeverity()),
            new Source(text.get("cds.source.drugInteraction", SOURCE_LABEL), null, null),
            null, null, null, UUID.randomUUID().toString()
        );
    }

    private String composeDetail(DrugInteraction di) {
        StringBuilder detail = new StringBuilder();
        // QA D887: the seeded texts in the reader's language.
        String description = com.example.hms.service.support.DrugInteractionText.description(di);
        String recommendation = com.example.hms.service.support.DrugInteractionText.recommendation(di);
        if (description != null) detail.append(description);
        if (recommendation != null) {
            if (!detail.isEmpty()) detail.append(' ');
            detail.append(text.get("cds.ddi.recommendation", "Recommendation: {0}", recommendation));
        }
        return detail.isEmpty() ? null : detail.toString();
    }

    private static CdsCard.Indicator mapIndicator(InteractionSeverity severity) {
        if (severity == null) return CdsCard.Indicator.INFO;
        return switch (severity) {
            case CONTRAINDICATED, MAJOR -> CdsCard.Indicator.CRITICAL;
            case MODERATE -> CdsCard.Indicator.WARNING;
            case MINOR, UNKNOWN -> CdsCard.Indicator.INFO;
        };
    }
}
