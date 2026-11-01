package com.example.hms.cdshooks;

import com.example.hms.cdshooks.bpa.BpaRuleEngine;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.Source;
import com.example.hms.cdshooks.service.BpaProtocolsCdsService;
import com.example.hms.model.CdsAcknowledgement;
import com.example.hms.model.Patient;
import com.example.hms.repository.CdsAcknowledgementRepository;
import com.example.hms.repository.PatientRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BpaProtocolsCdsServiceTest {

    private final BpaRuleEngine engine = mock(BpaRuleEngine.class);
    private final PatientRepository patients = mock(PatientRepository.class);
    private final CdsAcknowledgementRepository acknowledgements = mock(CdsAcknowledgementRepository.class);
    private final BpaProtocolsCdsService service =
        new BpaProtocolsCdsService(engine, patients, acknowledgements);

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
        when(engine.evaluateForPatient(patient, hospitalId)).thenReturn(List.of(card));
        CdsHookResponse response = service.evaluate(viewRequest(patientId));
        assertThat(response.cards()).containsExactly(card);
    }

    @Test
    void suppressesAcknowledgedCardsByUuid() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));

        String ackedUuid = UUID.randomUUID().toString();
        CdsCard acked = new CdsCard("Sepsis", null, CdsCard.Indicator.CRITICAL,
            new Source("HMS", null, null), null, null, null, ackedUuid);
        CdsCard fresh = new CdsCard("Malaria", null, CdsCard.Indicator.WARNING,
            new Source("HMS", null, null), null, null, null, UUID.randomUUID().toString());
        when(engine.evaluateForPatient(patient, hospitalId)).thenReturn(List.of(acked, fresh));

        CdsAcknowledgement ackEntry = CdsAcknowledgement.builder()
            .cardUuid(ackedUuid)
            .cardSummary("Sepsis")
            .indicator("critical")
            .build();
        // QA D1194 (round 22, changed stub): the viewer's own acknowledgements are read.
        UUID viewer = UUID.randomUUID();
        when(acknowledgements.findActiveForPatientAndUser(any(UUID.class), org.mockito.ArgumentMatchers.eq(viewer),
            any(LocalDateTime.class))).thenReturn(List.of(ackEntry));

        CdsHookResponse response = asViewer(viewer, () -> service.evaluate(viewRequest(patientId)));
        assertThat(response.cards()).containsExactly(fresh);
    }

    /**
     * QA D1194 (round 22): an acknowledgement recorded in French (its summary
     * is French) hides the same advisory in Spanish and English, because the
     * card is matched by its stable id, never by its text; and a card with an
     * id is never hidden by an acknowledgement of a same-text card with
     * another id.
     */
    @Test
    void theAcknowledgementHoldsInEveryLanguageAndIsMatchedByIdOnly_D1194() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        String stable = UUID.nameUUIDFromBytes("hms-bpa:pregnancy-htn:critical".getBytes(
            java.nio.charset.StandardCharsets.UTF_8)).toString();
        CdsAcknowledgement french = CdsAcknowledgement.builder()
            .cardUuid(stable).cardSummary("Hypertension de la grossesse").indicator("critical").build();
        CdsAcknowledgement sameTextOtherCard = CdsAcknowledgement.builder()
            .cardUuid(UUID.randomUUID().toString()).cardSummary("Hypertension in pregnancy").indicator("critical").build();
        UUID viewer = UUID.randomUUID();
        when(acknowledgements.findActiveForPatientAndUser(any(UUID.class), org.mockito.ArgumentMatchers.eq(viewer),
            any(LocalDateTime.class))).thenReturn(List.of(french, sameTextOtherCard));

        for (String summary : List.of("Hypertension de la grossesse", "Hipertensión en el embarazo",
                "Hypertension in pregnancy")) {
            CdsCard card = new CdsCard(summary, null, CdsCard.Indicator.CRITICAL,
                new Source("HMS", null, null), null, null, null, stable);
            when(engine.evaluateForPatient(patient, hospitalId)).thenReturn(List.of(card));
            assertThat(asViewer(viewer, () -> service.evaluate(viewRequest(patientId))).cards())
                .as("acknowledged in French, read as %s", summary).isEmpty();
        }

        CdsCard otherCard = new CdsCard("Hypertension in pregnancy", null, CdsCard.Indicator.CRITICAL,
            new Source("HMS", null, null), null, null, null, UUID.randomUUID().toString());
        when(engine.evaluateForPatient(patient, hospitalId)).thenReturn(List.of(otherCard));
        assertThat(asViewer(viewer, () -> service.evaluate(viewRequest(patientId))).cards())
            .as("a card with an id is matched by id, never by its text").containsExactly(otherCard);
    }

    /** QA D1194: another clinician's acknowledgement does not hide the card from this one; no viewer, no hiding. */
    @Test
    void onlyTheViewersOwnAcknowledgementsCount_D1194() {
        UUID patientId = UUID.randomUUID();
        UUID hospitalId = UUID.randomUUID();
        Patient patient = Patient.builder().build();
        patient.setId(patientId);
        patient.setHospitalId(hospitalId);
        when(patients.findByIdUnscoped(patientId)).thenReturn(Optional.of(patient));
        CdsCard card = new CdsCard("Sepsis", null, CdsCard.Indicator.CRITICAL,
            new Source("HMS", null, null), null, null, null, UUID.randomUUID().toString());
        when(engine.evaluateForPatient(patient, hospitalId)).thenReturn(List.of(card));
        UUID viewer = UUID.randomUUID();
        when(acknowledgements.findActiveForPatientAndUser(any(UUID.class), any(UUID.class), any(LocalDateTime.class)))
            .thenReturn(List.of());

        assertThat(asViewer(viewer, () -> service.evaluate(viewRequest(patientId))).cards()).containsExactly(card);
        verify(acknowledgements).findActiveForPatientAndUser(org.mockito.ArgumentMatchers.eq(patientId),
            org.mockito.ArgumentMatchers.eq(viewer), any(LocalDateTime.class));
        verify(acknowledgements, never()).findActiveForPatient(any(), any());

        assertThat(service.evaluate(viewRequest(patientId)).cards()).containsExactly(card);
    }

    private static <T> T asViewer(UUID userId, java.util.function.Supplier<T> call) {
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().principalUserId(userId).build());
        try {
            return call.get();
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }
}
