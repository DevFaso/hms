package com.example.hms.hl7.mllp;

import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.payload.dto.empi.EmpiIdentityResponseDTO;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.service.empi.EmpiService;
import com.example.hms.service.integration.MllpInboundAdtVisitProjectionService;
import com.example.hms.service.integration.MllpInboundLabService;
import com.example.hms.service.integration.impl.MllpInboundAdtServiceImpl;
import com.example.hms.service.integration.impl.MllpInboundMergeServiceImpl;
import com.example.hms.service.integration.message.IntegrationMessageRecorder;
import com.example.hms.service.platform.MllpAllowedSenderService;
import com.example.hms.utility.Hl7v2MessageBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cross-tenant oracle on the ADT and A40 merge paths, asserted where it
 * was readable: <b>on the ACK</b>.
 *
 * <p>These paths used to answer {@code AR} when the referenced patient existed
 * but belonged to another hospital, and {@code AE} when no such patient
 * existed anywhere. Two answers, told apart by anyone who can send a message —
 * so an allowlisted sender could walk an identifier space one A08 at a time
 * and collect the MRNs that are real in hospitals it cannot read. PR #715
 * closed the same hole on ORU^R01 by making a cross-tenant accession answer
 * exactly like an unknown one; this is that fix on the other two doors.
 *
 * <p>The refusal rows carry no payload on purpose: one probe must not park a
 * full PID in the DLQ. That is asserted with {@code isNull()} below.
 *
 * <p>Everything here goes through the <b>real</b> dispatcher, the real parser
 * and the real inbound services — only repositories, EMPI and the recorder are
 * mocked — because the thing under test is the bytes that leave the process,
 * not an internal enum. The MSA segment is compared whole: it carries the
 * acknowledgement code, the inbound control id and the text, and nothing else
 * varies between two dispatches. (The MSH segment carries a fresh control id
 * and a wall-clock stamp, which differ between any two messages and say
 * nothing about the patient.)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdtCrossTenantAckTest {

    @Mock private MllpAllowedSenderService allowlist;
    @Mock private MllpInboundLabService inboundLab;
    @Mock private EmpiService empiService;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private MllpInboundAdtVisitProjectionService visitProjection;
    @Mock private IntegrationMessageRecorder messageRecorder;

    private Hl7MessageDispatcher dispatcher;
    private Hospital hospital;

    /** Registered at the receiving hospital. */
    private static final String LOCAL_MRN = "MRN-LOCAL";
    /** Exists, but at some other hospital. */
    private static final String FOREIGN_MRN = "MRN-FOREIGN";
    /** Exists nowhere. */
    private static final String UNKNOWN_MRN = "MRN-NOBODY";

    private UUID localPatientId;
    private UUID foreignPatientId;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        hospital.setId(UUID.randomUUID());
        hospital.setName("Hopital Yalgado Ouedraogo");

        localPatientId = UUID.randomUUID();
        foreignPatientId = UUID.randomUUID();

        MllpInboundAdtServiceImpl adtService = new MllpInboundAdtServiceImpl(
            empiService, patientRepository, registrationRepository, visitProjection,
            messageRecorder);
        MllpInboundMergeServiceImpl mergeService = new MllpInboundMergeServiceImpl(
            empiService, registrationRepository, messageRecorder);

        dispatcher = new Hl7MessageDispatcher(
            new Hl7v2MessageBuilder(), allowlist, inboundLab, adtService, mergeService,
            messageRecorder);

        when(allowlist.resolveHospital(any(), any())).thenReturn(Optional.of(hospital));

        // EMPI is global: it resolves the foreign MRN perfectly well. That is
        // the point — the tenant boundary is the registration, not the alias.
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, LOCAL_MRN))
            .thenReturn(Optional.of(identity(localPatientId)));
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, FOREIGN_MRN))
            .thenReturn(Optional.of(identity(foreignPatientId)));
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, UNKNOWN_MRN))
            .thenReturn(Optional.empty());

        when(patientRepository.findByIdUnscoped(localPatientId))
            .thenReturn(Optional.of(patient(localPatientId)));
        when(patientRepository.findByIdUnscoped(foreignPatientId))
            .thenReturn(Optional.of(patient(foreignPatientId)));

        when(registrationRepository.findByPatientIdAndHospitalId(localPatientId, hospital.getId()))
            .thenReturn(Optional.of(new PatientHospitalRegistration()));
        when(registrationRepository.findByPatientIdAndHospitalId(foreignPatientId, hospital.getId()))
            .thenReturn(Optional.empty());
        when(registrationRepository.existsByPatientIdAndHospitalId(localPatientId, hospital.getId()))
            .thenReturn(true);
        when(registrationRepository.existsByPatientIdAndHospitalId(foreignPatientId, hospital.getId()))
            .thenReturn(false);
    }

    private static EmpiIdentityResponseDTO identity(UUID patientId) {
        return EmpiIdentityResponseDTO.builder()
            .id(UUID.randomUUID())
            .empiNumber("E-" + patientId)
            .patientId(patientId)
            .build();
    }

    private static Patient patient(UUID id) {
        Patient patient = new Patient();
        patient.setId(id);
        patient.setFirstName("Existing");
        patient.setLastName("Patient");
        return patient;
    }

    /**
     * Same control id on both messages on purpose: MSA-2 echoes it, so any
     * difference left in the segment is a difference the receiver chose to
     * make.
     */
    private static String a08(String mrn) {
        return "MSH|^~\\&|REGISTRATION|HOSP-B|HMS|HOSP1|20260924120000||ADT^A08|CTRL-SAME|P|2.5\r"
            + "PID|1||" + mrn + "^^^HOSP-B^MR||Traore^Awa||19900101|F|||1 Main St^^Ouagadougou^^^BF\r";
    }

    private static String a40(String survivingMrn, String priorMrn) {
        return "MSH|^~\\&|REGISTRATION|HOSP-B|HMS|HOSP1|20260924120000||ADT^A40|CTRL-SAME|P|2.5\r"
            + "EVN|A40|20260924120000\r"
            + "PID|1||" + survivingMrn + "^^^HOSP-B^MR||Traore^Awa||19900101|F\r"
            + "MRG|" + priorMrn + "^^^HOSP-B^MR\r";
    }

    /** The acknowledgement the sender actually reads. */
    private static String msa(String ack) {
        for (String segment : ack.split("\r")) {
            if (segment.startsWith("MSA")) {
                return segment;
            }
        }
        throw new AssertionError("no MSA segment in ACK: " + ack);
    }

    /* ── ADT^A08 ─────────────────────────────────────────────────────── */

    @Test
    @DisplayName("A08: a patient in another hospital is indistinguishable from a patient nobody has")
    void a08CrossTenantLooksExactlyLikeUnknown() {
        String foreign = msa(dispatcher.dispatch(a08(FOREIGN_MRN), "10.0.0.1:1"));
        String unknown = msa(dispatcher.dispatch(a08(UNKNOWN_MRN), "10.0.0.1:1"));

        // Byte for byte. Not "both are errors" — an AR and an AE are both
        // errors too, and that was the whole leak.
        assertThat(foreign).isEqualTo(unknown);
        assertThat(foreign).isEqualTo("MSA|AE|CTRL-SAME|ADT^A08 referenced entity not found");
    }

    @Test
    @DisplayName("A08: the in-tenant update still succeeds and still writes")
    void a08InTenantIsUnchanged() {
        String ack = dispatcher.dispatch(a08(LOCAL_MRN), "10.0.0.1:1");

        assertThat(msa(ack)).isEqualTo("MSA|AA|CTRL-SAME");
        verify(patientRepository).save(any(Patient.class));
    }

    @Test
    @DisplayName("A08: the cross-tenant reason survives on the integration message row")
    void a08CrossTenantReasonIsRecorded() {
        dispatcher.dispatch(a08(FOREIGN_MRN), "10.0.0.1:1");

        verify(messageRecorder).recordMessage(
            eq("MLLP:REGISTRATION/HOSP-B"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A08"), isNull(),
            eq(IntegrationMessageStatus.FAILED),
            eq("cross-tenant rejection (MSH-10 \"CTRL-SAME\")"),
            any());
    }

    /* ── ADT^A40 ─────────────────────────────────────────────────────── */

    @Test
    @DisplayName("A40: a merge naming a foreign patient reads exactly like one naming nobody")
    void a40CrossTenantLooksExactlyLikeUnknown() {
        String foreign = msa(dispatcher.dispatch(a40(LOCAL_MRN, FOREIGN_MRN), "10.0.0.1:1"));
        String unknown = msa(dispatcher.dispatch(a40(LOCAL_MRN, UNKNOWN_MRN), "10.0.0.1:1"));

        assertThat(foreign).isEqualTo(unknown);
        assertThat(foreign).isEqualTo("MSA|AE|CTRL-SAME|ADT^A40 referenced entity not found");
    }

    @Test
    @DisplayName("A40: owning only ONE of the two sides is not a distinguishable answer either")
    void a40PartialOwnershipIsNotPartialPermission() {
        // The asymmetry a merge adds over a demographic update: it names two
        // patients and needs both. A sender that holds one of them locally
        // could otherwise pair it with any candidate identifier and read off
        // whether that candidate is real somewhere else — so "one of the two
        // is mine" has to answer like "neither of them exists".
        UUID otherForeignId = UUID.randomUUID();
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, FOREIGN_MRN + "-2"))
            .thenReturn(Optional.of(identity(otherForeignId)));
        when(registrationRepository.existsByPatientIdAndHospitalId(otherForeignId, hospital.getId()))
            .thenReturn(false);

        String oneMine = msa(dispatcher.dispatch(a40(LOCAL_MRN, FOREIGN_MRN), "10.0.0.1:1"));
        String neitherMine = msa(dispatcher.dispatch(a40(FOREIGN_MRN, FOREIGN_MRN + "-2"),
            "10.0.0.1:1"));
        String nonePresent = msa(dispatcher.dispatch(a40(UNKNOWN_MRN, UNKNOWN_MRN + "-2"),
            "10.0.0.1:1"));

        assertThat(oneMine).isEqualTo(neitherMine).isEqualTo(nonePresent);
    }

    @Test
    @DisplayName("A40: an already-merged pair in another tenant does not answer AA")
    void a40AlreadyMergedElsewhereIsNotAccepted() {
        // Both identifiers resolve to one patient because some OTHER hospital
        // merged them. Answering AA — which the no-op branch did before the
        // gate was moved ahead of it — is the same oracle wearing an accept.
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, FOREIGN_MRN + "-2"))
            .thenReturn(Optional.of(identity(foreignPatientId)));

        String ack = msa(dispatcher.dispatch(a40(FOREIGN_MRN, FOREIGN_MRN + "-2"), "10.0.0.1:1"));

        assertThat(ack).isEqualTo("MSA|AE|CTRL-SAME|ADT^A40 referenced entity not found");
    }

    @Test
    @DisplayName("A40: the cross-tenant reason survives on the integration message row")
    void a40CrossTenantReasonIsRecorded() {
        dispatcher.dispatch(a40(LOCAL_MRN, FOREIGN_MRN), "10.0.0.1:1");

        verify(messageRecorder).recordMessage(
            eq("MLLP:REGISTRATION/HOSP-B"), any(),
            eq(IntegrationMessageDirection.INBOUND),
            eq("ADT^A40"), isNull(),
            eq(IntegrationMessageStatus.FAILED),
            eq("cross-tenant rejection (MSH-10 \"CTRL-SAME\")"),
            any());
    }

    @Test
    @DisplayName("A40: a legitimate in-tenant merge still merges")
    void a40InTenantStillMerges() {
        UUID secondLocalId = UUID.randomUUID();
        when(empiService.findIdentityByAlias(EmpiAliasType.MRN, LOCAL_MRN + "-2"))
            .thenReturn(Optional.of(identity(secondLocalId)));
        when(registrationRepository.existsByPatientIdAndHospitalId(secondLocalId, hospital.getId()))
            .thenReturn(true);

        String ack = dispatcher.dispatch(a40(LOCAL_MRN, LOCAL_MRN + "-2"), "10.0.0.1:1");

        assertThat(msa(ack)).isEqualTo("MSA|AA|CTRL-SAME");
        verify(empiService).mergePatientsAtAuthorisedHospital(
            eq(hospital.getId()), eq(localPatientId), eq(secondLocalId), any(), any());
    }
}
