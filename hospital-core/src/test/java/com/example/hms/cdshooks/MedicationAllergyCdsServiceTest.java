package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.rules.CdsRuleContext;
import com.example.hms.cdshooks.rules.CdsRuleEngine;
import com.example.hms.cdshooks.rules.DrugDrugInteractionRule;
import com.example.hms.cdshooks.service.MedicationAllergyCdsService;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.terminology.TerminologyCodes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MedicationAllergyCdsServiceTest {

    private final PatientAllergyRepository allergyRepo = mock(PatientAllergyRepository.class);
    private final PatientRepository patientRepo = mock(PatientRepository.class);
    private final CdsRuleEngine ruleEngine = mock(CdsRuleEngine.class);
    private final DrugDrugInteractionRule ddiRule = mock(DrugDrugInteractionRule.class);
    private final MedicationAllergyCdsService service = new MedicationAllergyCdsService(
        allergyRepo, patientRepo, ruleEngine, ddiRule);

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

    private static CdsHookRequest signRequestWithRxnorm(UUID patientId, String medText, String rxcui) {
        Map<String, Object> medRequest = Map.of(
            "resourceType", "MedicationRequest",
            "medicationCodeableConcept", Map.of(
                "text", medText,
                "coding", List.of(Map.of("system", TerminologyCodes.SYSTEM_RXNORM, "code", rxcui)))
        );
        Map<String, Object> bundle = Map.of("entry", List.of(Map.of("resource", medRequest)));
        Map<String, Object> ctx = Map.of(
            "patientId", patientId.toString(),
            "draftOrders", bundle
        );
        return new CdsHookRequest("order-sign", "id", null, null, "Practitioner/x", ctx, null);
    }

    @Test
    @DisplayName("descriptor advertises the broadened scope (allergy + drug-drug interaction)")
    void descriptorAdvertisesAllergyAndDdi() {
        var d = service.descriptor();
        assertThat(d.id()).isEqualTo("hms-medication-allergy-check");
        assertThat(d.hook()).isEqualTo("order-sign");
        assertThat(d.title().toLowerCase()).contains("drug-drug");
        assertThat(d.description()).contains("drug_interactions");
    }

    @Test
    @DisplayName("warns when the proposed medication matches an active allergy")
    void warnsWhenProposedMedMatchesActiveAllergy() {
        UUID patientId = UUID.randomUUID();
        Patient patient = patient(patientId, UUID.randomUUID());

        PatientAllergy a = new PatientAllergy();
        a.setPatient(patient);
        a.setAllergenDisplay("Penicillin");
        a.setSeverity(AllergySeverity.SEVERE);
        a.setActive(true);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of(a));
        // DDI returns nothing for the allergy-only assertions.
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(ruleEngine.buildContext(any(), any(), any(), any(), any()))
            .thenReturn(mock(CdsRuleContext.class));
        when(ddiRule.evaluate(any())).thenReturn(List.of());

        CdsHookResponse response = service.evaluate(signRequest(patientId, "Penicillin V 500 mg PO BID"));

        assertThat(response.cards()).hasSize(1);
        CdsCard card = response.cards().get(0);
        assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL);
        assertThat(card.summary())
            .contains("Penicillin V 500 mg PO BID")
            .contains("penicillin");
    }

    @Test
    @DisplayName("case-insensitive match and inactive allergies are ignored")
    void caseInsensitiveAndIgnoresInactiveAllergies() {
        UUID patientId = UUID.randomUUID();
        Patient patient = patient(patientId, UUID.randomUUID());
        PatientAllergy resolved = new PatientAllergy();
        resolved.setPatient(patient);
        resolved.setAllergenDisplay("Sulfa");
        resolved.setActive(false);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of(resolved));
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(ruleEngine.buildContext(any(), any(), any(), any(), any()))
            .thenReturn(mock(CdsRuleContext.class));
        when(ddiRule.evaluate(any())).thenReturn(List.of());

        CdsHookResponse response = service.evaluate(signRequest(patientId, "Sulfamethoxazole-trimethoprim"));
        assertThat(response.cards()).isEmpty();
    }

    @Test
    @DisplayName("no allergy data and no DDI hits → no cards (engine still consulted)")
    void noAllergyDataProducesNoCardsWhenDdiAlsoSilent() {
        UUID patientId = UUID.randomUUID();
        Patient patient = patient(patientId, UUID.randomUUID());
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(ruleEngine.buildContext(any(), any(), any(), any(), any()))
            .thenReturn(mock(CdsRuleContext.class));
        when(ddiRule.evaluate(any())).thenReturn(List.of());

        assertThat(service.evaluate(signRequest(patientId, "Amoxicillin")).cards()).isEmpty();
    }

    /* =====================================================================
       v1.0 row 3 — drug-drug interaction add-on
       ===================================================================== */

    @Test
    @DisplayName("emits a critical DDI card when the rule reports a contraindicated pair")
    void emitsCriticalDdiCardOnCoexistingPrescription() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = patient(patientId, hospitalId);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        CdsRuleContext ctx = mock(CdsRuleContext.class);
        when(ruleEngine.buildContext(
            eq(patient), eq(hospitalId),
            eq("Warfarin"), eq("11289"), any()))
            .thenReturn(ctx);

        CdsCard ddiCard = new CdsCard(
            "Drug-drug interaction: Warfarin ↔ Aspirin (MAJOR)",
            "Additive bleeding risk.",
            CdsCard.Indicator.CRITICAL,
            new Source("HMS Drug-Drug Interaction Check", null, null),
            null, null, null, "uuid"
        );
        when(ddiRule.evaluate(ctx)).thenReturn(List.of(ddiCard));

        CdsHookResponse response = service.evaluate(signRequestWithRxnorm(
            patientId, "Warfarin", "11289"));

        assertThat(response.cards()).contains(ddiCard);
        assertThat(response.cards())
            .extracting(CdsCard::indicator)
            .contains(CdsCard.Indicator.CRITICAL);
    }

    @Test
    @DisplayName("combines allergy card AND DDI card when both apply")
    void combinesAllergyAndDdiCards() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = patient(patientId, hospitalId);

        PatientAllergy aspirinAllergy = new PatientAllergy();
        aspirinAllergy.setPatient(patient);
        aspirinAllergy.setAllergenDisplay("Aspirin");
        aspirinAllergy.setActive(true);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of(aspirinAllergy));
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        CdsRuleContext ctx = mock(CdsRuleContext.class);
        when(ruleEngine.buildContext(any(), any(), any(), any(), any())).thenReturn(ctx);

        CdsCard ddiCard = new CdsCard(
            "Drug-drug interaction: Aspirin ↔ Warfarin (MAJOR)",
            "Additive bleeding risk.",
            CdsCard.Indicator.CRITICAL,
            new Source("HMS Drug-Drug Interaction Check", null, null),
            null, null, null, "uuid"
        );
        when(ddiRule.evaluate(ctx)).thenReturn(List.of(ddiCard));

        CdsHookResponse response = service.evaluate(signRequestWithRxnorm(
            patientId, "Aspirin 100 mg", "1191"));

        // Two cards: one allergy (matched "aspirin" haystack), one DDI.
        assertThat(response.cards()).hasSize(2);
        assertThat(response.cards()).extracting(CdsCard::source)
            .extracting(Source::label)
            .containsExactlyInAnyOrder("HMS Allergy Check", "HMS Drug-Drug Interaction Check");
    }

    @Test
    @DisplayName("DDI is silent when the patient cannot be loaded — allergy still runs")
    void ddiSilentWhenPatientUnknownButAllergyStillFires() {
        UUID patientId = UUID.randomUUID();

        PatientAllergy a = new PatientAllergy();
        a.setAllergenDisplay("Aspirin");
        a.setActive(true);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of(a));
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(Optional.empty());
        // ruleEngine + ddiRule must not be consulted when patient is null.
        lenient().when(ruleEngine.buildContext(any(), any(), any(), any(), any()))
            .thenReturn(mock(CdsRuleContext.class));
        lenient().when(ddiRule.evaluate(any())).thenReturn(List.of());

        CdsHookResponse response = service.evaluate(signRequestWithRxnorm(
            patientId, "Aspirin", "1191"));

        assertThat(response.cards()).hasSize(1);
        assertThat(response.cards().get(0).source().label()).isEqualTo("HMS Allergy Check");
        verify(ddiRule, never()).evaluate(any());
    }

    @Test
    @DisplayName("patient lookup is performed at most once even with multiple drafts")
    void patientLookupIsCachedAcrossDrafts() {
        UUID patientId = UUID.randomUUID();
        Patient patient = patient(patientId, UUID.randomUUID());
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());
        when(patientRepo.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        when(ruleEngine.buildContext(any(), any(), any(), any(), any()))
            .thenReturn(mock(CdsRuleContext.class));
        when(ddiRule.evaluate(any())).thenReturn(List.of());

        Map<String, Object> mr1 = Map.of(
            "resourceType", "MedicationRequest",
            "medicationCodeableConcept", Map.of("text", "Aspirin 100 mg"));
        Map<String, Object> mr2 = Map.of(
            "resourceType", "MedicationRequest",
            "medicationCodeableConcept", Map.of("text", "Warfarin 5 mg"));
        Map<String, Object> bundle = Map.of("entry", List.of(
            Map.of("resource", mr1), Map.of("resource", mr2)));
        Map<String, Object> contextMap = Map.of(
            "patientId", patientId.toString(),
            "draftOrders", bundle);
        CdsHookRequest req = new CdsHookRequest("order-sign", "i", null, null, "u", contextMap, null);

        service.evaluate(req);

        verify(patientRepo, times(1)).findByIdUnscoped(patientId);
        verify(ddiRule, times(2)).evaluate(any()); // one per draft
    }

    @Test
    @DisplayName("ignores non-MedicationRequest draft entries")
    void ignoresNonMedicationRequestDrafts() {
        UUID patientId = UUID.randomUUID();
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());

        Map<String, Object> nonMed = Map.of(
            "resourceType", "ServiceRequest",
            "code", Map.of("text", "MRI brain"));
        Map<String, Object> ctx = Map.of(
            "patientId", patientId.toString(),
            "draftOrders", Map.of("entry", List.of(Map.of("resource", nonMed))));
        CdsHookRequest req = new CdsHookRequest("order-sign", "i", null, null, "u", ctx, null);

        assertThat(service.evaluate(req).cards()).isEmpty();
        verify(patientRepo, never()).findByIdUnscoped(any());
        verify(ddiRule, never()).evaluate(any());
    }

    /* helpers */

    private static Patient patient(UUID id, UUID hospitalId) {
        Patient p = Patient.builder().build();
        p.setId(id);
        p.setHospitalId(hospitalId);
        return p;
    }
}
