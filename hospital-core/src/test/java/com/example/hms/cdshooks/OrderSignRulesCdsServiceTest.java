package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.service.OrderSignRulesCdsService;
import com.example.hms.model.Patient;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.Test;

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

class OrderSignRulesCdsServiceTest {

    private final CdsRuleEngine engine = mock(CdsRuleEngine.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final OrderSignRulesCdsService service = new OrderSignRulesCdsService(engine, patients);

    private static CdsHookRequest signRequest(UUID patientId, Map<String, Object> medRequestPayload) {
        Map<String, Object> bundle = Map.of("entry", List.of(Map.of("resource", medRequestPayload)));
        Map<String, Object> ctx = Map.of(
            "patientId", patientId.toString(),
            "draftOrders", bundle
        );
        return new CdsHookRequest("order-sign", "id", null, null, "Practitioner/x", ctx, null);
    }

    private static Map<String, Object> medRequestWithCoding(String text, String code, String dose) {
        return Map.of(
            "resourceType", "MedicationRequest",
            "medicationCodeableConcept", Map.of(
                "text", text,
                "coding", List.of(Map.of("code", code, "display", text))),
            "dosageInstruction", List.of(Map.of("text", dose))
        );
    }

    @Test
    void descriptorAdvertisesOrderSignHookWithStableId() {
        var d = service.descriptor();
        assertThat(d.hook()).isEqualTo("order-sign");
        assertThat(d.id()).isEqualTo("hms-order-sign-rules");
        assertThat(d.title()).isNotBlank();
    }

    @Test
    void emptyResponseWhenPatientUnknown() {
        UUID patientId = UUID.randomUUID();
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        CdsHookResponse response = service.evaluate(signRequest(patientId,
            medRequestWithCoding("Amoxicillin", "AMOX", "500 mg")));

        assertThat(response.cards()).isEmpty();
        verify(engine, never()).evaluateProposedPrescription(any(), any(), any(), any(), any());
    }

    @Test
    void emptyResponseWhenPatientIdMissing() {
        Map<String, Object> ctx = Map.of(
            "draftOrders", Map.of("entry", List.of(
                Map.of("resource", medRequestWithCoding("Amoxicillin", "AMOX", "500 mg")))));
        CdsHookRequest req = new CdsHookRequest("order-sign", "id", null, null, "u", ctx, null);

        CdsHookResponse response = service.evaluate(req);
        assertThat(response.cards()).isEmpty();
    }

    @Test
    void delegatesToEngineWithExtractedFields() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        CdsCard card = new CdsCard("test", null, CdsCard.Indicator.WARNING,
            new Source("t", null, null), null, null, null, "u");
        when(engine.evaluateProposedPrescription(any(), eq(hospitalId), any(), any(), any()))
            .thenReturn(List.of(card));

        CdsHookResponse response = service.evaluate(signRequest(patientId,
            medRequestWithCoding("Amoxicillin 500 mg", "AMOX-500", "500 mg PO BID")));

        assertThat(response.cards()).containsExactly(card);
        verify(engine).evaluateProposedPrescription(
            patient, hospitalId,
            "Amoxicillin 500 mg", "AMOX-500", "500 mg PO BID");
    }

    @Test
    void ignoresNonMedicationRequestDrafts() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        Map<String, Object> nonMed = Map.of(
            "resourceType", "ServiceRequest",
            "code", Map.of("text", "MRI brain")
        );

        CdsHookResponse response = service.evaluate(signRequest(patientId, nonMed));
        assertThat(response.cards()).isEmpty();
        verify(engine, never()).evaluateProposedPrescription(any(), any(), any(), any(), any());
    }

    @Test
    void quantityFallbackWhenDosageTextMissing() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(engine.evaluateProposedPrescription(any(), any(), any(), any(), any()))
            .thenReturn(List.of());

        Map<String, Object> mr = Map.of(
            "resourceType", "MedicationRequest",
            "medicationCodeableConcept", Map.of("text", "Amoxicillin"),
            "dosageInstruction", List.of(Map.of(
                "doseQuantity", Map.of("value", 500, "unit", "MG")))
        );

        service.evaluate(signRequest(patientId, mr));

        verify(engine).evaluateProposedPrescription(
            any(), any(), eq("Amoxicillin"), any(), eq("500 mg"));
    }
}
