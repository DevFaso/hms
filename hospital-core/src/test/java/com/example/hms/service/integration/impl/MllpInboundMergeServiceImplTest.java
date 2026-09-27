package com.example.hms.service.integration.impl;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.service.empi.EmpiAuthorisedMergePort;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundOutcome;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Inbound {@code ADT^A40} patient merge (Tier 2 item 41).
 *
 * <p><b>The cross-tenant tests are the point of this class.</b> EMPI's
 * request-scoped merge resolves the caller's hospital from the security
 * context, and there is none on an MLLP worker thread, so nothing EMPI could
 * read there says which patients a sender may merge. The gate lives here;
 * these pin that it does, and that the merge it lets through is handed the
 * receiving hospital explicitly ({@code mergePatientsAtAuthorisedHospital})
 * rather than sent to the request-scoped {@code mergePatients}.
 *
 * <p>EMPI is mocked here, so none of this proves the merge actually applies
 * on a context-free thread; {@code AdtA40MergeEndToEndIT} runs the real chain
 * for that.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MllpInboundMergeServiceImplTest {

    @Mock private EmpiService empiService;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private IntegrationMessageRecorder messageRecorder;
    @Mock private EmpiAuthorisedMergePort authorisedMerge;

    private MllpInboundMergeServiceImpl service;

    private Hospital hospital;
    private UUID hospitalId;
    private UUID survivingPatientId;
    private UUID retiringPatientId;

    private static final String SURVIVING_MRN = "MRN-SURVIVOR";
    private static final String PRIOR_MRN = "MRN-RETIRED";

    @BeforeEach
    void setUp() {
        service = new MllpInboundMergeServiceImpl(
            empiService, registrationRepository, messageRecorder, authorisedMerge);

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
        empiKnows(mrn, patientId, hospitalId);
    }

    /** Known to EMPI, with its master identity owned (stamped) by {@code owner}. */
    private void empiKnows(String mrn, UUID patientId, UUID owner) {
        // @Value @Builder — immutable, no setters.
        EmpiIdentityResponseDTO dto = EmpiIdentityResponseDTO.builder()
            .patientId(patientId)
            .hospitalId(owner)
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
        verify(authorisedMerge).mergePatientsAtAuthorisedHospital(
            eq(hospitalId), eq(survivingPatientId), eq(retiringPatientId), any(), anyString());
        // Never the request-scoped entry point: it has no scope to resolve on
        // this thread and refuses every merge.
        verify(empiService, never()).mergePatients(any(), any(), any(), any());
    }

    @Test
    void theMergeIsRecordedAsAutomatedNotManual() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);

        process();

        ArgumentCaptor<EmpiMergeType> type = ArgumentCaptor.forClass(EmpiMergeType.class);
        verify(authorisedMerge).mergePatientsAtAuthorisedHospital(any(), any(), any(), type.capture(), anyString());
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
        verify(authorisedMerge).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), notes.capture());
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

        // Owning ONE of the two sides is not a distinguishable answer: a
        // sender could otherwise pair its own local MRN with any candidate
        // identifier and read off whether that candidate exists elsewhere.
        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(authorisedMerge, never()).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), any());
    }

    @Test
    void refusesWhenTheRETIREDSideIsNotRegisteredHereEither() {
        // Checking only the survivor would permit merging a stranger's record
        // INTO a local patient, which is as damaging as the reverse.
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, false);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(authorisedMerge, never()).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), any());
    }

    @Test
    void aCrossTenantRefusalStillRecordsItsReasonOnTheIntegrationRow() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, false);

        process();

        // The ACK cannot say this. The DLQ row can — and it says it without
        // the body, so one probe does not park two MRNs and a name in the
        // payload column.
        verify(messageRecorder).recordMessage(
            eq("MLLP:LIS/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A40"), isNull(),
            eq(IntegrationMessageStatus.FAILED),
            eq("cross-tenant rejection (MSH-10 \"MSG-A40-1\")"),
            any());
    }

    @Test
    void anUnknownIdentifierRecordsItsOwnDifferentReason() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiDoesNotKnow(PRIOR_MRN);

        process();

        verify(messageRecorder).recordMessage(
            eq("MLLP:LIS/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A40"), isNull(),
            // FAILED, like the cross-tenant refusal above. What stops a
            // retrying sender flooding the badge is the correlation id, not
            // the status.
            eq(IntegrationMessageStatus.FAILED),
            eq("identifier not found (MSH-10 \"MSG-A40-1\")"),
            any());
    }

    @Test
    void anAppliedMergeRecordsNoRejection() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);

        assertThat(process()).isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(messageRecorder, never()).recordMessage(
            any(), any(), any(), any(), any(), any(), any(), any());
    }

    /* ── Ownership: registered here, but the identity is another hospital's ── */

    @Test
    void aPairRegisteredHereButOwnedElsewhereIsATerminalRefusalNotAMerge() {
        // A referred patient: registered at the receiving hospital, so the
        // gate passes, but its master identity is stamped with its home
        // hospital, which EMPI would refuse on every retry.
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId, UUID.randomUUID());
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_OWNER);
        verify(authorisedMerge, never()).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), any());
        verify(messageRecorder).recordMessage(
            eq("MLLP:LIS/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A40"), isNull(),
            eq(IntegrationMessageStatus.FAILED),
            eq(MllpInboundMergeServiceImpl.REASON_NOT_OWNER + " (MSH-10 \"MSG-A40-1\")"),
            any());
    }

    @Test
    void anUnstampedIdentityIsNotOwnedHereEither() {
        // A legacy identity with no hospital stamp: EMPI admits it to no
        // pinned caller, so it is not this hospital's to merge.
        empiKnows(SURVIVING_MRN, survivingPatientId, null);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_OWNER);
    }

    @Test
    void ownershipIsAskedOnlyAfterTheRegistrationGateSoItCannotBeAnOracle() {
        // Owned elsewhere AND not registered here: the answer must be the
        // cross-tenant one, identical to an unknown MRN, never NOT_OWNER.
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId, UUID.randomUUID());
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, false);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
    }

    @Test
    void aMergeEmpiRefusesLeavesADeadLetterWithoutTheExceptionText() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);
        when(authorisedMerge.mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), anyString()))
            .thenThrow(new BusinessException("constraint failed for notes MRN " + PRIOR_MRN));

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_INVALID);
        verify(messageRecorder).recordMessage(
            eq("MLLP:LIS/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A40"), isNull(),
            eq(IntegrationMessageStatus.FAILED),
            eq(MllpInboundMergeServiceImpl.REASON_EMPI_REFUSED + " (MSH-10 \"MSG-A40-1\")"),
            any());
    }

    /* ── Unknown identifiers ─────────────────────────────────────────── */

    @Test
    void anUnknownIdentifierIsRejectedRatherThanProvisioned() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiDoesNotKnow(PRIOR_MRN);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(authorisedMerge, never()).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), any());
        // Not even the tenant check ran — nothing to check.
        verifyNoInteractions(registrationRepository);
    }

    @Test
    void anUnknownSurvivorIsAlsoRejected() {
        empiDoesNotKnow(SURVIVING_MRN);
        empiKnows(PRIOR_MRN, retiringPatientId);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(authorisedMerge, never()).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), any());
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
        // The patient is registered here — without that this is not a resend
        // of OUR merge, and the accept below would be the oracle in reverse
        // (see the test that follows).
        registeredHere(survivingPatientId, true);

        assertThat(process()).isEqualTo(MllpInboundOutcome.ACCEPTED);
        verify(authorisedMerge, never()).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), any());
    }

    @Test
    void aResendForSomeoneElseSTenantIsNOTAcceptedBecauseTheAcceptWouldLeak() {
        // The subtle half. Both identifiers resolve to one patient because
        // some OTHER hospital merged them. Answering AA here told the sender
        // that two identifiers it does not own belong to one person somewhere
        // else — the same oracle as the AR, wearing an accept. The tenant gate
        // runs BEFORE the already-merged check for exactly this reason.
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, survivingPatientId);
        registeredHere(survivingPatientId, false);

        assertThat(process()).isEqualTo(MllpInboundOutcome.REJECTED_NOT_FOUND);
        verify(authorisedMerge, never()).mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), any());
    }

    @Test
    void aRefusalFromTheMergeServiceBecomesAnErrorNotAnAccept() {
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiKnows(PRIOR_MRN, retiringPatientId);
        registeredHere(survivingPatientId, true);
        registeredHere(retiringPatientId, true);
        when(authorisedMerge.mergePatientsAtAuthorisedHospital(any(), any(), any(), any(), anyString()))
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

    @Test
    void theRecordedReasonQuotesTheWholeControlId() {
        // Longer than HL7's nominal 20, as real senders' ids are. Quoted
        // whole: MSH-10 is bounded at parse to its column, and a shorter cap
        // here would make two ids that share a prefix one row text.
        empiKnows(SURVIVING_MRN, survivingPatientId);
        empiDoesNotKnow(PRIOR_MRN);
        String controlId = "20260826-LIS-MERGE-000000000042";

        service.processMerge(message(), hospital, "LIS", "HOSP1", controlId);

        verify(messageRecorder).recordMessage(
            eq("MLLP:LIS/HOSP1"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A40"), isNull(),
            eq(IntegrationMessageStatus.FAILED),
            eq("identifier not found (MSH-10 \"" + controlId + "\")"),
            any());
    }
}
