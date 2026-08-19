package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.service.PatientViewCdsService;
import com.example.hms.enums.AllergySeverity;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientAllergy;
import com.example.hms.model.PatientProblem;
import com.example.hms.repository.PatientAllergyRepository;
import com.example.hms.repository.PatientProblemRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PatientViewCdsServiceTest {

    private final PatientAllergyRepository allergyRepo = mock(PatientAllergyRepository.class);
    private final PatientProblemRepository problemRepo = mock(PatientProblemRepository.class);
    private final PatientViewCdsService service = new PatientViewCdsService(allergyRepo, problemRepo);

    @Test
    void returnsAllergyAndProblemCards() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        PatientAllergy a = new PatientAllergy();
        a.setPatient(patient);
        a.setAllergenDisplay("Penicillin");
        a.setReaction("hives");
        a.setSeverity(AllergySeverity.SEVERE);
        a.setActive(true);
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of(a));

        PatientProblem p = new PatientProblem();
        p.setPatient(patient);
        p.setProblemDisplay("Sickle cell disease");
        p.setStatus(ProblemStatus.ACTIVE);
        p.setChronic(true);
        when(problemRepo.findByPatient_Id(patientId)).thenReturn(List.of(p));

        CdsHookRequest req = new CdsHookRequest(
            "patient-view", "abc", null, null, "Practitioner/x",
            Map.of("patientId", patientId.toString()),
            null
        );

        CdsHookResponse response = service.evaluate(req);

        assertThat(response.cards()).hasSize(2);
        CdsCard allergy = response.cards().get(0);
        assertThat(allergy.summary()).contains("1 active allergy");
        assertThat(allergy.detail()).contains("Penicillin").contains("hives");
        assertThat(allergy.indicator()).isEqualTo(CdsCard.Indicator.WARNING);

        CdsCard problem = response.cards().get(1);
        assertThat(problem.summary()).contains("1 active problem");
        assertThat(problem.detail()).contains("Sickle cell disease").contains("chronic");
        assertThat(problem.indicator()).isEqualTo(CdsCard.Indicator.INFO);
    }

    @Test
    void problemCardRendersIcdAndLoincFromSeedTable() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        // Hypertension — ICD I10 binds to LOINC 85354-9 via the seed table.
        PatientProblem p = new PatientProblem();
        p.setPatient(patient);
        p.setProblemDisplay("Essential hypertension");
        p.setProblemCode("I10");
        p.setIcdVersion("ICD-10");
        p.setStatus(ProblemStatus.ACTIVE);
        when(problemRepo.findByPatient_Id(patientId)).thenReturn(List.of(p));
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());

        CdsHookRequest req = new CdsHookRequest(
            "patient-view", "x", null, null, null,
            Map.of("patientId", patientId.toString()), null
        );

        CdsCard problem = service.evaluate(req).cards().get(0);
        assertThat(problem.detail())
            .contains("Essential hypertension")
            .contains("[ICD-10: I10]")
            .contains("[LOINC: 85354-9 Blood pressure panel]");
    }

    @Test
    void problemCardEntityLoincOverridesSeedBinding() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        // I10 would normally bind to 85354-9, but the entity carries its own LOINC.
        PatientProblem p = new PatientProblem();
        p.setPatient(patient);
        p.setProblemDisplay("Hypertension");
        p.setProblemCode("I10");
        p.setLoincCode("8480-6");
        p.setLoincDisplay("Systolic blood pressure");
        p.setStatus(ProblemStatus.ACTIVE);
        when(problemRepo.findByPatient_Id(patientId)).thenReturn(List.of(p));
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());

        CdsHookRequest req = new CdsHookRequest(
            "patient-view", "x", null, null, null,
            Map.of("patientId", patientId.toString()), null
        );

        CdsCard problem = service.evaluate(req).cards().get(0);
        assertThat(problem.detail())
            .contains("[LOINC: 8480-6 Systolic blood pressure]");
        assertThat(problem.detail()).doesNotContain("85354-9");
    }

    @Test
    void problemCardSkipsLoincWhenNoBindingAndEntityLacksCode() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        // Z00 (general examination) — intentionally not in the seed table.
        PatientProblem p = new PatientProblem();
        p.setPatient(patient);
        p.setProblemDisplay("General examination");
        p.setProblemCode("Z00");
        p.setStatus(ProblemStatus.ACTIVE);
        when(problemRepo.findByPatient_Id(patientId)).thenReturn(List.of(p));
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());

        CdsHookRequest req = new CdsHookRequest(
            "patient-view", "x", null, null, null,
            Map.of("patientId", patientId.toString()), null
        );

        CdsCard problem = service.evaluate(req).cards().get(0);
        assertThat(problem.detail()).contains("[ICD-10: Z00]");
        assertThat(problem.detail()).doesNotContain("LOINC:");
    }

    @Test
    void malformedIcdAndLoincAreDroppedSilently() {
        UUID patientId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);

        PatientProblem p = new PatientProblem();
        p.setPatient(patient);
        p.setProblemDisplay("Local-system problem");
        p.setProblemCode("not-icd");        // fails ICD-10 regex
        p.setLoincCode("99999999-zzz");      // fails LOINC regex
        p.setStatus(ProblemStatus.ACTIVE);
        when(problemRepo.findByPatient_Id(patientId)).thenReturn(List.of(p));
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());

        CdsHookRequest req = new CdsHookRequest(
            "patient-view", "x", null, null, null,
            Map.of("patientId", patientId.toString()), null
        );

        CdsCard problem = service.evaluate(req).cards().get(0);
        assertThat(problem.detail()).contains("Local-system problem");
        assertThat(problem.detail()).doesNotContain("[ICD-10:");
        assertThat(problem.detail()).doesNotContain("[LOINC:");
    }

    @Test
    void emptyChartProducesNoCards() {
        UUID patientId = UUID.randomUUID();
        when(allergyRepo.findByPatient_Id(patientId)).thenReturn(List.of());
        when(problemRepo.findByPatient_Id(patientId)).thenReturn(List.of());

        CdsHookRequest req = new CdsHookRequest(
            "patient-view", "x", null, null, null,
            Map.of("patientId", "Patient/" + patientId), null
        );

        assertThat(service.evaluate(req).cards()).isEmpty();
    }

    @Test
    void missingPatientIdReturnsEmpty() {
        CdsHookRequest req = new CdsHookRequest("patient-view", "x", null, null, null, Map.of(), null);
        assertThat(service.evaluate(req).cards()).isEmpty();
    }
}
