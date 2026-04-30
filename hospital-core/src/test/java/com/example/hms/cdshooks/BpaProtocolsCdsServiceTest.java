package com.example.hms.cdshooks;

import com.example.hms.cdshooks.bpa.BpaRuleEngine;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.service.BpaProtocolsCdsService;
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

class BpaProtocolsCdsServiceTest {

    private final BpaRuleEngine engine = mock(BpaRuleEngine.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final BpaProtocolsCdsService service = new BpaProtocolsCdsService(engine, patients);

    private static CdsHookRequest viewRequest(UUID patientId) {
        Map<String, Object> ctx = Map.of("patientId", patientId.toString());
        return new CdsHookRequest("patient-view", "instance", null, null, "Practitioner/x", ctx, null);
    }

    @Test
    void descriptorAdvertisesPatientViewHookWithStableId() {
        var d = service.descriptor();
        assertThat(d.hook()).isEqualTo("patient-view");
        assertThat(d.id()).isEqualTo("hms-bpa-protocols");
        assertThat(d.title()).isNotBlank();
        assertThat(d.description()).contains("malaria", "sepsis");
    }

    @Test
    void emptyResponseWhenPatientIdMissing() {
        CdsHookRequest req = new CdsHookRequest("patient-view", "i", null, null, "u", Map.of(), null);
        CdsHookResponse response = service.evaluate(req);
        assertThat(response.cards()).isEmpty();
        verify(engine, never()).evaluateForPatient(any(), any());
    }

    @Test
    void emptyResponseWhenPatientUnknown() {
        UUID patientId = UUID.randomUUID();
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.empty());

        CdsHookResponse response = service.evaluate(viewRequest(patientId));

        assertThat(response.cards()).isEmpty();
        verify(engine, never()).evaluateForPatient(any(), any());
    }

    @Test
    void delegatesToEngineWithPatientHospitalScopeAndReturnsCards() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        CdsCard card = new CdsCard("Sepsis — Hour-1 bundle", "detail",
            CdsCard.Indicator.WARNING, new Source("HMS", null, null),
            null, null, null, UUID.randomUUID().toString());
        when(engine.evaluateForPatient(eq(patient), eq(hospitalId)))
            .thenReturn(List.of(card));

        CdsHookResponse response = service.evaluate(viewRequest(patientId));
        assertThat(response.cards()).containsExactly(card);
    }
}
