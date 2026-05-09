package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.service.MedicationPrescribeRulesCdsService;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.terminology.TerminologyCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicationPrescribeRulesCdsServiceTest {

    private final CdsRuleEngine engine = mock(CdsRuleEngine.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final MedicationCatalogItemRepository catalog = mock(MedicationCatalogItemRepository.class);
    private final MedicationPrescribeRulesCdsService service =
        new MedicationPrescribeRulesCdsService(engine, patients, catalog);

    private static CdsHookRequest prescribeRequest(UUID patientId,
                                                   List<Map<String, Object>> drafts) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("entry", drafts.stream()
            .map(d -> Map.<String, Object>of("resource", d))
            .toList());
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("patientId", patientId.toString());
        ctx.put("medications", bundle);
        return new CdsHookRequest("medication-prescribe", "id", null, null, "Practitioner/x", ctx, null);
    }

    private static Map<String, Object> medRequest(String text, String code, String dose) {
        Map<String, Object> mr = new HashMap<>();
        mr.put("resourceType", "MedicationRequest");
        mr.put("medicationCodeableConcept", Map.of(
            "text", text,
            "coding", List.of(Map.of("code", code, "display", text))));
        mr.put("dosageInstruction", List.of(Map.of("text", dose)));
        return mr;
    }

    private static Map<String, Object> medRequestWithRxnorm(String text, String rxcui, String dose) {
        Map<String, Object> mr = new HashMap<>();
        mr.put("resourceType", "MedicationRequest");
        mr.put("medicationCodeableConcept", Map.of(
            "text", text,
            "coding", List.of(
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", rxcui))));
        mr.put("dosageInstruction", List.of(Map.of("text", dose)));
        return mr;
    }

    @Test
    @DisplayName("descriptor advertises medication-prescribe with stable id")
    void descriptorAdvertisesMedicationPrescribeHookWithStableId() {
        var d = service.descriptor();
        assertThat(d.hook()).isEqualTo("medication-prescribe");
        assertThat(d.id()).isEqualTo("hms-medication-prescribe-rules");
        assertThat(d.title()).isNotBlank();
    }

    @Test
    @DisplayName("returns empty when patient is unknown")
    void emptyResponseWhenPatientUnknown() {
        UUID patientId = UUID.randomUUID();
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        CdsHookResponse response = service.evaluate(prescribeRequest(patientId,
            List.of(medRequest("Amoxicillin", "AMOX", "500 mg"))));

        assertThat(response.cards()).isEmpty();
        verify(engine, never()).evaluateProposedPrescription(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("delegates to engine for each MedicationRequest in the bundle")
    void delegatesForEachMedicationRequest() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = patient(patientId, hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        CdsCard card = warningCard("warn");
        when(engine.evaluateProposedPrescription(any(), eq(hospitalId), any(), any(), any()))
            .thenReturn(List.of(card));
        // catalog lookup misses for both → no tall-man advisory.
        when(catalog.findByHospitalIdAndCode(eq(hospitalId), any())).thenReturn(Optional.empty());
        when(catalog.findActiveByHospitalIdAndRxnormCode(eq(hospitalId), any()))
            .thenReturn(List.of());

        CdsHookResponse response = service.evaluate(prescribeRequest(patientId, List.of(
            medRequest("Aspirin", "ASA", "100 mg"),
            medRequest("Warfarin", "WAR", "5 mg")
        )));

        assertThat(response.cards()).hasSize(2);
        verify(engine).evaluateProposedPrescription(patient, hospitalId, "Aspirin", "ASA", "100 mg");
        verify(engine).evaluateProposedPrescription(patient, hospitalId, "Warfarin", "WAR", "5 mg");
    }

    @Test
    @DisplayName("RxNorm coding is preferred over the first coding entry")
    void prefersRxnormSystemCoding() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = patient(patientId, hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(engine.evaluateProposedPrescription(any(), any(), any(), any(), any()))
            .thenReturn(List.of());
        when(catalog.findByHospitalIdAndCode(eq(hospitalId), any())).thenReturn(Optional.empty());
        when(catalog.findActiveByHospitalIdAndRxnormCode(eq(hospitalId), any()))
            .thenReturn(List.of());

        service.evaluate(prescribeRequest(patientId, List.of(
            medRequestWithRxnorm("Aspirin 100 mg", "1191", "100 mg")
        )));

        verify(engine).evaluateProposedPrescription(
            patient, hospitalId, "Aspirin 100 mg", "1191", "100 mg");
    }

    @Test
    @DisplayName("emits a tall-man advisory when the catalog row carries one")
    void emitsTallManAdvisoryWhenConfigured() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = patient(patientId, hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(engine.evaluateProposedPrescription(any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        MedicationCatalogItem item = MedicationCatalogItem.builder()
            .code("PRED-5")
            .nameFr("Prednisone 5 mg")
            .genericName("prednisone")
            .tallManName("predniSONE")
            .hospital(hospital(hospitalId))
            .build();
        when(catalog.findByHospitalIdAndCode(hospitalId, "PRED-5")).thenReturn(Optional.of(item));
        // findActiveByHospitalIdAndRxnormCode is not consulted on this path —
        // mark it lenient so an unstubbed-call strict mode doesn't fail.
        lenient().when(catalog.findActiveByHospitalIdAndRxnormCode(any(), any()))
            .thenReturn(List.of());

        CdsHookResponse response = service.evaluate(prescribeRequest(patientId, List.of(
            medRequest("Prednisone 5 mg", "PRED-5", "1 tab")
        )));

        assertThat(response.cards())
            .extracting(CdsCard::summary)
            .contains("Confusable name — verify spelling");
        assertThat(response.cards())
            .extracting(CdsCard::detail)
            .anyMatch(d -> d != null && d.contains("predniSONE"));
        assertThat(response.cards())
            .extracting(CdsCard::indicator)
            .contains(CdsCard.Indicator.INFO);
    }

    @Test
    @DisplayName("ignores non-MedicationRequest payloads (medication-prescribe is medication-only)")
    void ignoresNonMedicationRequestEntries() {
        UUID patientId = UUID.randomUUID();
        Patient patient = patient(patientId, UUID.randomUUID());
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        Map<String, Object> serviceRequest = Map.of(
            "resourceType", "ServiceRequest",
            "code", Map.of("text", "MRI brain")
        );

        assertThat(service.evaluate(prescribeRequest(patientId, List.of(serviceRequest))).cards())
            .isEmpty();
        verify(engine, never()).evaluateProposedPrescription(any(), any(), any(), any(), any());
    }

    /* helpers */

    private static Patient patient(UUID patientId, UUID hospitalId) {
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        return patient;
    }

    private static Hospital hospital(UUID id) {
        Hospital h = new Hospital();
        h.setId(id);
        return h;
    }

    private static CdsCard warningCard(String summary) {
        return new CdsCard(summary, null, CdsCard.Indicator.WARNING,
            new Source("test", null, null), null, null, null, "u");
    }
}
