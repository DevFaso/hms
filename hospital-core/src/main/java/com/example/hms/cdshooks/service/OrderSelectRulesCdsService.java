package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * CDS Hooks 1.0 {@code order-select} service. Fires earlier than
 * {@code order-sign} — the clinician has highlighted one or more draft
 * orders in CPOE but has not yet committed them — so the cards returned
 * here are advisory, not blocking. Reuses the same {@link CdsRuleEngine}
 * (drug-drug, duplicate-order, pediatric-dose) but scopes evaluation to
 * the entries identified in {@code context.selections}.
 *
 * <p>Selection narrowing — when {@code selections} is non-empty we evaluate
 * only the {@code draftOrders} entries whose {@code resource.id} appears
 * in the selection list. An empty {@code selections} array is treated as
 * "no narrowing", matching the {@code order-sign} behaviour and giving
 * portals a smooth fallback if they don't yet emit selection references.
 *
 * <p>RxNorm preference — the service prefers an RxNorm-system coding
 * extracted by {@link RxNormCodingExtractor}, falling back to the first
 * entry of {@code coding[]} the way {@link OrderSignRulesCdsService} does.
 * The engine's catalog lookup transparently handles either path since V93
 * (see {@link CdsRuleEngine#resolveCatalogItem(UUID, String)}).
 *
 * <p>UX contract — cards from {@code order-select} must not block submit
 * on {@code critical}; that gating remains exclusive to {@code order-sign}.
 * The descriptor's {@code description} field surfaces this contract.
 */
@Component
public class OrderSelectRulesCdsService implements CdsHookService {

    private static final String ID = "hms-order-select-rules";

    private final CdsRuleEngine ruleEngine;
    private final PatientRepository patientRepository;

    public OrderSelectRulesCdsService(CdsRuleEngine ruleEngine,
                                      PatientRepository patientRepository) {
        this.ruleEngine = ruleEngine;
        this.patientRepository = patientRepository;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        return new CdsServiceDescriptor(
            "order-select",
            ID,
            "Order selection safety preview",
            "Advisory drug-safety cards for draft orders the clinician has"
                + " selected in CPOE. Runs the HMS CDS rule engine"
                + " (drug-drug interaction, duplicate-order, pediatric-dose)"
                + " against each selected MedicationRequest. Cards are"
                + " advisory — final blocking gate remains at order-sign.",
            null
        );
    }

    @Override
    public CdsHookResponse evaluate(CdsHookRequest request) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return CdsHookResponse.empty();
        Patient patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
        if (patient == null) return CdsHookResponse.empty();

        UUID hospitalId = patient.getHospitalId();
        Set<String> selectionFilter = new HashSet<>(CdsHookContext.selectionIds(request));

        List<CdsCard> cards = CdsHookContext.medicationDrafts(request).stream()
            .filter(draft -> "MedicationRequest".equals(draft.get("resourceType")))
            .filter(draft -> matchesSelection(draft, selectionFilter))
            .map(MedicationDraftExtractor::extract)
            .filter(proposed -> proposed.name() != null || proposed.code() != null)
            .flatMap(proposed -> ruleEngine.evaluateProposedPrescription(
                patient,
                hospitalId,
                proposed.name(),
                proposed.code(),
                proposed.dose()
            ).stream())
            .toList();
        return CdsHookResponse.of(cards);
    }

    /**
     * A draft passes the selection filter when no selection narrowing was
     * supplied, or when its {@code id} matches one of the selection ids.
     */
    private static boolean matchesSelection(Map<String, Object> draft, Set<String> selectionFilter) {
        if (selectionFilter.isEmpty()) return true;
        Object id = draft.get("id");
        if (id == null) return false;
        return selectionFilter.contains(id.toString());
    }

}
