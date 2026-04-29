package com.example.hms.cdshooks.rules;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.enums.InteractionSeverity;
import com.example.hms.model.medication.DrugInteraction;
import com.example.hms.repository.DrugInteractionRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pairs the proposed prescription's RxNorm with each currently-active
 * prescription on the patient, looks up
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
        java.util.Set<String> seenPairs = new java.util.HashSet<>();
        for (String existing : context.activePrescriptionRxnorms()) {
            if (existing == null || existing.isBlank() || existing.equals(proposed)) continue;
            String pairKey = canonicalPair(proposed, existing);
            if (!seenPairs.add(pairKey)) continue;
            Optional<DrugInteraction> hit = repository.findInteractionBetween(proposed, existing);
            hit.filter(DrugInteraction::isActive).ifPresent(di -> cards.add(buildCard(di)));
        }
        return cards;
    }

    private static String canonicalPair(String a, String b) {
        return a.compareTo(b) <= 0 ? a + "|" + b : b + "|" + a;
    }

    private CdsCard buildCard(DrugInteraction di) {
        String summary = "Drug-drug interaction: " + di.getDrug1Name()
            + " ↔ " + di.getDrug2Name() + " (" + di.getSeverity() + ")";
        StringBuilder detail = new StringBuilder();
        if (di.getDescription() != null) detail.append(di.getDescription());
        if (di.getRecommendation() != null) {
            if (detail.length() > 0) detail.append(" ");
            detail.append("Recommendation: ").append(di.getRecommendation());
        }
        return new CdsCard(
            summary,
            detail.length() == 0 ? null : detail.toString(),
            mapIndicator(di.getSeverity()),
            new Source(SOURCE_LABEL, null, null),
            null, null, null, UUID.randomUUID().toString()
        );
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
