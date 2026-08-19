package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.rules.CdsRuleContext;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.rules.DrugDrugInteractionRule;
import com.example.hms.cdshooks.service.MedicationDraftExtractor.ProposedMedication;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * On {@code order-sign} for medication orders, returns:
 *
 * <ol>
 *   <li>A <strong>critical</strong> drug-allergy card when the proposed
 *       medication name overlaps an active allergy on the patient's chart.
 *       Deliberately a freetext substring match — the West-African
 *       deployments this codebase targets often record allergies as
 *       "penicillin", "sulfa", "aspirin" rather than as coded values.</li>
 *   <li>One <strong>drug-drug interaction</strong> card per coexisting
 *       active prescription that pairs with the proposed medication in
 *       {@code clinical.drug_interactions} (RxNorm-keyed). Severity maps
 *       to the same critical/warning/info indicators the
 *       {@code DrugDrugInteractionRule} uses on {@code order-sign}.</li>
 * </ol>
 *
 * <p>v1.0 / Clinical Safety / Drug-drug interaction check (roadmap row 3)
 * extended this service from allergy-only to allergy + DDI. The service
 * id stays {@code hms-medication-allergy-check} so external CDS clients
 * (Cerner / Epic sandboxes) keep their cached descriptor pointers; the
 * descriptor's {@code title} and {@code description} are updated to
 * reflect the broader scope.
 *
 * <p>The DDI evaluation reuses the engine's
 * {@link CdsRuleEngine#buildContext(Patient, UUID, String, String, String)}
 * to inherit the active-prescription scoping, RxNorm batch resolution,
 * and the V93 RxNorm crosswalk fallback in a single place. We invoke
 * {@link DrugDrugInteractionRule} directly rather than the full engine
 * so a future addition to the rule catalogue cannot silently change the
 * surface of this CDS service.
 */
@Component
public class MedicationAllergyCdsService implements CdsHookService {

    private static final String ID = "hms-medication-allergy-check";
    private static final String SOURCE_LABEL = "HMS Allergy Check";

    private final PatientAllergyRepository allergyRepository;
    private final PatientRepository patientRepository;
    private final CdsRuleEngine ruleEngine;
    private final DrugDrugInteractionRule drugDrugInteractionRule;

    public MedicationAllergyCdsService(PatientAllergyRepository allergyRepository,
                                       PatientRepository patientRepository,
                                       CdsRuleEngine ruleEngine,
                                       DrugDrugInteractionRule drugDrugInteractionRule) {
        this.allergyRepository = allergyRepository;
        this.patientRepository = patientRepository;
        this.ruleEngine = ruleEngine;
        this.drugDrugInteractionRule = drugDrugInteractionRule;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        return new CdsServiceDescriptor(
            "order-sign",
            ID,
            "Drug allergy + drug-drug interaction check",
            "Warns when a proposed MedicationRequest matches an active "
                + "allergy on the patient's chart, and returns a card per "
                + "drug-drug interaction with any coexisting active "
                + "prescription (RxNorm-keyed against clinical.drug_interactions).",
            null
        );
    }

    @Override
    public CdsHookResponse evaluate(CdsHookRequest request) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return CdsHookResponse.empty();

        Set<String> allergyHaystack = collectAllergyTerms(allergyRepository.findByPatient_Id(patientId));
        // The patient lookup is only needed when DDI evaluation has work to do.
        // We defer it to the first MedicationRequest draft so requests with no
        // medication entries (allergy-only check) keep their lightweight path.
        Patient patient = null;
        boolean patientLookupAttempted = false;

        List<CdsCard> cards = new ArrayList<>();
        for (Map<String, Object> draft : CdsHookContext.medicationDrafts(request)) {
            if (!"MedicationRequest".equals(draft.get("resourceType"))) continue;

            String medText = MedicationDraftExtractor.extract(draft).name();
            if (medText == null) {
                medText = extractMedicationText(draft);
            }
            if (medText == null) continue;

            // 1. Allergy card — same critical-match semantics as before.
            if (!allergyHaystack.isEmpty()) {
                CdsCard allergy = allergyCard(medText, allergyHaystack);
                if (allergy != null) cards.add(allergy);
            }

            // 2. Drug-drug interaction cards — added in v1.0 row 3.
            if (!patientLookupAttempted) {
                patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
                patientLookupAttempted = true;
            }
            if (patient != null) {
                cards.addAll(evaluateDrugDrugInteractions(patient, draft));
            }
        }
        return CdsHookResponse.of(cards);
    }

    /* =====================================================================
       Drug-allergy path — unchanged from the v0.x service.
       ===================================================================== */

    private CdsCard allergyCard(String medText, Set<String> haystacks) {
        String norm = medText.toLowerCase(Locale.ROOT);
        return haystacks.stream()
            .filter(norm::contains)
            .findFirst()
            .map(allergen -> buildAllergyCard(medText, allergen))
            .orElse(null);
    }

    private Set<String> collectAllergyTerms(List<PatientAllergy> allergies) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        for (PatientAllergy a : allergies) {
            if (!a.isActive()) continue;
            addNormalised(out, a.getAllergenDisplay());
            addNormalised(out, a.getAllergenCode());
        }
        return out;
    }

    private static void addNormalised(Set<String> sink, String value) {
        if (value == null) return;
        String trimmed = value.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() >= 3) sink.add(trimmed);
    }

    /**
     * Fallback extractor for the medication name. Mirrors the lighter
     * extraction the original service used — kept so allergy-only payloads
     * (no coding[] at all) still match. The shared
     * {@link MedicationDraftExtractor} already covers this for the common
     * case; we only fall through here when its name extraction returned null.
     */
    private static String extractMedicationText(Map<String, Object> draft) {
        String fromConcept = textFromCodeableConcept(draft.get("medicationCodeableConcept"));
        if (fromConcept != null) return fromConcept;
        return textFromReference(draft.get("medicationReference"));
    }

    private static String textFromCodeableConcept(Object value) {
        if (!(value instanceof Map<?, ?> map)) return null;
        String text = nonBlank(map.get("text"));
        if (text != null) return text;
        return textFromFirstCoding(map.get("coding"));
    }

    private static String textFromFirstCoding(Object coding) {
        if (!(coding instanceof List<?> list) || list.isEmpty()) return null;
        if (!(list.get(0) instanceof Map<?, ?> first)) return null;
        String display = nonBlank(first.get("display"));
        return display != null ? display : nonBlank(first.get("code"));
    }

    private static String textFromReference(Object value) {
        if (!(value instanceof Map<?, ?> ref)) return null;
        return nonBlank(ref.get("display"));
    }

    private static String nonBlank(Object value) {
        return (value instanceof String s && !s.isBlank()) ? s : null;
    }

    private CdsCard buildAllergyCard(String medication, String matchedAllergen) {
        String summary = "Allergy alert: " + medication
            + " matches recorded allergy “" + matchedAllergen + "”";
        String detail =
            "The patient has an active allergy entry that matches the proposed "
                + "medication. Review the patient's allergy list before signing.";
        return new CdsCard(
            summary,
            detail,
            CdsCard.Indicator.CRITICAL,
            new Source(SOURCE_LABEL, null, null),
            null, null, null, java.util.UUID.randomUUID().toString()
        );
    }

    /* =====================================================================
       Drug-drug interaction path — v1.0 row 3 add-on.
       ===================================================================== */

    /**
     * Builds a {@link CdsRuleContext} for the proposed medication and runs
     * the {@link DrugDrugInteractionRule} in isolation. Returns the cards
     * the rule emits — one per coexisting active prescription that has a
     * registered interaction. The engine's {@code buildContext} performs
     * RxNorm resolution, active-prescription scoping, and the V93
     * RxNorm-crosswalk fallback in a single place, so this service does
     * not need to duplicate any of that work.
     */
    private List<CdsCard> evaluateDrugDrugInteractions(Patient patient, Map<String, Object> draft) {
        ProposedMedication proposed = MedicationDraftExtractor.extract(draft);
        if (proposed.name() == null && proposed.code() == null) return List.of();
        CdsRuleContext context = ruleEngine.buildContext(
            patient,
            patient.getHospitalId(),
            proposed.name(),
            proposed.code(),
            proposed.dose()
        );
        if (context == null) return List.of();
        List<CdsCard> cards = drugDrugInteractionRule.evaluate(context);
        return cards == null ? List.of() : cards;
    }
}
