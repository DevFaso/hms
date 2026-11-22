package com.example.hms.service.pharmacy;

import com.example.hms.enums.DispenseBillingDeferralKind;
import com.example.hms.enums.DispenseBillingDeferralStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.DispenseBillingDeferral;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDTO;
import com.example.hms.payload.dto.pharmacy.DispenseBillingDeferralDecisionDTO;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseBillingDeferralRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * QA D3406 / D3408 (r70): a deferral is decided on the amount of its fill. The approver sees the fill's charge and
 * what it owes; a credit ceiling below it is refused at approval (it used to be refused at the hand-over, a dead
 * end); a third-party cover records the insurer share by the patient's coverage and leaves only that on the
 * voucher, the patient share being paid first.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeferralFillAmountTest {

    private static final Clock CLOCK = Clock.fixed(LocalDateTime.of(2026, 11, 22, 9, 0).toInstant(ZoneOffset.UTC),
            ZoneOffset.UTC);

    @Mock private DispenseBillingDeferralRepository deferralRepository;
    @Mock private PrescriptionRepository prescriptionRepository;
    @Mock private AdmissionRepository admissionRepository;
    @Mock private UserRepository userRepository;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;
    @Mock private DispenseBillingDeferralFollowUp followUp;
    @Mock private AuditEventLogService auditEventLogService;
    @Mock private FillCoverShare coverShare;

    private DispenseBillingDeferralService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();
    private final UUID accountant = UUID.randomUUID();
    private final UUID billing = UUID.randomUUID();
    private final UUID prescriptionId = UUID.randomUUID();
    private final UUID patientId = UUID.randomUUID();
    private Prescription prescription;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        service = new DispenseBillingDeferralService(deferralRepository, prescriptionRepository, admissionRepository,
                userRepository, roleValidator, support, followUp, auditEventLogService, CLOCK);
        service.setCoverShare(coverShare);

        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        Patient patient = new Patient();
        patient.setId(patientId);
        prescription = new Prescription();
        prescription.setId(prescriptionId);
        prescription.setHospital(hospital);
        prescription.setPatient(patient);
        prescription.setMedicationName("Artéméther-luméfantrine 80/480");

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(prescriptionRepository.findById(prescriptionId)).thenReturn(Optional.of(prescription));
        when(roleValidator.canApproveThirdPartyCover(accountant, hospitalId)).thenReturn(true);
        when(roleValidator.canApproveOutpatientCredit(accountant, hospitalId)).thenReturn(true);
        when(roleValidator.canApproveThirdPartyCover(billing, hospitalId)).thenReturn(true);
        when(roleValidator.canRequestDispenseBillingDeferral(any(), eq(hospitalId))).thenReturn(true);
        // The prepared fill: 1 000 F, nothing paid yet.
        when(coverShare.preparedFill(prescriptionId, hospitalId)).thenReturn(Optional.of(
                new FillCoverShare.PreparedFill(UUID.randomUUID(), new BigDecimal("1000"), BigDecimal.ZERO,
                        new BigDecimal("1000"))));
    }

    private DispenseBillingDeferral stored(DispenseBillingDeferralKind kind, DispenseBillingDeferralStatus status) {
        DispenseBillingDeferral row = DispenseBillingDeferral.builder().hospitalId(hospitalId)
                .prescriptionId(prescriptionId).patientId(patientId).kind(kind).status(status)
                .reason("Pays at month end").requestedBy(pharmacist).requestedAt(LocalDateTime.now(CLOCK)).build();
        row.setId(UUID.randomUUID());
        when(deferralRepository.findLockedByIdAndHospitalId(row.getId(), hospitalId)).thenReturn(Optional.of(row));
        when(deferralRepository.findByHospitalIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId), any()))
                .thenReturn(List.of(row));
        return row;
    }

    private static User user(UUID id) {
        User u = new User();
        u.setId(id);
        return u;
    }

    @Test
    @DisplayName("QA D3406: the card shows the fill's charge and what it owes before anybody decides")
    void d3406_theCardShowsTheFillAmount() {
        stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT, DispenseBillingDeferralStatus.REQUESTED);
        when(roleValidator.getCurrentUserId()).thenReturn(accountant);

        List<DispenseBillingDeferralDTO> rows = service.list(null, null, false);

        assertThat(rows).singleElement().satisfies(dto -> {
            assertThat(dto.getFillCharge()).isEqualByComparingTo("1000");
            assertThat(dto.getFillDue()).isEqualByComparingTo("1000");
            assertThat(dto.getInsurerShare()).isNull();
        });
    }

    @Test
    @DisplayName("QA D3406: a credit ceiling below what the fill owes is refused at approval, naming both amounts")
    void d3406_aCeilingBelowTheFillIsRefusedAtApproval() {
        DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                DispenseBillingDeferralStatus.REQUESTED);
        when(roleValidator.getCurrentUserId()).thenReturn(accountant);
        UUID id = row.getId();
        DispenseBillingDeferralDecisionDTO below = new DispenseBillingDeferralDecisionDTO("Known family", null,
                new BigDecimal("500"));

        assertThatThrownBy(() -> service.approve(id, below))
                .isInstanceOf(FieldBusinessException.class)
                .satisfies(e -> {
                    FieldBusinessException fe = (FieldBusinessException) e;
                    assertThat(fe.getField()).isEqualTo("creditLimit");
                    assertThat(fe.getMessageKey()).isEqualTo("pharmacy.deferral.creditLimitBelowDue");
                    assertThat(fe.getMessage()).contains("500").contains("1000");
                });
        assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.REQUESTED);

        service.approve(id, new DispenseBillingDeferralDecisionDTO("Known family", null, new BigDecimal("1000")));
        assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.APPROVED);
        assertThat(row.getCreditLimit()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("QA D3408: a cover records the insurer share by the coverage; the hand-over asks for the patient share")
    void d3408_aCoverLeavesTheInsurerShareOnly() {
        DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                DispenseBillingDeferralStatus.REQUESTED);
        when(roleValidator.getCurrentUserId()).thenReturn(billing);
        when(coverShare.planShare(patientId, hospitalId, new BigDecimal("1000"), prescriptionId))
                .thenReturn(Optional.of(new BigDecimal("750")));

        service.approve(row.getId(), new DispenseBillingDeferralDecisionDTO(null, "MTA-BON-0001", null));

        assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.APPROVED);
        assertThat(row.getCreditLimit()).as("the insurer share, never typed").isEqualByComparingTo("750");

        // Nothing paid: the whole 1 000 F cannot leave on the voucher; the 250 F patient share is asked for.
        DispenseBillingDeferralService.Choice choice = new DispenseBillingDeferralService.Choice(row.getId(), null);
        User handingOver = user(pharmacist);
        row.setDecidedBy(billing);
        BigDecimal wholeFill = new BigDecimal("1000");
        assertThatThrownBy(() -> service.admit(prescription, hospitalId, wholeFill, choice, handingOver))
                .isInstanceOf(FieldBusinessException.class)
                .satisfies(e -> {
                    FieldBusinessException fe = (FieldBusinessException) e;
                    assertThat(fe.getMessageKey()).isEqualTo("pharmacy.handOver.coverPatientShareDue");
                    assertThat(fe.getMessage()).contains("250").contains("750");
                });

        // The patient share paid: what is due is the insurer share, which leaves on the cover.
        DispenseBillingDeferral executed = service.admit(prescription, hospitalId, new BigDecimal("750"), choice,
                handingOver);
        assertThat(executed.getStatus()).isEqualTo(DispenseBillingDeferralStatus.EXECUTED);
        assertThat(executed.getAmountDue()).isEqualByComparingTo("750");
    }

    private DispenseBillingDeferral approvedCover(String insurerShare) {
        DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                DispenseBillingDeferralStatus.APPROVED);
        row.setDecidedBy(billing);
        row.setCreditLimit(new BigDecimal(insurerShare));
        // capOf: the recorded share, or the plan's share of the fill handing over, whichever is less.
        when(coverShare.capOf(any(), any(), any(), any(), any())).thenAnswer(call -> {
            BigDecimal recorded = call.getArgument(0);
            BigDecimal charge = call.getArgument(3);
            return recorded.min(charge.multiply(new BigDecimal("0.75")));
        });
        return row;
    }

    @Test
    @DisplayName("review of #1284: a cover AND a claim on one fill never let the patient share leave unpaid")
    void aClaimNeverStandsInForThePatientShare() {
        // 1 560 fill at 75 %: insurer 1 170, patient 390. The claim of 1 170 is submitted; the patient paid nothing.
        DispenseBillingDeferral row = approvedCover("1170");
        DispenseBillingDeferralService.Choice choice = new DispenseBillingDeferralService.Choice(row.getId(), null);
        User handingOver = user(pharmacist);
        BigDecimal dueAfterTheClaim = new BigDecimal("390");
        DispenseBillingDeferralService.FillMoney nothingPaid =
                new DispenseBillingDeferralService.FillMoney(new BigDecimal("1560"), BigDecimal.ZERO);

        assertThatThrownBy(() -> service.admit(prescription, hospitalId, dueAfterTheClaim, choice, handingOver,
                FillClaimCover.Gap.NONE, nothingPaid))
                .isInstanceOf(FieldBusinessException.class)
                .satisfies(e -> {
                    FieldBusinessException fe = (FieldBusinessException) e;
                    assertThat(fe.getMessageKey()).isEqualTo("pharmacy.handOver.coverPatientShareDue");
                    assertThat(fe.getMessage()).contains("390").contains("1170");
                });
        assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.APPROVED);

        // The 390 F patient share paid: the fill leaves on the cover.
        DispenseBillingDeferral executed = service.admit(prescription, hospitalId, new BigDecimal("1170"), choice,
                handingOver, FillClaimCover.Gap.NONE,
                new DispenseBillingDeferralService.FillMoney(new BigDecimal("1560"), new BigDecimal("390")));
        assertThat(executed.getStatus()).isEqualTo(DispenseBillingDeferralStatus.EXECUTED);
    }

    @Test
    @DisplayName("review of #1284: a smaller fill prepared again carries the plan share of ITS charge, not the old one")
    void aSmallerFillCarriesItsOwnInsurerShare() {
        // Approved on 1 000 (insurer 750); the fill that leaves costs 400: the insurer pays 300, the patient 100.
        DispenseBillingDeferral row = approvedCover("750");
        DispenseBillingDeferralService.Choice choice = new DispenseBillingDeferralService.Choice(row.getId(), null);
        User handingOver = user(pharmacist);
        BigDecimal due = new BigDecimal("400");
        DispenseBillingDeferralService.FillMoney nothingPaid =
                new DispenseBillingDeferralService.FillMoney(new BigDecimal("400"), BigDecimal.ZERO);

        assertThatThrownBy(() -> service.admit(prescription, hospitalId, due, choice, handingOver,
                FillClaimCover.Gap.NONE, nothingPaid))
                .satisfies(e -> assertThat(e.getMessage()).contains("100").contains("300"));

        DispenseBillingDeferral executed = service.admit(prescription, hospitalId, new BigDecimal("300"), choice,
                handingOver, FillClaimCover.Gap.NONE,
                new DispenseBillingDeferralService.FillMoney(new BigDecimal("400"), new BigDecimal("100")));
        assertThat(executed.getStatus()).isEqualTo(DispenseBillingDeferralStatus.EXECUTED);
    }

    @Test
    @DisplayName("review of #1284: a cancelled prepared fill withdraws the open covers of its order, never a credit")
    void aCancelledFillWithdrawsItsCovers() {
        DispenseBillingDeferral cover = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                DispenseBillingDeferralStatus.APPROVED);
        DispenseBillingDeferral credit = stored(DispenseBillingDeferralKind.OUTPATIENT_CREDIT,
                DispenseBillingDeferralStatus.APPROVED);
        when(deferralRepository.findByHospitalIdAndPrescriptionIdAndStatusInOrderByRequestedAtAsc(eq(hospitalId),
                eq(prescriptionId), any())).thenReturn(List.of(cover, credit));
        when(roleValidator.getCurrentUserId()).thenReturn(pharmacist);

        service.withdrawCoversOfCancelledFill(prescription, hospitalId);

        assertThat(cover.getStatus()).isEqualTo(DispenseBillingDeferralStatus.CANCELLED);
        assertThat(cover.getCancelReason()).isNotBlank();
        assertThat(cover.getCancelledBy()).isEqualTo(pharmacist);
        assertThat(credit.getStatus()).isEqualTo(DispenseBillingDeferralStatus.APPROVED);
    }

    @Test
    @DisplayName("QA D3408: a cover with no plan coverage, or none prepared, is refused at approval")
    void d3408_aCoverNeedsAPlanAndAPreparedFill() {
        DispenseBillingDeferral row = stored(DispenseBillingDeferralKind.THIRD_PARTY_COVER,
                DispenseBillingDeferralStatus.REQUESTED);
        when(roleValidator.getCurrentUserId()).thenReturn(billing);
        when(coverShare.planShare(any(), any(), any(), any())).thenReturn(Optional.empty());
        UUID id = row.getId();
        DispenseBillingDeferralDecisionDTO voucher = new DispenseBillingDeferralDecisionDTO(null, "MTA-BON-0002", null);

        assertThatThrownBy(() -> service.approve(id, voucher))
                .extracting("messageKey").isEqualTo("pharmacy.deferral.coverNoPlan");

        when(coverShare.preparedFill(prescriptionId, hospitalId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.approve(id, voucher))
                .extracting("messageKey").isEqualTo("pharmacy.deferral.coverNeedsPreparedFill");
        assertThat(row.getStatus()).isEqualTo(DispenseBillingDeferralStatus.REQUESTED);
    }
}
