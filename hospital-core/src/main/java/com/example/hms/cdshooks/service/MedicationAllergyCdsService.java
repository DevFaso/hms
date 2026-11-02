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
import java.util.Map;
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

    /** Card wording in the caller's language (D61); English when built outside Spring. */
    private com.example.hms.cdshooks.CdsCardText text = com.example.hms.cdshooks.CdsCardText.ENGLISH;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCardText(com.example.hms.cdshooks.CdsCardText text) {
        this.text = text;
    }

    /** QA D1183: the catalogue the drug is resolved against; absent in unit tests built by hand. */
    private com.example.hms.repository.MedicationCatalogItemRepository catalogRepository;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCatalogRepository(com.example.hms.repository.MedicationCatalogItemRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

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

        List<PatientAllergy> allergies = allergyRepository.findByPatient_Id(patientId);
        // The patient lookup is only needed when DDI evaluation has work to do.
        // We defer it to the first MedicationRequest draft so requests with no
        // medication entries (allergy-only check) keep their lightweight path.
        Patient patient = null;
        boolean patientLookupAttempted = false;

        List<CdsCard> cards = new ArrayList<>();
        for (Map<String, Object> draft : CdsHookContext.medicationDrafts(request)) {
            if (!"MedicationRequest".equals(draft.get("resourceType"))) continue;

            ProposedMedication proposed = MedicationDraftExtractor.extract(draft);
            String medText = proposed.name();
            if (medText == null) {
                medText = extractMedicationText(draft);
            }
            if (medText == null) continue;

            if (!patientLookupAttempted) {
                patient = patientRepository.findByIdUnscoped(patientId).orElse(null);
                patientLookupAttempted = true;
            }
            // 1. Allergy card: QA D1183 (round 23) - the same rule as the
            // prescription's stop (name, INN class, cited brand, catalogue
            // item's generic and ATC), and the safety net for a name that
            // resolves to no active ingredient while a class allergy is on file.
            UUID hospitalId = patient != null ? patient.getHospitalId() : null;
            cards.addAll(allergyCards(medText, proposed.code(), hospitalId, allergies));

            // 2. Drug-drug interaction cards — added in v1.0 row 3.
            if (patient != null) {
                cards.addAll(evaluateDrugDrugInteractions(patient, draft));
            }
        }
        return CdsHookResponse.of(cards);
    }

    /* =====================================================================
       Drug-allergy path — unchanged from the v0.x service.
       ===================================================================== */

    /**
     * QA D1183 (round 23): the allergy cards for one proposed medication, the
     * rule the prescription's stop applies (shared through
     * {@link com.example.hms.service.allergy.AllergyDrugIdentity}):
     * <ol>
     *   <li>a CRITICAL card when an active allergy covers the drug - by name,
     *       allergy code, INN class, cited brand, or catalogue item's generic
     *       name and ATC group;</li>
     *   <li>else the CRITICAL safety-net card when the name resolves to no
     *       active ingredient while a drug-class allergy is on file;</li>
     *   <li>else the beta-lactam cross-reactivity WARNING (QA D793).</li>
     * </ol>
     * Also read by the medication-prescribe hook, so the prescriber sees the
     * same cards while composing the order.
     */
    public List<CdsCard> allergyCards(String medText, String code, UUID hospitalId, List<PatientAllergy> allergies) {
        if (medText == null || medText.isBlank() || allergies == null || allergies.isEmpty()) {
            return List.of();
        }
        com.example.hms.service.allergy.AllergyDrugIdentity identity =
            com.example.hms.service.allergy.AllergyDrugIdentity.of(medText, catalogItemsFor(medText, code, hospitalId));
        java.util.Optional<String> matched = identity.matchingAllergies(allergies).stream()
            .map(com.example.hms.service.allergy.AllergyDrugIdentity::displayOf)
            .findFirst()
            .or(() -> allergyCodeIn(identity.text(), allergies));
        if (matched.isPresent()) {
            return List.of(buildAllergyCard(medText, matched.get().trim()));
        }
        java.util.Optional<PatientAllergy> unverifiable = identity.unverifiableClassAllergy(allergies);
        if (unverifiable.isPresent()) {
            return List.of(com.example.hms.service.allergy.UnverifiableAllergyCard.of(medText,
                com.example.hms.service.allergy.AllergyDrugIdentity.displayOf(unverifiable.get())));
        }
        return crossReactivityCard(medText, allergies).map(List::of).orElse(List.of());
    }

    /** {@link #allergyCards} for the patient's active allergies, recorded at any hospital. */
    public List<CdsCard> allergyCardsForPatient(UUID patientId, UUID hospitalId, String medText, String code) {
        if (patientId == null) {
            return List.of();
        }
        return allergyCards(medText, code, hospitalId, allergyRepository.findByPatient_Id(patientId));
    }

    /** An active allergy recorded by code (3+ characters) that the drug's text carries. */
    private static java.util.Optional<String> allergyCodeIn(String drugText, List<PatientAllergy> allergies) {
        for (PatientAllergy a : allergies) {
            if (a == null || !a.isActive() || a.getAllergenCode() == null) continue;
            String code = com.example.hms.service.allergy.DrugClassAllergy.normalize(a.getAllergenCode());
            if (code.length() >= 3 && drugText.contains(code)) {
                return java.util.Optional.of(a.getAllergenCode());
            }
        }
        return java.util.Optional.empty();
    }

    /**
     * The catalogue items behind the proposed medication at the patient's
     * hospital: the item its code names, else the items its name names (the
     * dispense and prescription name rule) in this hospital's catalogue and the
     * national list (V95). Empty without a catalogue.
     */
    private List<com.example.hms.model.medication.MedicationCatalogItem> catalogItemsFor(
            String medText, String code, UUID hospitalId) {
        if (catalogRepository == null || hospitalId == null) {
            return List.of();
        }
        if (code != null && !code.isBlank()) {
            java.util.Optional<com.example.hms.model.medication.MedicationCatalogItem> coded =
                catalogRepository.findByHospitalIdAndCode(hospitalId, code.trim());
            if (coded.isPresent()) {
                return List.of(coded.get());
            }
        }
        org.springframework.data.domain.Page<com.example.hms.model.medication.MedicationCatalogItem> page =
            catalogRepository.findActivePage(hospitalId, org.springframework.data.domain.Pageable.unpaged());
        if (page == null) {
            return List.of();
        }
        return page.getContent().stream()
            .filter(item -> com.example.hms.service.pharmacy.DispenseVerificationService.namesCatalogItem(medText, item))
            .toList();
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
        String summary = text.get("cds.allergy.summary",
            "Allergy alert: {0} matches recorded allergy “{1}”", medication, matchedAllergen);
        String detail = text.get("cds.allergy.detail",
            "The patient has an active allergy entry that matches the proposed "
                + "medication. Review the patient's allergy list before signing.");
        return new CdsCard(
            summary,
            detail,
            CdsCard.Indicator.CRITICAL,
            new Source(text.get("cds.source.allergy", SOURCE_LABEL), null, null),
            null, null, null, java.util.UUID.randomUUID().toString()
        );
    }

    private java.util.Optional<CdsCard> crossReactivityCard(String medication, List<PatientAllergy> allergies) {
        return com.example.hms.service.allergy.BetaLactamCrossReactivity.crossReactiveAllergen(medication, allergies)
            .map(allergen -> new CdsCard(
                text.get("cds.allergy.crossReactivity.summary",
                    "Possible cross-reactivity: {0} and the documented allergy to {1}", medication, allergen),
                text.get("cds.allergy.crossReactivity.detail",
                    "{0} is a beta-lactam that can cross-react with the patient's documented allergy to {1}. "
                        + "Check what the reaction was before giving it. This is an advisory: the order is not blocked.",
                    medication, allergen),
                CdsCard.Indicator.WARNING,
                new Source(text.get("cds.source.allergy", SOURCE_LABEL), null, null),
                null, null, null, java.util.UUID.randomUUID().toString()));
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
