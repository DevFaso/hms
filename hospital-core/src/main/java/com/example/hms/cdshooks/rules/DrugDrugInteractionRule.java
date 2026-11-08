package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.InteractionSeverity;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Prescription;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.repository.DrugInteractionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Pairs the proposed prescription's RxNorm with each other order on the
 * patient (in force, or a proposal the patient may soon be on, QA D1523,
 * whose status the card then names), looks up
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

        Map<String, PrescriptionStatus> proposalOnly = proposalOnlyStatuses(context);
        List<CdsCard> cards = new ArrayList<>();
        for (String existing : new LinkedHashSet<>(context.activePrescriptionRxnorms())) {
            if (existing == null || existing.isBlank() || existing.equals(proposed)) {
                continue;
            }
            // proposed is always one side of the pair; findInteractionBetween is bidirectional.
            String pairKey = canonicalPair(proposed, existing);
            int sep = pairKey.indexOf('|');
            repository.findInteractionBetween(pairKey.substring(0, sep), pairKey.substring(sep + 1))
                .filter(DrugInteraction::isActive)
                .ifPresent(di -> cards.add(buildCard(di, proposalOnly.get(existing))));
        }
        return cards;
    }

    /**
     * QA D1523: the status of a proposal (a draft, an order waiting for its signature) for each RxNorm
     * that only proposals carry, so the card says the other order is not signed yet. An RxNorm an
     * order in force carries is not in the map: that order is the one the card is about.
     */
    private static Map<String, PrescriptionStatus> proposalOnlyStatuses(CdsRuleContext context) {
        Map<String, PrescriptionStatus> proposals = new HashMap<>();
        Set<String> inForce = new HashSet<>();
        List<Prescription> orders = context.activePrescriptions();
        List<String> rxnorms = context.activePrescriptionRxnorms();
        for (int i = 0; i < rxnorms.size() && i < orders.size(); i++) {
            String rx = rxnorms.get(i);
            Prescription order = orders.get(i);
            if (rx == null || order == null) {
                continue;
            }
            if (order.getStatus() != null && order.getStatus().isProposal()) {
                proposals.putIfAbsent(rx, order.getStatus());
            } else {
                inForce.add(rx);
            }
        }
        proposals.keySet().removeAll(inForce);
        return proposals;
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
