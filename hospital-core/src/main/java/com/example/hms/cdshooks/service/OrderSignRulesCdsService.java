package com.example.hms.cdshooks.service;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceDescriptor;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * CDS Hooks 1.0 {@code order-sign} service that runs the full
 * {@link CdsRuleEngine} (drug-drug, duplicate-order, pediatric-dose).
 *
 * <p>Co-exists with the legacy {@code hms-medication-allergy-check} —
 * external CDS clients can call either, and the in-process
 * {@code PrescriptionService} integration calls the engine directly.
 *
 * <p>The hook contract delivers draft orders as loosely-typed JSON.
 * We accept three shapes for the medication identifier so the service
 * is forgiving of FHIR-compliant clients and ad-hoc test harnesses
 * alike:
 * <ul>
 *   <li>{@code medicationCodeableConcept.text}</li>
 *   <li>{@code medicationCodeableConcept.coding[0].display | code}</li>
 *   <li>{@code medicationReference.display}</li>
 * </ul>
 */
@Component
public class OrderSignRulesCdsService implements CdsHookService {

    private static final String ID = "hms-order-sign-rules";

    private final CdsRuleEngine ruleEngine;
    private final PatientRepository patientRepository;

    public OrderSignRulesCdsService(CdsRuleEngine ruleEngine,
                                    PatientRepository patientRepository) {
        this.ruleEngine = ruleEngine;
        this.patientRepository = patientRepository;
    }

    @Override
    public CdsServiceDescriptor descriptor() {
        return new CdsServiceDescriptor(
            "order-sign",
            ID,
            "Medication order safety checks",
            "Runs the HMS CDS rule engine against a draft MedicationRequest:"
                + " drug-drug interaction, duplicate-order, and pediatric-dose.",
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
        List<CdsCard> cards = CdsHookContext.medicationDrafts(request).stream()
            .filter(draft -> "MedicationRequest".equals(draft.get("resourceType")))
            .map(ProposedMedication::fromDraft)
            .filter(proposed -> proposed.name() != null)
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

    /* =====================================================================
       Draft-payload extraction helpers
       ===================================================================== */

    private record ProposedMedication(String name, String code, String dose) {

        static ProposedMedication fromDraft(Map<String, Object> draft) {
            String name = textFromCodeableConcept(draft.get("medicationCodeableConcept"));
            if (name == null) name = textFromReference(draft.get("medicationReference"));
            String code = codeFromCodeableConcept(draft.get("medicationCodeableConcept"));
            String dose = doseFromDosageInstruction(draft.get("dosageInstruction"));
            return new ProposedMedication(name, code, dose);
        }

        private static String textFromCodeableConcept(Object value) {
            if (!(value instanceof Map<?, ?> map)) return null;
            String text = nonBlank(map.get("text"));
            if (text != null) return text;
            return textFromFirstCoding(map.get("coding"));
        }

        private static String codeFromCodeableConcept(Object value) {
            if (!(value instanceof Map<?, ?> map)) return null;
            Object coding = map.get("coding");
            if (!(coding instanceof List<?> list) || list.isEmpty()) return null;
            if (!(list.get(0) instanceof Map<?, ?> first)) return null;
            return nonBlank(first.get("code"));
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

        private static String doseFromDosageInstruction(Object value) {
            if (!(value instanceof List<?> list) || list.isEmpty()) return null;
            Object first = list.get(0);
            if (!(first instanceof Map<?, ?> instr)) return null;
            String text = nonBlank(instr.get("text"));
            if (text != null) return text;
            return doseFromQuantity(instr.get("doseQuantity"));
        }

        private static String doseFromQuantity(Object value) {
            if (!(value instanceof Map<?, ?> q)) return null;
            Object v = q.get("value");
            Object u = q.get("unit");
            if (v == null) return null;
            return u == null
                ? v.toString()
                : v + " " + u.toString().toLowerCase(Locale.ROOT);
        }

        private static String nonBlank(Object value) {
            return (value instanceof String s && !s.isBlank()) ? s : null;
        }
    }
}
