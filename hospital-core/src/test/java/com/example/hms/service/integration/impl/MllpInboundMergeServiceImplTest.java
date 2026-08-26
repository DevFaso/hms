package com.example.hms.service.integration.impl;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.utility.Hl7v2MessageBuilder.ParsedMergeMessage;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Inbound {@code ADT^A40} patient merge (Tier 2 item 41).
 *
 * <p><b>The cross-tenant tests are the point of this class.</b> Every tenant
 * guard inside {@code EmpiServiceImpl} resolves the caller's hospital from the
 * security context, and {@code isVisibleToCaller} reads a null active hospital
 * as "unscoped, allow". There is no security context on an MLLP worker thread,
 * so those guards pass unconditionally on this path — the gate has to live
 * here, and these pin that it does.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MllpInboundMergeServiceImplTest {

    @Mock private EmpiService empiService;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;

    private MllpInboundMergeServiceImpl service;

    private Hospital hospital;
    private UUID hospitalId;
    private UUID survivingPatientId;
    private UUID retiringPatientId;

    private static final String SURVIVING_MRN = "MRN-SURVIVOR";
    private static final String PRIOR_MRN = "MRN-RETIRED";

    @BeforeEach
    void setUp() {
        service = new MllpInboundMergeServiceImpl(empiService, registrationRepository);

        hospitalId = UUID.randomUUID();
        hospital = new Hospital();
        hospital.setId(hospitalId);
        hospital.setName("Hopital Yalgado Ouedraogo");

        survivingPatientId = UUID.randomUUID();
        retiringPatientId = UUID.randomUUID();
    }

    private ParsedMergeMessage message() {
        return new ParsedMergeMessage(SURVIVING_MRN, "HOSP1", PRIOR_MRN, "HOSP1");
    }

    private void empiKnows(String mrn, UUID patientId) {
        // @Value @Builder — immutable, no setters.
        EmpiIdentityResponseDTO dto = EmpiIdentityResponseDTO.builder()
            .patientId(patientId)
            .build();
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, mrn)).thenReturn(Optional.of(dto));
    }

    private void empiDoesNotKnow(String mrn) {
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, mrn)).thenReturn(Optional.empty());
    }

    private void registeredHere(UUID patientId, boolean registered) {
        when(registrationRepository.existsByPatientIdAndHospitalId(patientId, hospitalId))
            .thenReturn(registered);
    }

    private MllpInboundOutcome process() {
        return service.processMerge(message(), hospital, "LIS", "HOSP1", "MSG-A40-1");
    }

    /* ── The happy path ──────────────────────────────────────────────── */

    @Test
    void mergesTheRetiredIdentifierIntoTheSurvivor() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);

        assertThat(process()).isEqualTo(MllpInboundOutcome.ACCEPTED);

        // Argument ORDER is the whole risk: primary (survivor) first.
        verify(empiService).mergePatients(
            eq(survivingPatientId), eq(retiringPatientId), any(), anyString());
    }

    @Test
    void theMergeIsRecordedAsAutomatedNotManual() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);

        process();

        ArgumentCaptor<EmpiMergeType> type = ArgumentCaptor.forClass(EmpiMergeType.class);
        verify(empiService).mergePatients(any(), any(), type.capture(), anyString());
        // No human made this call and the merge event must not read as
        // though one did — mergedBy is null on this path.
        assertThat(type.getValue()).isEqualTo(EmpiMergeType.AUTOMATED);
    }

    @Test
    void theNotesCarryTheSenderAndControlIdBecauseThereIsNoPrincipal() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);

        process();

        ArgumentCaptor<String> notes = ArgumentCaptor.forClass(String.class);
        verify(empiService).mergePatients(any(), any(), any(), notes.capture());
        // mergedBy is null on an MLLP thread, so this note is the merge row's
        // only provenance.
        assertThat(notes.getValue())
            .contains("A40")
            .contains("LIS")
            .contains(PRIOR_MRN)
            .contains(SURVIVING_MRN)
            .contains("MSG-A40-1");
    }

    /* ── The cross-tenant gate ───────────────────────────────────────── */

    @Test
    void refusesWhenTheSurvivorIsNotRegisteredAtTheReceivingHospital() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, false);
        registeredHere(retiringPatientId, true);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_CROSS_TENANT);
        verify(empiService, never()).mergePatients(any(), any(), any(), anyString());
    }

    @Test
    void refusesWhenTheRETIREDSideIsNotRegisteredHereEither() {
        // Checking only the survivor would permit merging a stranger's record
        // INTO a local patient, which is as damaging as the reverse.
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, false);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_CROSS_TENANT);
        verify(empiService, never()).mergePatients(any(), any(), any(), anyString());
    }

    /* ── Unknown identifiers ─────────────────────────────────────────── */

    @Test
    void anUnknownIdentifierIsRejectedRatherThanProvisioned() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiDoesNotKnow(PRIOR_MRN);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(empiService, never()).mergePatients(any(), any(), any(), anyString());
        // Not even the tenant check ran — nothing to check.
        verifyNoInteractions(registrationRepository);
    }

    @Test
    void anUnknownSurvivorIsAlsoRejected() {
        empiDoesNotKnow(SURVIVING_MRN);
        empiKnows(PRIOR_MRN, retiringPatientId);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(empiService, never()).mergePatients(any(), any(), any(), anyString());
    }

    @Test
    void anIdentityWithNoPatientBehindItIsTreatedAsUnknown() {
        EmpiIdentityResponseDTO orphan = EmpiIdentityResponseDTO.builder()
            .patientId(null)
            .build();
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, SURVIVING_MRN))
            .thenReturn(Optional.of(orphan));
        empiKnows(PRIOR_MRN, retiringPatientId);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
    }

    /* ── Degenerate and repeated messages ────────────────────────────── */

    @Test
    void aMessageMergingAnIdentifierIntoItselfIsRefused() {
        ParsedMergeMessage sameBothSides =
            new ParsedMergeMessage(SURVIVING_MRN, "HOSP1", SURVIVING_MRN, "HOSP1");

        assertThat(service.processMerge(sameBothSides, hospital, "LIS", "HOSP1", "MSG-1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verifyNoInteractions(empiService);
    }

    @Test
    void aResendAfterTheMergeIsAcceptedRatherThanParkedAsAnError() {
        // Both MRNs now resolve to the same patient because the first message
        // already merged them and the aliases were reassigned. Rejecting would
        // leave a permanent AE in the sender's queue for work that is done.
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, survivingPatientId);

        assertThat(process()).isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(empiService, never()).mergePatients(any(), any(), any(), anyString());
    }

    @Test
    void aRefusalFromTheMergeServiceBecomesAnErrorNotAnAccept() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);
        when(empiService.mergePatients(any(), any(), any(), anyString()))
            .thenThrow(new BusinessException("already merged"));

        // The sender's request was not applied; their queue should say so.
        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
    }

    /* ── Malformed input ─────────────────────────────────────────────── */

    @Test
    void aNullOrIncompleteMessageIsInvalid() {
        assertThat(service.processMerge(null, hospital, "LIS", "HOSP1", "M1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);

        assertThat(service.processMerge(
            new ParsedMergeMessage(null, null, PRIOR_MRN, "HOSP1"), hospital, "LIS", "HOSP1", "M1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);

        assertThat(service.processMerge(
            new ParsedMergeMessage(SURVIVING_MRN, "HOSP1", "  ", null), hospital, "LIS", "HOSP1", "M1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);

        verifyNoInteractions(empiService);
    }

    @Test
    void anUnresolvedHospitalIsInvalidRatherThanUnscoped() {
        // Belt-and-braces: the dispatcher gates on the allowlist first, but a
        // null hospital reaching here must never mean "no tenant restriction".
        assertThat(service.processMerge(message(), null, "LIS", "HOSP1", "M1"))
            .isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verifyNoInteractions(empiService);
    }
}
