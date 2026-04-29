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
