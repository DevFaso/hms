package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.service.MedicationAllergyCdsService;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MedicationAllergyCdsServiceTest {

    private final PatientAllergyRepository allergyRepo = mock(PatientAllergyRepository.class);
    private final MedicationAllergyCdsService service = new MedicationAllergyCdsService(allergyRepo);

    private static CdsHookRequest signRequest(UUID patientId, String medText) {
        Map<String, Object> medRequest = Map.of(
            "resourceType", "MedicationRequest",
            "medicationCodeableConcept", Map.of("text", medText)
        );
        Map<String, Object> bundle = Map.of("entry", List.of(Map.of("resource", medRequest)));
        Map<String, Object> ctx = Map.of(
            "patientId", patientId.toString(),
            "draftOrders", bundle
        );
        return new CdsHookRequest("order-sign", "id", null, null, "Practitioner/x", ctx, null);
    }

    @Test
    void warnsWhenProposedMedMatchesActiveAllergy() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        PatientAllergy a = new PatientAllergy();
        a.setPatient(patient);
        a.setAllergenDisplay("Penicillin");
        a.setSeverity(AllergySeverity.SEVERE);
        a.setActive(true);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of(a));

        CdsHookResponse response = service.evaluate(signRequest(patientId, "Penicillin V 500 mg PO BID"));

        assertThat(response.cards()).hasSize(1);
        CdsCard card = response.cards().get(0);
        assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.critical);
        assertThat(card.summary())
            .contains("Penicillin V 500 mg PO BID")
            .contains("penicillin");
    }

    @Test
    void caseInsensitiveAndIgnoresInactiveAllergies() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        PatientAllergy resolved = new PatientAllergy();
        resolved.setPatient(patient);
        resolved.setAllergenDisplay("Sulfa");
        resolved.setActive(false);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of(resolved));

        CdsHookResponse response = service.evaluate(signRequest(patientId, "Sulfamethoxazole-trimethoprim"));
        assertThat(response.cards()).isEmpty();
    }

    @Test
    void noAllergyDataProducesNoCards() {
        UUID patientId = UUID.randomUUID();
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());
        assertThat(service.evaluate(signRequest(patientId, "Amoxicillin")).cards()).isEmpty();
    }
}
