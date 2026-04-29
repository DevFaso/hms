package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * On {@code order-sign} for medication orders, returns a critical card
 * if the proposed medication name overlaps with any active allergy on
 * the patient's record.
 *
 * <p>This is a deliberately simple text-match check. RxNorm/ATC lookup
 * goes in P1 once those code systems are bound (gap #5). Even without
 * coded data, a string match catches the common case in West-African
 * deployments where allergies are recorded as freetext (e.g. "penicillin",
 * "sulfa", "aspirin").
 */
@Component
public class MedicationAllergyCdsService implements CdsHookService {

    private static final String ID = "hms-medication-allergy-check";
    private static final String SOURCE_LABEL = "HMS Allergy Check";

    private final PatientAllergyRepository allergyRepository;

    public MedicationAllergyCdsService(PatientAllergyRepository allergyRepository) {
        this.allergyRepository = allergyRepository;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        return new CdsServiceDescriptor(
            "order-sign",
            ID,
            "Drug-allergy interaction check",
            "Warns when a proposed MedicationRequest matches an active allergy "
                + "on the patient's chart.",
            null
        );
    }

    @Override
    public CdsHookResponse evaluate(CdsHookRequest request) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return CdsHookResponse.empty();

        Set<String> haystacks = collectAllergyTerms(allergyRepository.findByPatient_Id(patientId));
        if (haystacks.isEmpty()) return CdsHookResponse.empty();

        List<CdsCard> cards = new ArrayList<>();
        for (Map<String, Object> draft : CdsHookContext.medicationDrafts(request)) {
            CdsCard card = cardForDraft(draft, haystacks);
            if (card != null) cards.add(card);
        }
        return CdsHookResponse.of(cards);
    }

    /** Returns a critical card when the draft's medication text overlaps an active allergy term, else null. */
    private CdsCard cardForDraft(Map<String, Object> draft, Set<String> haystacks) {
        if (!"MedicationRequest".equals(draft.get("resourceType"))) return null;
        String medText = extractMedicationText(draft);
        if (medText == null) return null;
        String norm = medText.toLowerCase(Locale.ROOT);
        return haystacks.stream()
            .filter(norm::contains)
            .findFirst()
            .map(allergen -> buildCard(medText, allergen))
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

    private CdsCard buildCard(String medication, String matchedAllergen) {
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
}
