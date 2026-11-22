package com.example.hms.service.pharmacy;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.exception.ConflictException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDecisionDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralReasonDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralRequestDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.i18n.LocaleContextHolder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Policy rule 6 (provisional, V345): « à facturer » is asked for by one person, approved by another and executed by
 * the pharmacist; an inpatient item needs nobody's approval; an urgency override is logged and reviewed.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DispenseBillingDeferralServiceTest {

    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 11, 20, 9, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);

    @Mock private DispenseBillingDeferralRepository deferralRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;
    @Mock private DispenseBillingDeferralFollowUp followUp;
    @Mock private AuditEventLogService auditEventLogService;

    private DispenseBillingDeferralService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();
    private final UUID accountant = UUID.randomUUID();
    private final UUID billing = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();

    private Prescription prescription;
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        service = new DispenseBillingDeferralService(deferralRepository, prescriptionRepository, admissionRepository,
                userRepository, roleValidator, support, followUp, auditEventLogService, CLOCK);

        hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(patientId);
        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setHospital(hospital);
        prescription.setPatient(patient);
        prescription.setMedicationName("Amoxicilline 500 mg");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findByIdAndHospitalIdForUpdate(prescriptionId, hospitalId))
                .thenReturn(Optional.of(prescription));
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(deferralRepository.save(any(DispenseBillingDeferral.class))).thenAnswer(saved -> {
            DispenseBillingDeferral row = saved.getArgument(0);
            if (row.getId() == null) {
                row.setId(UUID.randomUUID());
            }
            return row;
        });
        // The pharmacist asks and overrides; the accountant approves both kinds and reviews; billing approves a
        // third-party cover only and reviews.
        when(roleValidator.canRequestDispenseBillingDeferral(any(), eq(hospitalId))).thenReturn(true);
        when(roleValidator.canReadDispenseBillingCounter(any(), eq(hospitalId))).thenReturn(true);
        when(roleValidator.canOverrideDispenseBillingForUrgency(pharmacist, hospitalId)).thenReturn(true);
        when(roleValidator.canApproveThirdPartyCover(accountant, hospitalId)).thenReturn(true);
        when(roleValidator.canApproveOutpatientCredit(accountant, hospitalId)).thenReturn(true);
        when(roleValidator.canReviewDispenseBillingOverride(accountant, hospitalId)).thenReturn(true);
        when(roleValidator.canApproveThirdPartyCover(billing, hospitalId)).thenReturn(true);
        when(roleValidator.canReviewDispenseBillingOverride(billing, hospitalId)).thenReturn(true);
    }

    // Each refused call as one callable: its arguments are built before the call that must refuse.

    private ThrowingCallable asking(DispenseBillingDeferralRequestDTO body) {
        return () -> service.request(body);
    }

    private ThrowingCallable approving(UUID id, DispenseBillingDeferralDecisionDTO body) {
        return () -> service.approve(id, body);
    }

    private ThrowingCallable rejecting(UUID id, DispenseBillingDeferralReasonDTO body) {
        return () -> service.reject(id, body);
    }

    private ThrowingCallable withdrawing(UUID id, DispenseBillingDeferralReasonDTO body) {
        return () -> service.cancel(id, body);
    }

    private ThrowingCallable reviewing(UUID id, DispenseBillingDeferralReasonDTO body) {
        return () -> service.review(id, body);
    }

    private ThrowingCallable admitting(Prescription order, UUID hospital, BigDecimal due,
                                       DispenseBillingDeferralService.Choice choice, User who) {
        return () -> service.admit(order, hospital, due, choice, who);
    }

    private void actingAs(UUID userId) {
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
    }

    private static User user(UUID id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    private DispenseBillingDeferral stored(DispenseBillingDeferralKind kind, DispenseBillingDeferralStatus status,
                                           UUID requestedBy) {
        DispenseBillingDeferral row = DispenseBillingDeferral.builder().hospitalId(hospitalId)
                .prescriptionId(prescriptionId).patientId(patientId).kind(kind).status(status)
                .reason("Cannot pay today").requestedBy(requestedBy).requestedAt(LocalDateTime.now(CLOCK)).build();
        row.setId(UUID.randomUUID());
        when(deferralRepository.findLockedByIdAndHospitalId(row.getId(), hospitalId)).thenReturn(Optional.of(row));
        return row;
    }

    private static DispenseBillingDeferralRequestDTO ask(UUID prescriptionId, DispenseBillingDeferralKind kind,
                                                         String reason) {
        return new DispenseBillingDeferralRequestDTO(prescriptionId, kind, reason, null);
    }

    private void openStay(AdmissionStatus status) {
        UUID admissionId = UUID.randomUUID();
        Encounter visit = new Encounter();
        visit.setAdmissionId(admissionId);
        prescription.setEncounter(visit);
        Admission stay = new Admission();
        stay.setId(admissionId);
        stay.setHospital(hospital);
        stay.setStatus(status);
        when(admissionRepository.findById(admissionId)).thenReturn(Optional.of(stay));
    }

    @Nested
    @DisplayName("asking")
    class Asking {

        @Test
        @DisplayName("the counter asks for a credit: REQUESTED, with who and why; nothing is decided; audited")
        void aRequestDecidesNothing() {
            actingAs(pharmacist);

            DispenseBillingDeferralDTO asked = service.request(ask(prescriptionId,
                    DispenseBillingDeferralKind.OUTPATIENT_CREDIT, "  Cannot pay today "));

            assertThat(asked.getStatus()).isEqualTo("REQUESTED");
            assertThat(asked.getReason()).isEqualTo("Cannot pay today");
            assertThat(asked.getRequestedBy()).isEqualTo(pharmacist);
            assertThat(asked.getDecidedBy()).isNull();
            assertThat(asked.isCanDecide()).as("whoever asks never decides").isFalse();
            verify(support).logAudit(eq(AuditEventType.DISPENSE_BILLING_DEFERRAL_REQUESTED), anyString(),
                    eq(asked.getId().toString()), anyString(), eq(hospitalId));
        }

        @Test
        @DisplayName("an urgency override or the stay's account is never asked for; a reason is required")
        void onlyAnApprovableKindWithAReason() {
            actingAs(pharmacist);

            assertThatThrownBy(asking(ask(prescriptionId,
                    DispenseBillingDeferralKind.URGENCY_OVERRIDE, "now")))
                    .isInstanceOf(FieldBusinessException.class);
            assertThatThrownBy(asking(ask(prescriptionId,
                    DispenseBillingDeferralKind.OUTPATIENT_CREDIT, "  ")))
                    .isInstanceOf(FieldBusinessException.class);
            verify(deferralRepository, never()).save(any());
        }

        @Test
        @DisplayName("an inpatient's order needs no deferral: it goes on the account of the open stay")
        void anInpatientNeedsNone() {
            actingAs(pharmacist);
            openStay(AdmissionStatus.ACTIVE);

            assertThatThrownBy(asking(ask(prescriptionId,
                    DispenseBillingDeferralKind.OUTPATIENT_CREDIT, "Cannot pay")))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.deferral.inpatientNotNeeded");
        }

        @Test
        @DisplayName("one open deferral per prescription; a card number in the reason is refused")
        void oneOpenAndNoCardData() {
            actingAs(pharmacist);
            when(deferralRepository.existsByHospitalIdAndPrescriptionIdAndStatusIn(eq(hospitalId), eq(prescriptionId),
                    any())).thenReturn(true);
            assertThatThrownBy(asking(ask(prescriptionId,
                    DispenseBillingDeferralKind.OUTPATIENT_CREDIT, "Cannot pay")))
                    .isInstanceOf(ConflictException.class);

            when(deferralRepository.existsByHospitalIdAndPrescriptionIdAndStatusIn(eq(hospitalId), eq(prescriptionId),
                    any())).thenReturn(false);
            assertThatThrownBy(asking(ask(prescriptionId,
                    DispenseBillingDeferralKind.OUTPATIENT_CREDIT, "card 4111 1111 1111 1111")))
                    .isInstanceOf(FieldBusinessException.class);
        }

        @Test
        @DisplayName("a prescription of another hospital answers as a missing one")
        void anotherHospitalsPrescription() {
            actingAs(pharmacist);
            UUID foreign = UUID.randomUUID();
            when(prescriptionRepository.findByIdAndHospitalIdForUpdate(foreign, hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(asking(ask(foreign, DispenseBillingDeferralKind.OUTPATIENT_CREDIT, "x")))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("deciding: another person, of the right desk")
    class Deciding {

        @Test
        @DisplayName("finance approves a credit with its reason and a limit")
        void financeApprovesACredit() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, pharmacist);
            actingAs(accountant);

            DispenseBillingDeferralDTO approved = service.approve(row.getId(),
                    new DispenseBillingDeferralDecisionDTO("Known family, pays at month end", null,
                            new BigDecimal("5000")));

            assertThat(approved.getStatus()).isEqualTo("APPROVED");
            assertThat(row.getDecidedBy()).isEqualTo(accountant);
            assertThat(row.getDecisionNote()).isEqualTo("Known family, pays at month end");
            assertThat(row.getCreditLimit()).isEqualByComparingTo("5000");
            verify(support).logAudit(eq(AuditEventType.DISPENSE_BILLING_DEFERRAL_APPROVED), anyString(),
                    eq(row.getId().toString()), anyString(), eq(hospitalId));
        }

        @Test
        @DisplayName("a credit needs the approver's reason; a limit is whole francs above zero")
        void aCreditNeedsAReasonAndAValidLimit() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, pharmacist);
            actingAs(accountant);

            assertThatThrownBy(approving(row.getId(), new DispenseBillingDeferralDecisionDTO()))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.deferral.creditReasonRequired");
            assertThatThrownBy(approving(row.getId(),
                    new DispenseBillingDeferralDecisionDTO("ok", null, new BigDecimal("12.5"))))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.deferral.limitInvalid");
            assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.REQUESTED);
        }

        @Test
        @DisplayName("billing approves a third-party cover against its voucher; without a reference it is refused")
        void billingChecksTheVoucher() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                    DispenseBillingDeferralStatus.REQUESTED, pharmacist);
            actingAs(billing);

            assertThatThrownBy(approving(row.getId(), null))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.deferral.coverReferenceRequired");

            service.approve(row.getId(), new DispenseBillingDeferralDecisionDTO(null, "AMU-2026-0042", null));
            assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.APPROVED);
            assertThat(row.getCoverReference()).isEqualTo("AMU-2026-0042");
        }

        @Test
        @DisplayName("billing alone does not grant a credit")
        void billingDoesNotGrantCredit() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, pharmacist);
            actingAs(billing);

            assertThatThrownBy(approving(row.getId(),
                    new DispenseBillingDeferralDecisionDTO("ok", null, null)))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
            assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.REQUESTED);
        }

        @Test
        @DisplayName("the pharmacist never approves: not an approver of anything")
        void thePharmacistNeverApproves() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                    DispenseBillingDeferralStatus.REQUESTED, accountant);
            actingAs(pharmacist);

            assertThatThrownBy(approving(row.getId(),
                    new DispenseBillingDeferralDecisionDTO(null, "V-1", null)))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
            assertThatThrownBy(rejecting(row.getId(), new DispenseBillingDeferralReasonDTO("no")))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
            verify(deferralRepository, never()).findLockedByIdAndHospitalId(any(), any());
        }

        @Test
        @DisplayName("never the same person: whoever asked does not approve or reject")
        void noSelfApproval() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, accountant);
            actingAs(accountant);

            assertThatThrownBy(approving(row.getId(),
                    new DispenseBillingDeferralDecisionDTO("ok", null, null)))
                    .isInstanceOf(ConflictException.class);
            assertThatThrownBy(rejecting(row.getId(), new DispenseBillingDeferralReasonDTO("no")))
                    .isInstanceOf(ConflictException.class);
            assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.REQUESTED);
        }

        @Test
        @DisplayName("a rejection and a withdrawal each need a reason; the withdrawal is stamped")
        void rejectAndCancelNeedAReason() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, pharmacist);
            actingAs(accountant);
            assertThatThrownBy(rejecting(row.getId(), new DispenseBillingDeferralReasonDTO("  ")))
                    .isInstanceOf(FieldBusinessException.class);

            actingAs(pharmacist);
            assertThatThrownBy(withdrawing(row.getId(), new DispenseBillingDeferralReasonDTO(null)))
                    .isInstanceOf(FieldBusinessException.class);
            service.cancel(row.getId(), new DispenseBillingDeferralReasonDTO("Patient paid at the till"));
            assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.CANCELLED);
            assertThat(row.getCancelledBy()).isEqualTo(pharmacist);
            assertThat(row.getCancelledAt()).isEqualTo(LocalDateTime.now(CLOCK));
            assertThat(row.getCancelReason()).isEqualTo("Patient paid at the till");
        }

        @Test
        @DisplayName("a deferral of another hospital answers as a missing one")
        void anotherHospitalsDeferral() {
            actingAs(accountant);
            UUID foreign = UUID.randomUUID();
            when(deferralRepository.findLockedByIdAndHospitalId(foreign, hospitalId)).thenReturn(Optional.empty());

            assertThatThrownBy(approving(foreign, null))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        }
    }

    @Nested
    @DisplayName("the counter's gate")
    class Gate {

        private final BigDecimal due = new BigDecimal("1000");

        @Test
        @DisplayName("no deferral without an approver: a fill with money due and nothing behind it is refused")
        void nothingBehindItIsRefused() {
            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, null), user(pharmacist)))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("field", "messageKey")
                    .containsExactly("billingDeferralId", "pharmacy.handOver.unpaid");
            verify(deferralRepository, never()).save(any());
        }

        @Test
        @DisplayName("a deferral only asked for, rejected, of another prescription or already used is refused")
        void onlyAnApprovedDeferralOfThisPrescription() {
            DispenseBillingDeferral requested = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, pharmacist);
            DispenseBillingDeferral used = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            used.setDecidedBy(accountant);
            used.setExecutedBy(pharmacist);
            used.setExecutedAt(LocalDateTime.now(CLOCK).minusDays(1));
            DispenseBillingDeferral other = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.APPROVED, pharmacist);
            other.setDecidedBy(accountant);
            other.setPrescriptionId(UUID.randomUUID());

            for (DispenseBillingDeferral row : List.of(requested, used, other)) {
                assertThatThrownBy(admitting(prescription, hospitalId, due,
                        new DispenseBillingDeferralService.Choice(row.getId(), null), user(pharmacist)))
                        .isInstanceOf(FieldBusinessException.class)
                        .extracting("messageKey").isEqualTo("pharmacy.handOver.deferralNotApproved");
            }
        }

        @Test
        @DisplayName("an approved credit is executed within its limit, and refused above it")
        void theLimitHolds() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.APPROVED, pharmacist);
            row.setDecidedBy(accountant);
            row.setCreditLimit(new BigDecimal("500"));

            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(row.getId(), null), user(pharmacist)))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.handOver.deferralOverLimit");
            assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.APPROVED);

            row.setCreditLimit(new BigDecimal("1000"));
            DispenseBillingDeferral executed = service.admit(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(row.getId(), null), user(pharmacist));
            assertThat(executed.getStatus()).isEqualTo(DispenseBillingDeferralStatus.EXECUTED);
            assertThat(executed.getExecutedBy()).isEqualTo(pharmacist);
            assertThat(executed.getAmountDue()).isEqualByComparingTo("1000");
        }

        @Test
        @DisplayName("a fill split over two lots is one hand-over: the same person goes on, the limit holds for the total")
        void aSplitFillIsOneHandOver() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.APPROVED, billing);
            row.setDecidedBy(accountant);
            row.setCreditLimit(new BigDecimal("1500"));
            DispenseBillingDeferralService.Choice choice = new DispenseBillingDeferralService.Choice(row.getId(), null);

            service.admit(prescription, hospitalId, new BigDecimal("600"), choice, user(pharmacist));
            service.admit(prescription, hospitalId, new BigDecimal("400"), choice, user(pharmacist));
            assertThat(row.getAmountDue()).isEqualByComparingTo("1000");

            assertThatThrownBy(admitting(prescription, hospitalId, new BigDecimal("600"), choice,
                    user(pharmacist)))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.handOver.deferralOverLimit");
            // Somebody else does not go on with a hand-over that is not theirs.
            when(roleValidator.canOverrideDispenseBillingForUrgency(billing, hospitalId)).thenReturn(false);
            assertThatThrownBy(admitting(prescription, hospitalId, new BigDecimal("100"), choice,
                    user(billing)))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.handOver.deferralNotApproved");
        }

        @Test
        @DisplayName("never the same person: the approver does not hand the fill over")
        void theApproverDoesNotExecute() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                    DispenseBillingDeferralStatus.APPROVED, pharmacist);
            row.setDecidedBy(accountant);

            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(row.getId(), null), user(accountant)))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.handOver.deferralApproverHandsOver");
        }

        @Test
        @DisplayName("an inpatient item goes on the account of the open stay: nobody approves, no reason is asked")
        void anInpatientItemGoesOnTheStay() {
            openStay(AdmissionStatus.ACTIVE);

            DispenseBillingDeferral row = service.admit(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, null), user(pharmacist));

            assertThat(row.getKind()).isEqualTo(DispenseBillingDeferralKind.ADMISSION_ACCOUNT);
            assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.EXECUTED);
            assertThat(row.getAdmissionId()).isNotNull();
            assertThat(row.getDecidedBy()).isNull();
            assertThat(row.getReason()).isNull();
        }

        @Test
        @DisplayName("a closed stay or a take-home prescription is not an inpatient item")
        void aClosedStayIsNotAnAccount() {
            openStay(AdmissionStatus.DISCHARGED);
            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, null), user(pharmacist)))
                    .isInstanceOf(FieldBusinessException.class);

            openStay(AdmissionStatus.ACTIVE);
            prescription.setTakeHome(true);
            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, null), user(pharmacist)))
                    .isInstanceOf(FieldBusinessException.class);
            assertThat(service.chargedToOpenStay(prescription, hospitalId)).isFalse();
        }

        /** QA D3404: the patient's stay at this hospital, as the repository answers for (patient, hospital, open). */
        private Admission admittedAt(AdmissionStatus status, LocalDateTime admittedAt) {
            Admission stay = new Admission();
            stay.setId(UUID.randomUUID());
            stay.setHospital(hospital);
            stay.setStatus(status);
            stay.setAdmissionDateTime(admittedAt);
            when(admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(eq(patientId), eq(hospitalId), any()))
                    .thenReturn(List.of(stay));
            return stay;
        }

        @Test
        @DisplayName("QA D3404: an inpatient order written without the stay's visit goes on the patient's open stay")
        void anInpatientOrderWithoutTheVisitFindsTheStay() {
            Admission stay = admittedAt(AdmissionStatus.ACTIVE, LocalDateTime.now(CLOCK).minusDays(1));
            prescription.setCreatedAt(LocalDateTime.now(CLOCK).minusHours(2));

            DispenseBillingDeferral row = service.admit(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, null), user(pharmacist));

            assertThat(row.getKind()).isEqualTo(DispenseBillingDeferralKind.ADMISSION_ACCOUNT);
            assertThat(row.getAdmissionId()).isEqualTo(stay.getId());
            assertThat(service.chargedToOpenStay(prescription, hospitalId)).isTrue();
            // Tenancy: only this hospital's open stays are asked for.
            verify(admissionRepository, atLeastOnce()).findByPatient_IdAndHospital_IdAndStatusIn(patientId, hospitalId,
                    DispenseBillingDeferralService.OPEN_STAY);
        }

        @Test
        @DisplayName("QA D3404: never a closed stay, never a stay admitted after the order, never another stay than the visit's")
        void neverAClosedOrLaterStay() {
            admittedAt(AdmissionStatus.DISCHARGED, LocalDateTime.now(CLOCK).minusDays(3));
            assertThat(service.chargedToOpenStay(prescription, hospitalId)).isFalse();

            prescription.setCreatedAt(LocalDateTime.now(CLOCK).minusDays(2));
            admittedAt(AdmissionStatus.ACTIVE, LocalDateTime.now(CLOCK).minusDays(1));
            assertThat(service.chargedToOpenStay(prescription, hospitalId)).isFalse();
            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, null), user(pharmacist)))
                    .isInstanceOf(FieldBusinessException.class);

            // An outpatient visit's order is the visit's, never the stay's (InpatientOrderEnd.orderOfStay).
            admittedAt(AdmissionStatus.ACTIVE, LocalDateTime.now(CLOCK).minusDays(1));
            prescription.setCreatedAt(LocalDateTime.now(CLOCK));
            Encounter consultation = new Encounter();
            consultation.setEncounterType(com.example.hms.enums.EncounterType.OUTPATIENT);
            prescription.setEncounter(consultation);
            assertThat(service.chargedToOpenStay(prescription, hospitalId)).isFalse();
            consultation.setEncounterType(com.example.hms.enums.EncounterType.INPATIENT);
            assertThat(service.chargedToOpenStay(prescription, hospitalId)).isTrue();

            // The visit names a stay that has ended: the patient's other open stay is not charged instead.
            prescription.setCreatedAt(LocalDateTime.now(CLOCK));
            openStay(AdmissionStatus.DISCHARGED);
            assertThat(service.chargedToOpenStay(prescription, hospitalId)).isFalse();
        }

        @Test
        @DisplayName("an urgency override: a pharmacist, with a reason, logged as an override awaiting review")
        void anUrgencyOverride() {
            DispenseBillingDeferral row = service.admit(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, "  Asthma attack, till closed "), user(pharmacist));

            assertThat(row.getKind()).isEqualTo(DispenseBillingDeferralKind.URGENCY_OVERRIDE);
            assertThat(row.getReason()).isEqualTo("Asthma attack, till closed");
            assertThat(row.getRequestedBy()).isEqualTo(pharmacist);
            assertThat(row.getReviewedAt()).isNull();

            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, "later"), user(billing)))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.handOver.urgencyRoleRequired");
            assertThatThrownBy(admitting(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(UUID.randomUUID(), "later"), user(pharmacist)))
                    .isInstanceOf(FieldBusinessException.class)
                    .extracting("messageKey").isEqualTo("pharmacy.handOver.deferBothGiven");
        }
    }

    @Nested
    @DisplayName("reviewing an urgency override")
    class Reviewing {

        @Test
        @DisplayName("finance reviews it with a note; the task is closed; the list no longer shows it")
        void financeReviews() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.URGENCY_OVERRIDE,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            actingAs(accountant);

            assertThatThrownBy(reviewing(row.getId(), new DispenseBillingDeferralReasonDTO(" ")))
                    .isInstanceOf(FieldBusinessException.class);
            DispenseBillingDeferralDTO reviewed = service.review(row.getId(),
                    new DispenseBillingDeferralReasonDTO("Justified, invoice issued"));

            assertThat(reviewed.getReviewedBy()).isEqualTo(accountant);
            assertThat(row.getReviewedAt()).isEqualTo(LocalDateTime.now(CLOCK));
            assertThat(row.getReviewNote()).isEqualTo("Justified, invoice issued");
            verify(followUp).close(row);
            verify(support).logAudit(eq(AuditEventType.DISPENSE_BILLING_OVERRIDE_REVIEWED), anyString(),
                    eq(row.getId().toString()), anyString(), eq(hospitalId));
            assertThatThrownBy(reviewing(row.getId(), new DispenseBillingDeferralReasonDTO("again")))
                    .isInstanceOf(ConflictException.class);
        }

        @Test
        @DisplayName("never the same person: whoever overrode does not review; the pharmacist is no reviewer")
        void noSelfReview() {
            DispenseBillingDeferral own = stored(DispenseBillingDeferralKind.URGENCY_OVERRIDE,
                    DispenseBillingDeferralStatus.EXECUTED, accountant);
            actingAs(accountant);
            assertThatThrownBy(reviewing(own.getId(), new DispenseBillingDeferralReasonDTO("fine")))
                    .isInstanceOf(ConflictException.class);

            actingAs(pharmacist);
            assertThatThrownBy(reviewing(own.getId(), new DispenseBillingDeferralReasonDTO("fine")))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
            assertThat(own.getReviewedAt()).isNull();
        }

        @Test
        @DisplayName("the work list: open deferrals and overrides not yet reviewed, of the acting hospital")
        void theWorkList() {
            DispenseBillingDeferral open = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, pharmacist);
            DispenseBillingDeferral override = stored(DispenseBillingDeferralKind.URGENCY_OVERRIDE,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            when(deferralRepository.findByHospitalIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId), any()))
                    .thenReturn(List.of(open));
            when(deferralRepository.findByHospitalIdAndKindAndReviewedAtIsNullOrderByRequestedAtAsc(hospitalId,
                    DispenseBillingDeferralKind.URGENCY_OVERRIDE)).thenReturn(List.of(override));
            actingAs(accountant);

            List<DispenseBillingDeferralDTO> work = service.list(null, null, false);

            assertThat(work).extracting(DispenseBillingDeferralDTO::getId)
                    .containsExactly(open.getId(), override.getId());
            assertThat(work.get(0).isCanDecide()).isTrue();
            assertThat(work.get(1).isCanReview()).isTrue();
            assertThat(work.get(0).getMedicationName()).isEqualTo("Amoxicilline 500 mg");
        }

        @Test
        @DisplayName("the counter view: an inpatient order needs nothing; otherwise the open deferrals of the prescription")
        void theCounterView() {
            DispenseBillingDeferral approved = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.APPROVED, billing);
            approved.setDecidedBy(accountant);
            when(deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId),
                    eq(prescriptionId), any())).thenReturn(List.of(approved));
            actingAs(pharmacist);

            var counter = service.counter(prescriptionId);
            assertThat(counter.isOnOpenStay()).isFalse();
            assertThat(counter.getOpen()).singleElement().satisfies(row -> {
                assertThat(row.isCanExecute()).as("approved by somebody else: the pharmacist may hand over").isTrue();
                assertThat(row.isCanDecide()).isFalse();
            });

            // The approver reads the same row and may not hand the fill over on it.
            actingAs(accountant);
            assertThat(service.counter(prescriptionId).getOpen().get(0).isCanExecute()).isFalse();

            openStay(AdmissionStatus.ACTIVE);
            assertThat(service.counter(prescriptionId).isOnOpenStay()).isTrue();
        }

        @Test
        @DisplayName("the counter view of a prescription of another hospital answers as a missing one; a reader is required")
        void theCounterViewIsScoped() {
            actingAs(pharmacist);
            Hospital elsewhere = new Hospital();
            elsewhere.setId(UUID.randomUUID());
            prescription.setHospital(elsewhere);
            assertThatThrownBy(() -> service.counter(prescriptionId))
                    .isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);

            prescription.setHospital(hospital);
            when(roleValidator.canRequestDispenseBillingDeferral(any(), eq(hospitalId))).thenReturn(false);
            UUID nobody = UUID.randomUUID();
            actingAs(nobody);
            when(roleValidator.canReadDispenseBillingCounter(nobody, hospitalId)).thenReturn(false);
            assertThatThrownBy(() -> service.counter(prescriptionId))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
            assertThatThrownBy(() -> service.list(null, null, false))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
        }

        @Test
        @DisplayName("the reconciliation reads deferrals by id, at its hospital only, with nobody's actions on them")
        void byIdsForTheReconciliation() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.URGENCY_OVERRIDE,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            when(deferralRepository.findByHospitalIdAndIdIn(hospitalId, List.of(row.getId()))).thenReturn(List.of(row));
            when(userRepository.findById(pharmacist)).thenReturn(Optional.of(named(pharmacist, "Awa", "Pharmacienne")));

            var byId = service.byIds(hospitalId, List.of(row.getId()));

            assertThat(byId).containsOnlyKeys(row.getId());
            assertThat(byId.get(row.getId()).getRequestedByName()).isEqualTo("Awa Pharmacienne");
            assertThat(byId.get(row.getId()).isCanReview()).isFalse();
            assertThat(service.byIds(hospitalId, List.of())).isEmpty();
            assertThat(service.byIds(null, List.of(row.getId()))).isEmpty();
        }
    }

    @Nested
    @DisplayName("review of #1244")
    class ReviewOfPr1244 {

        private final BigDecimal due = new BigDecimal("600");

        @Test
        @DisplayName("a fill split over lots is one override: the further lot goes on the same row, and one review is asked")
        void oneOverrideForOneHandOver() {
            DispenseBillingDeferralService.Choice urgency =
                    new DispenseBillingDeferralService.Choice(null, "Asthma attack, till closed");
            DispenseBillingDeferral first = service.admit(prescription, hospitalId, due, urgency, user(pharmacist));
            when(deferralRepository
                    .findFirstByHospitalIdAndPrescriptionIdAndKindAndRequestedByAndReviewedAtIsNullOrderByRequestedAtDesc(
                            hospitalId, prescriptionId, DispenseBillingDeferralKind.URGENCY_OVERRIDE, pharmacist))
                    .thenReturn(Optional.of(first));

            DispenseBillingDeferral second = service.admit(prescription, hospitalId, new BigDecimal("400"), urgency,
                    user(pharmacist));

            assertThat(second).isSameAs(first);
            assertThat(first.getAmountDue()).isEqualByComparingTo("1000");
            verify(deferralRepository, times(1)).save(any());

            // Each fill has its audit line; the review task is filed once, for the first.
            com.example.hms.model.pharmacy.Dispense lotA = new com.example.hms.model.pharmacy.Dispense();
            lotA.setId(UUID.randomUUID());
            com.example.hms.model.pharmacy.Dispense lotB = new com.example.hms.model.pharmacy.Dispense();
            lotB.setId(UUID.randomUUID());
            service.executed(first, lotA, prescription);
            service.executed(first, lotB, prescription);
            assertThat(first.getDispenseId()).isEqualTo(lotA.getId());
            verify(followUp, times(1)).reviewNeeded(eq(first), any(), any());
            // QA D2924 (r66): each line names the hospital, or the hospital admin's audit log cannot list it.
            verify(support, times(2)).logAudit(
                    eq(AuditEventType.DISPENSE_BILLING_URGENCY_OVERRIDE), anyString(), anyString(), anyString(),
                    eq(hospitalId));
        }

        @Test
        @DisplayName("another reason, another day or a reviewed override is not the same hand-over: a new override")
        void notTheSameHandOver() {
            DispenseBillingDeferral yesterday = stored(DispenseBillingDeferralKind.URGENCY_OVERRIDE,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            yesterday.setReason("Asthma attack, till closed");
            yesterday.setRequestedAt(LocalDateTime.now(CLOCK).minusDays(1));
            yesterday.setAmountDue(new BigDecimal("500"));
            when(deferralRepository
                    .findFirstByHospitalIdAndPrescriptionIdAndKindAndRequestedByAndReviewedAtIsNullOrderByRequestedAtDesc(
                            hospitalId, prescriptionId, DispenseBillingDeferralKind.URGENCY_OVERRIDE, pharmacist))
                    .thenReturn(Optional.of(yesterday));

            DispenseBillingDeferral today = service.admit(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, "Asthma attack, till closed"), user(pharmacist));
            assertThat(today).isNotSameAs(yesterday);
            assertThat(yesterday.getAmountDue()).isEqualByComparingTo("500");

            yesterday.setRequestedAt(LocalDateTime.now(CLOCK));
            DispenseBillingDeferral otherReason = service.admit(prescription, hospitalId, due,
                    new DispenseBillingDeferralService.Choice(null, "Another emergency"), user(pharmacist));
            assertThat(otherReason).isNotSameAs(yesterday);
        }

        @Test
        @DisplayName("a cancelled fill gives back exactly what it left owing; the deferral never goes below zero")
        void aCancelledFillGivesItsAmountBack() {
            DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.URGENCY_OVERRIDE,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            row.setAmountDue(new BigDecimal("1000"));
            com.example.hms.model.pharmacy.Pharmacy pharmacy = com.example.hms.model.pharmacy.Pharmacy.builder()
                    .hospital(hospital).name("Pharmacie").build();
            com.example.hms.model.pharmacy.Dispense lot = new com.example.hms.model.pharmacy.Dispense();
            lot.setPharmacy(pharmacy);
            service.stamp(lot, row, pharmacist, LocalDateTime.now(CLOCK), new BigDecimal("400"));
            assertThat(lot.getBillingDeferredAmount()).isEqualByComparingTo("400");
            assertThat(lot.getBillingDeferralId()).isEqualTo(row.getId());

            service.fillCancelled(lot);
            assertThat(row.getAmountDue()).isEqualByComparingTo("600");
            assertThat(lot.getBillingDeferredAmount()).as("not given back twice").isEqualByComparingTo("0");
            service.fillCancelled(lot);
            assertThat(row.getAmountDue()).isEqualByComparingTo("600");

            // A fill with no deferral, or of a deferral this hospital does not hold, changes nothing.
            service.fillCancelled(new com.example.hms.model.pharmacy.Dispense());
            service.fillCancelled(null);
            assertThat(row.getAmountDue()).isEqualByComparingTo("600");
        }

        @Test
        @DisplayName("the pharmacy verifier reads the counter view, and nothing else")
        void theVerifierReadsTheCounterOnly() {
            UUID verifier = UUID.randomUUID();
            actingAs(verifier);
            when(roleValidator.canRequestDispenseBillingDeferral(verifier, hospitalId)).thenReturn(false);
            when(roleValidator.canReadDispenseBillingCounter(verifier, hospitalId)).thenReturn(true);
            when(deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId),
                    eq(prescriptionId), any())).thenReturn(List.of());

            assertThat(service.counter(prescriptionId).getOpen()).isEmpty();
            assertThatThrownBy(() -> service.list(null, null, false))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
            // And whoever does not hand fills over reads no counter either.
            when(roleValidator.canReadDispenseBillingCounter(verifier, hospitalId)).thenReturn(false);
            assertThatThrownBy(() -> service.counter(prescriptionId))
                    .isInstanceOf(ClientSafeAccessDeniedException.class);
        }

        @Test
        @DisplayName("« sorties, encore dues »: only the executed deferrals whose fills still owe money now")
        void executedAndStillDue() {
            DispenseBillingDeferral owing = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            DispenseBillingDeferral settled = stored(DispenseBillingDeferralKind.URGENCY_OVERRIDE,
                    DispenseBillingDeferralStatus.EXECUTED, pharmacist);
            when(deferralRepository.findByHospitalIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId), any()))
                    .thenReturn(List.of(owing, settled));
            DeferredFillBalance balance = mock(DeferredFillBalance.class);
            when(balance.stillDue(owing)).thenReturn(new BigDecimal("700"));
            when(balance.stillDue(settled)).thenReturn(BigDecimal.ZERO);
            service.setFillBalance(balance);
            actingAs(accountant);

            List<DispenseBillingDeferralDTO> all = service.list(List.of(DispenseBillingDeferralStatus.EXECUTED), null,
                    false);
            assertThat(all).hasSize(2);
            List<DispenseBillingDeferralDTO> stillOwing = service.list(List.of(DispenseBillingDeferralStatus.EXECUTED), null,
                    true);
            assertThat(stillOwing).singleElement().satisfies(row -> {
                assertThat(row.getId()).isEqualTo(owing.getId());
                assertThat(row.getStillDue()).isEqualByComparingTo("700");
            });
        }
    }

    private static User named(UUID id, String first, String last) {
        User u = new User();
        u.setId(id);
        u.setFirstName(first);
        u.setLastName(last);
        return u;
    }

    @Nested
    @DisplayName("QA D2929 (r66): an order that left the counter with nothing due closes its open deferrals")
    class SettledOrder {

        @Test
        @DisplayName("a credit still asked for, and one approved, are withdrawn by the hand-over with the reason; task and bell closed; audited at the hospital")
        void openDeferralsAreClosed() {
            DispenseBillingDeferral asked = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, billing);
            DispenseBillingDeferral approved = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                    DispenseBillingDeferralStatus.APPROVED, billing);
            approved.setDecidedBy(accountant);
            when(deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(
                    eq(hospitalId), eq(prescriptionId), any())).thenReturn(java.util.List.of(asked, approved));

            service.closeOpenOnSettledOrder(prescription, hospitalId, pharmacist);

            for (DispenseBillingDeferral row : java.util.List.of(asked, approved)) {
                assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.CANCELLED);
                assertThat(row.getCancelledBy()).isEqualTo(pharmacist);
                assertThat(row.getCancelledAt()).isEqualTo(LocalDateTime.now(CLOCK));
                assertThat(row.getCancelReason()).startsWith("Clos automatiquement");
                verify(followUp).close(row);
                verify(support).logAudit(eq(AuditEventType.DISPENSE_BILLING_DEFERRAL_CANCELLED), anyString(),
                        eq(row.getId().toString()), anyString(), eq(hospitalId));
            }
        }

        @Test
        @DisplayName("a deferral decided or used in the meantime is left as it is; nothing open, nothing written")
        void onlyOpenOnesAreClosed() {
            DispenseBillingDeferral listed = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                    DispenseBillingDeferralStatus.REQUESTED, billing);
            when(deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(
                    eq(hospitalId), eq(prescriptionId), any())).thenReturn(java.util.List.of(listed));
            // Somebody rejected it between the list and the lock.
            listed.setStatus(DispenseBillingDeferralStatus.REJECTED);

            service.closeOpenOnSettledOrder(prescription, hospitalId, pharmacist);
            service.closeOpenOnSettledOrder(null, hospitalId, pharmacist);
            service.closeOpenOnSettledOrder(prescription, hospitalId, null);

            assertThat(listed.getStatus()).isEqualTo(DispenseBillingDeferralStatus.REJECTED);
            assertThat(listed.getCancelledBy()).isNull();
            verify(followUp, never()).close(any());
        }
    }

    @Test
    @DisplayName("QA D2931 (r66): the gate refuses with the words it is given when the counter sent no basis")
    void theRefusalSaysWhyTheClaimDoesNotCover() {
        BigDecimal due = new BigDecimal("90");
        User counter = user(pharmacist);
        assertThatThrownBy(() -> service.admit(prescription, hospitalId, due, null, counter,
                FillClaimCover.Gap.OWN_VOUCHER))
                .isInstanceOf(FieldBusinessException.class)
                .extracting("field", "messageKey")
                .containsExactly("billingDeferralId", "pharmacy.handOver.claimVouchedBySelf");
        assertThatThrownBy(() -> service.admit(prescription, hospitalId, due, null, counter,
                FillClaimCover.Gap.NO_VOUCHER))
                .isInstanceOf(FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.handOver.claimNotVouched");
        assertThatThrownBy(() -> service.admit(prescription, hospitalId, due, null, counter))
                .isInstanceOf(FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.handOver.unpaid");
    }
}
