package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.service.OrderSelectRulesCdsService;
import com.example.hms.model.Patient;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OrderSelectRulesCdsServiceTest {

    private final CdsRuleEngine engine = mock(CdsRuleEngine.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final OrderSelectRulesCdsService service =
        new OrderSelectRulesCdsService(engine, patients);

    private static CdsHookRequest selectRequest(UUID patientId,
                                                List<String> selections,
                                                List<Map<String, Object>> drafts) {
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("entry", drafts.stream()
            .map(d -> Map.<String, Object>of("resource", d))
            .toList());
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("patientId", patientId.toString());
        ctx.put("draftOrders", bundle);
        if (selections != null) ctx.put("selections", selections);
        return new CdsHookRequest("order-select", "id", null, null, "Practitioner/x", ctx, null);
    }

    private static Map<String, Object> medRequest(String id, String text, String code, String dose) {
        Map<String, Object> coding = Map.of("code", code, "display", text);
        Map<String, Object> mr = new HashMap<>();
        mr.put("resourceType", "MedicationRequest");
        mr.put("id", id);
        mr.put("medicationCodeableConcept", Map.of("text", text, "coding", List.of(coding)));
        mr.put("dosageInstruction", List.of(Map.of("text", dose)));
        return mr;
    }

    private static Map<String, Object> medRequestWithRxnorm(String id, String text, String rxcui, String dose) {
        Map<String, Object> mr = new HashMap<>();
        mr.put("resourceType", "MedicationRequest");
        mr.put("id", id);
        mr.put("medicationCodeableConcept", Map.of(
            "text", text,
            "coding", List.of(
                Map.of("system", "http://www.whocc.no/atc", "code", "B01AC06"),
                Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", rxcui))
        ));
        mr.put("dosageInstruction", List.of(Map.of("text", dose)));
        return mr;
    }

    @Test
    @DisplayName("descriptor advertises order-select with stable id")
    void descriptorAdvertisesOrderSelectHookWithStableId() {
        var d = service.descriptor();
        assertThat(d.hook()).isEqualTo("order-select");
        assertThat(d.id()).isEqualTo("hms-order-select-rules");
        assertThat(d.title()).isNotBlank();
        assertThat(d.description()).contains("advisory");
    }

    @Test
    @DisplayName("returns no cards when patientId is missing or unknown")
    void emptyResponseWhenPatientUnknown() {
        UUID patientId = UUID.randomUUID();
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        CdsHookResponse response = service.evaluate(
            selectRequest(patientId, List.of(),
                List.of(medRequest("mr-1", "Amoxicillin", "AMOX", "500 mg"))));

        assertThat(response.cards()).isEmpty();
        verify(engine, never()).evaluateProposedPrescription(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("evaluates every draft when selections list is empty")
    void evaluatesAllDraftsWhenSelectionsEmpty() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        CdsCard card = new CdsCard("warn", null, CdsCard.Indicator.WARNING,
            new Source("test", null, null), null, null, null, "u");
        when(engine.evaluateProposedPrescription(any(), eq(hospitalId), any(), any(), any()))
            .thenReturn(List.of(card));

        CdsHookResponse response = service.evaluate(
            selectRequest(patientId, List.of(),
                List.of(
                    medRequest("mr-1", "Aspirin", "ASA", "100 mg"),
                    medRequest("mr-2", "Warfarin", "WAR", "5 mg"))));

        assertThat(response.cards()).hasSize(2);
        verify(engine).evaluateProposedPrescription(patient, hospitalId, "Aspirin", "ASA", "100 mg");
        verify(engine).evaluateProposedPrescription(patient, hospitalId, "Warfarin", "WAR", "5 mg");
    }

    @Test
    @DisplayName("only evaluates drafts whose id is in the selections list")
    void narrowsToSelectedDrafts() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(engine.evaluateProposedPrescription(any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        // FHIR-reference form ("MedicationRequest/mr-2") — exercises the
        // selectionIds slash-stripping path so we know the test fails if
        // future refactors change the reference parsing.
        service.evaluate(selectRequest(patientId,
            List.of("MedicationRequest/mr-2"),
            List.of(
                medRequest("mr-1", "Aspirin", "ASA", "100 mg"),
                medRequest("mr-2", "Warfarin", "WAR", "5 mg"),
                medRequest("mr-3", "Paracetamol", "PARA", "500 mg"))));

        verify(engine).evaluateProposedPrescription(any(), any(), eq("Warfarin"), eq("WAR"), any());
        verify(engine, never())
            .evaluateProposedPrescription(any(), any(), eq("Aspirin"), any(), any());
        verify(engine, never())
            .evaluateProposedPrescription(any(), any(), eq("Paracetamol"), any(), any());
    }

    @Test
    @DisplayName("prefers RxNorm-system code over the first coding entry")
    void prefersRxnormSystemCoding() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(engine.evaluateProposedPrescription(any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        service.evaluate(selectRequest(patientId, List.of(),
            List.of(medRequestWithRxnorm("mr-1", "Aspirin 100 mg", "1191", "100 mg"))));

        // ATC ("B01AC06") would have been picked by the legacy first-coding path;
        // the RxNorm-preferring extractor must yield "1191" instead.
        verify(engine).evaluateProposedPrescription(
            patient, null, "Aspirin 100 mg", "1191", "100 mg");
    }

    @Test
    @DisplayName("ignores non-MedicationRequest draft entries")
    void ignoresNonMedicationRequestDrafts() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        Map<String, Object> nonMed = Map.of(
            "resourceType", "ServiceRequest",
            "id", "sr-1",
            "code", Map.of("text", "MRI brain")
        );

        CdsHookResponse response = service.evaluate(selectRequest(patientId, List.of(), List.of(nonMed)));
        assertThat(response.cards()).isEmpty();
        verify(engine, never()).evaluateProposedPrescription(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("returns empty when patientId is missing from context")
    void emptyWhenPatientIdMissing() {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("draftOrders", Map.of("entry", List.of(
            Map.of("resource", medRequest("mr-1", "Amoxicillin", "AMOX", "500 mg")))));
        CdsHookRequest req = new CdsHookRequest("order-select", "id", null, null, "u", ctx, null);

        assertThat(service.evaluate(req).cards()).isEmpty();
    }
}
