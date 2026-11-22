package com.example.hms.service.pharmacy;

import com.example.hms.enums.PharmacyClaimStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.mapper.pharmacy.PharmacyClaimMapper;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.pharmacy.Dispense;
import com.example.hms.model.pharmacy.Pharmacy;
import com.example.hms.model.pharmacy.PharmacyClaim;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyClaimResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.pharmacy.DispenseRepository;
import com.example.hms.repository.pharmacy.PharmacyClaimRepository;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * T-52: unit tests for pharmacy claim service — lifecycle transitions,
 * tenant isolation, and validation.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("PharmacyClaimServiceImpl")
class PharmacyClaimServiceImplTest {

    @Mock private PharmacyClaimRepository claimRepository;
    @Mock private DispenseRepository dispenseRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRepository userRepository;
    @Mock private PharmacyClaimMapper claimMapper;
    @Mock private RoleValidator roleValidator;
    @Mock private PharmacyServiceSupport support;
    @Mock private com.example.hms.repository.pharmacy.InventoryItemRepository inventoryItemRepository;
    @Mock private com.example.hms.repository.pharmacy.PharmacyPaymentRepository paymentRepository;

    @InjectMocks private PharmacyClaimServiceImpl service;

    private UUID hospitalId;
    private UUID dispenseId;
    private UUID patientId;
    private UUID userId;
    private Hospital hospital;
    private Patient patient;
    private Dispense dispense;
    private User user;

    @BeforeEach
    void setUp() {
        hospitalId = UUID.randomUUID();
        dispenseId = UUID.randomUUID();
        patientId = UUID.randomUUID();
        userId = UUID.randomUUID();

        hospital = new Hospital();
        hospital.setId(hospitalId);

        patient = new Patient();
        patient.setId(patientId);

        Pharmacy pharmacy = new Pharmacy();
        pharmacy.setId(UUID.randomUUID());
        pharmacy.setHospital(hospital);

        dispense = new Dispense();
        dispense.setId(dispenseId);
        dispense.setPatient(patient);
        dispense.setPharmacy(pharmacy);
        // QA D1613: 10 units at 600 XOF, so the dispense costs 6 000 (the request claims 5 000).
        com.example.hms.model.medication.MedicationCatalogItem catalogItem =
                new com.example.hms.model.medication.MedicationCatalogItem();
        catalogItem.setId(UUID.randomUUID());
        dispense.setMedicationCatalogItem(catalogItem);
        dispense.setQuantityDispensed(new BigDecimal("10"));
        com.example.hms.model.pharmacy.InventoryItem item = new com.example.hms.model.pharmacy.InventoryItem();
        item.setUnitPrice(new BigDecimal("600.00"));
        org.mockito.Mockito.lenient().when(inventoryItemRepository.findByPharmacyIdAndMedicationCatalogItemId(
                pharmacy.getId(), catalogItem.getId())).thenReturn(Optional.of(item));

        user = new User();
        user.setId(userId);
        // QA D2925 (r66): by default the caller is of a money desk of the hospital; actingAs(false) is the pharmacy.
        org.mockito.Mockito.lenient().when(roleValidator.canRecordPayerDecision(any(), eq(hospitalId)))
                .thenReturn(true);
    }

    private PharmacyClaimRequestDTO request() {
        return PharmacyClaimRequestDTO.builder()
                .dispenseId(dispenseId)
                .patientId(patientId)
                .hospitalId(hospitalId)
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .build();
    }

    @Test
    @DisplayName("createClaim: persists DRAFT and audits")
    void createDraftSucceeds() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        PharmacyClaim entity = PharmacyClaim.builder()
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .claimStatus(PharmacyClaimStatus.DRAFT)
                .hospital(hospital)
                .build();
        when(claimMapper.toEntity(any(), any(), any(), any())).thenReturn(entity);
        when(claimRepository.save(any())).thenAnswer(inv -> {
            PharmacyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        PharmacyClaimResponseDTO result = service.createClaim(request());

        assertThat(result).isNotNull();
        verify(claimRepository).save(any());
        verify(support).logAudit(any(), any(), any(), any());
    }

    private void claimable() {
        org.mockito.Mockito.lenient().when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        org.mockito.Mockito.lenient().when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        org.mockito.Mockito.lenient().when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        org.mockito.Mockito.lenient().when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        org.mockito.Mockito.lenient().when(claimMapper.toEntity(any(), any(), any(), any())).thenAnswer(inv -> PharmacyClaim.builder()
                .amount(inv.<PharmacyClaimRequestDTO>getArgument(0).getAmount()).currency("XOF")
                .claimStatus(PharmacyClaimStatus.DRAFT).hospital(hospital).build());
        org.mockito.Mockito.lenient().when(claimRepository.save(any())).thenAnswer(inv -> {
            PharmacyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        org.mockito.Mockito.lenient().when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());
    }

    @Test
    @DisplayName("QA D3408 (r70): the claim is the insurer share the cover recorded; left out, it is filled in")
    void d3408_theClaimIsTheCoverInsurerShare() {
        FillCoverShare cover = mock(FillCoverShare.class);
        service.setFillCoverShare(cover);
        when(cover.coverShare(dispense, hospitalId)).thenReturn(Optional.of(new BigDecimal("4500")));
        claimable();
        PharmacyClaimRequestDTO untyped = request();
        untyped.setAmount(null);

        service.createClaim(untyped);

        org.mockito.ArgumentCaptor<PharmacyClaim> saved = org.mockito.ArgumentCaptor.forClass(PharmacyClaim.class);
        verify(claimRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("4500");
    }

    @Test
    @DisplayName("QA D3408 (r70): a typed amount that is not the insurer share by the plan is refused, naming it")
    void d3408_aTypedAmountOtherThanThePlanShareIsRefused() {
        FillCoverShare cover = mock(FillCoverShare.class);
        service.setFillCoverShare(cover);
        when(cover.coverShare(dispense, hospitalId)).thenReturn(Optional.empty());
        when(cover.planShare(patientId, hospitalId, new BigDecimal("6000")))
                .thenReturn(Optional.of(new BigDecimal("4500")));
        claimable();
        PharmacyClaimRequestDTO typed = request();

        assertThatThrownBy(() -> service.createClaim(typed))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.amountIsInsurerShare");
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("QA D3408 (r70): a coverage with no plan keeps the typed amount; none typed is refused")
    void d3408_aCoverageWithNoPlanKeepsTheTypedAmount() {
        FillCoverShare cover = mock(FillCoverShare.class);
        service.setFillCoverShare(cover);
        when(cover.coverShare(any(), any())).thenReturn(Optional.empty());
        when(cover.planShare(any(), any(), any())).thenReturn(Optional.empty());
        claimable();
        PharmacyClaimRequestDTO untyped = request();
        untyped.setAmount(null);

        assertThatThrownBy(() -> service.createClaim(untyped))
                .extracting("messageKey").isEqualTo("pharmacy.claim.amountPositive");

        service.createClaim(request());
        org.mockito.ArgumentCaptor<PharmacyClaim> saved = org.mockito.ArgumentCaptor.forClass(PharmacyClaim.class);
        verify(claimRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo("5000");
    }

    @Test
    @DisplayName("createClaim: rejects cross-hospital dispense")
    void rejectsCrossHospitalDispense() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        dispense.getPharmacy().setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects non-positive amount")
    void rejectsNonPositiveAmount() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyClaimRequestDTO dto = request();
        dto.setAmount(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.amountPositive");
    }

    @Test
    @DisplayName("submitClaim: transitions DRAFT -> SUBMITTED, sets timestamps and user")
    void submitFromDraft() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.submitClaim(UUID.randomUUID());

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.SUBMITTED);
        assertThat(existing.getSubmittedAt()).isNotNull();
        assertThat(existing.getSubmittedByUser()).isEqualTo(user);
    }

    @Test
    @DisplayName("submitClaim: rejects non-DRAFT source status")
    void submitRejectsWrongSourceStatus() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.submitClaim(claimId))
                .isInstanceOf(BusinessException.class);
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("markAccepted: only from SUBMITTED")
    void acceptOnlyFromSubmitted() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        UUID billing = UUID.randomUUID();
        when(roleValidator.getCurrentUserId()).thenReturn(billing);

        service.markAccepted(UUID.randomUUID(), "ok");

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.ACCEPTED);
        assertThat(existing.getNotes()).isEqualTo("ok");
        // Review of #1244: no money desk at the hospital, nothing recorded: the claim covers no fill.
        assertThat(existing.getVouchedBy()).isNull();
    }

    // ───────────── review round 2 of #1244: who stands behind a claim is recorded when it happens ─────────────

    private PharmacyClaim aClaimIn(PharmacyClaimStatus status) {
        PharmacyClaim claim = draftClaim();
        claim.setHospital(hospital);
        claim.setClaimStatus(status);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(claim));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());
        return claim;
    }

    private UUID actingAs(boolean moneyDesk) {
        UUID actor = UUID.randomUUID();
        User who = new User();
        who.setId(actor);
        org.mockito.Mockito.lenient().when(roleValidator.getCurrentUserId()).thenReturn(actor);
        org.mockito.Mockito.lenient().when(userRepository.findById(actor)).thenReturn(Optional.of(who));
        org.mockito.Mockito.lenient().when(roleValidator.vouchesForClaimCover(actor, hospitalId)).thenReturn(moneyDesk);
        org.mockito.Mockito.lenient().when(roleValidator.canRecordPayerDecision(actor, hospitalId))
                .thenReturn(moneyDesk);
        return actor;
    }

    @Test
    @DisplayName("QA D2925 (r66): the pharmacy creates and sends a claim; it never records the payer's answer to it")
    void thePharmacyNeverAnswersItsOwnClaim() {
        PharmacyClaim claim = aClaimIn(PharmacyClaimStatus.DRAFT);
        UUID pharmacist = actingAs(false);

        service.submitClaim(UUID.randomUUID());
        assertThat(claim.getClaimStatus()).isEqualTo(PharmacyClaimStatus.SUBMITTED);
        assertThat(claim.getVouchedBy()).as("the pharmacy stands behind nothing").isNull();

        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.markAccepted(id, null))
                .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> service.markRejected(id, "Assuré inconnu"))
                .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(claim.getClaimStatus()).as("still waiting for a money desk").isEqualTo(PharmacyClaimStatus.SUBMITTED);
        assertThat(claim.getRejectionReason()).isNull();

        // The state D2925 left behind is no longer reachable: billing accepts the claim the pharmacist sent.
        UUID clerk = actingAs(true);
        service.markAccepted(id, null);
        assertThat(claim.getClaimStatus()).isEqualTo(PharmacyClaimStatus.ACCEPTED);
        assertThat(claim.getVouchedBy()).isEqualTo(clerk);
        assertThat(FillClaimCover.counts(claim, pharmacist)).as("the fill may leave").isTrue();
    }

    @Test
    @DisplayName("QA D2925 (r66): a payment the pharmacist tries to record is refused; an accountant records it, and the first voucher stays")
    void aPaymentIsRecordedByAMoneyDeskAndKeepsTheVoucher() {
        PharmacyClaim claim = aClaimIn(PharmacyClaimStatus.SUBMITTED);
        UUID clerk = actingAs(true);
        service.markAccepted(UUID.randomUUID(), null);
        assertThat(claim.getVouchedBy()).isEqualTo(clerk);
        java.time.LocalDateTime vouchedAt = claim.getVouchedAt();
        assertThat(vouchedAt).isNotNull();

        UUID pharmacist = actingAs(false);
        UUID id = UUID.randomUUID();
        assertThatThrownBy(() -> service.markPaid(id, null))
                .isInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        assertThat(claim.getClaimStatus()).isEqualTo(PharmacyClaimStatus.ACCEPTED);

        UUID accountant = actingAs(true);
        service.markPaid(id, null);

        assertThat(claim.getClaimStatus()).isEqualTo(PharmacyClaimStatus.PAID);
        assertThat(claim.getVouchedBy()).as("written once").isEqualTo(clerk);
        assertThat(claim.getVouchedAt()).isEqualTo(vouchedAt);
        assertThat(claim.getSecondVouchedBy()).isEqualTo(accountant);
        assertThat(FillClaimCover.counts(claim, pharmacist)).isTrue();
        assertThat(FillClaimCover.counts(claim, clerk)).as("somebody else stands behind it too").isTrue();
    }

    @Test
    @DisplayName("QA D2925 (r66): a claim left ACCEPTED with nobody behind it is confirmed by a money desk, once")
    void anUnvouchedAcceptedClaimIsConfirmedOnce() {
        // What the pharmacist's own "accept" left before it was refused: ACCEPTED, no voucher, billing locked out.
        PharmacyClaim claim = aClaimIn(PharmacyClaimStatus.ACCEPTED);
        UUID clerk = actingAs(true);
        UUID id = UUID.randomUUID();

        service.markAccepted(id, null);

        assertThat(claim.getClaimStatus()).isEqualTo(PharmacyClaimStatus.ACCEPTED);
        assertThat(claim.getVouchedBy()).isEqualTo(clerk);
        assertThat(claim.getVouchedAt()).isNotNull();
        assertThat(FillClaimCover.counts(claim, UUID.randomUUID())).isTrue();

        // Somebody stands behind it now: it is not accepted a second time, by the same person or another.
        assertThatThrownBy(() -> service.markAccepted(id, null)).isInstanceOf(BusinessException.class)
                .isNotInstanceOf(com.example.hms.exception.ClientSafeAccessDeniedException.class);
        actingAs(true);
        assertThatThrownBy(() -> service.markAccepted(id, null)).isInstanceOf(BusinessException.class);
        assertThat(claim.getVouchedBy()).isEqualTo(clerk);
        assertThat(claim.getSecondVouchedBy()).isNull();
    }

    @Test
    @DisplayName("QA D2925 (r66): the payer's decisions are closed to the pharmacy and the super-admin at the endpoint; the accountant reads and decides")
    void thePayerDecisionEndpointsAreForTheMoneyDesks() throws Exception {
        Class<?> controller = com.example.hms.controller.pharmacy.PharmacyClaimController.class;
        for (String decision : java.util.List.of("accept", "reject", "pay")) {
            String roles = java.util.Arrays.stream(controller.getDeclaredMethods())
                    .filter(m -> m.getName().equals(decision)).findFirst().orElseThrow()
                    .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value();
            assertThat(roles).as(decision).contains("'BILLING_SPECIALIST'", "'CLAIMS_REVIEWER'", "'ACCOUNTANT'",
                    "'HOSPITAL_ADMIN'").doesNotContain("PHARMACIST").doesNotContain("SUPER_ADMIN");
        }
        for (String pharmacyStep : java.util.List.of("create", "submit")) {
            String roles = java.util.Arrays.stream(controller.getDeclaredMethods())
                    .filter(m -> m.getName().equals(pharmacyStep)).findFirst().orElseThrow()
                    .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value();
            assertThat(roles).as(pharmacyStep).contains("'PHARMACIST'");
        }
        String readers = controller.getDeclaredMethod("listByHospital", org.springframework.data.domain.Pageable.class)
                .getAnnotation(org.springframework.security.access.prepost.PreAuthorize.class).value();
        assertThat(readers).contains("'ACCOUNTANT'", "'PHARMACIST'");
    }

    @Test
    @DisplayName("review round 2: billing sends the claim: recorded at once; a rejection records nobody")
    void sendingRecordsAMoneyDeskAndARejectionRecordsNobody() {
        PharmacyClaim claim = aClaimIn(PharmacyClaimStatus.DRAFT);
        UUID clerk = actingAs(true);
        service.submitClaim(UUID.randomUUID());
        assertThat(claim.getVouchedBy()).isEqualTo(clerk);
        assertThat(claim.getVouchedAt()).isEqualTo(claim.getSubmittedAt());

        actingAs(true);
        service.markRejected(UUID.randomUUID(), "Assuré inconnu");

        assertThat(claim.getSecondVouchedBy()).as("refusing a claim is not standing behind it").isNull();
        assertThat(FillClaimCover.counts(claim, UUID.randomUUID())).as("a rejected claim covers nothing").isFalse();
    }

    @Test
    @DisplayName("markRejected: requires a reason")
    void rejectRequiresReason() {
        UUID claimId = UUID.randomUUID();
        assertThatThrownBy(() -> service.markRejected(claimId, " "))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.rejectionReasonRequired");
    }

    @Test
    @DisplayName("markPaid: only from ACCEPTED")
    void payOnlyFromAccepted() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.ACCEPTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markPaid(UUID.randomUUID(), null);

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.PAID);
    }

    @Test
    @DisplayName("markPaid: rejects transition from SUBMITTED (must be ACCEPTED first)")
    void payRejectsFromSubmitted() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.markPaid(claimId, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("loadInScope: rejects cross-hospital reads (tenant isolation)")
    void getClaimRejectsOtherHospital() {
        Hospital other = new Hospital();
        other.setId(UUID.randomUUID());
        PharmacyClaim existing = draftClaim();
        existing.setHospital(other);

        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getClaim(claimId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private PharmacyClaim draftClaim() {
        PharmacyClaim c = PharmacyClaim.builder()
                .dispense(dispense)
                .patient(patient)
                .hospital(hospital)
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .claimStatus(PharmacyClaimStatus.DRAFT)
                .build();
        c.setId(UUID.randomUUID());
        return c;
    }

    @Test
    @DisplayName("createClaim: rejects null DTO")
    void rejectsNullDto() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        assertThatThrownBy(() -> service.createClaim(null))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.requestRequired");
    }

    @Test
    @DisplayName("createClaim: rejects hospital mismatch")
    void rejectsHospitalMismatch() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyClaimRequestDTO dto = request();
        dto.setHospitalId(UUID.randomUUID());

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("hospital");
    }

    @Test
    @DisplayName("createClaim: rejects dispense not found")
    void rejectsDispenseNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.empty());
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects dispense whose patient does not match")
    void rejectsPatientMismatchOnCreate() {
        Patient other = new Patient();
        other.setId(UUID.randomUUID());
        dispense.setPatient(other);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.patientMismatch");
    }

    @Test
    @DisplayName("createClaim: rejects dispense with null patient")
    void rejectsNullPatient() {
        dispense.setPatient(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("createClaim: rejects dispense with null pharmacy")
    void rejectsNullPharmacy() {
        dispense.setPharmacy(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects when patient record is missing")
    void rejectsPatientRepoMissing() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.empty());
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: rejects when hospital record is missing")
    void rejectsHospitalRepoMissing() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.empty());
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("createClaim: defaults claim status to DRAFT when mapper returns null status")
    void defaultsStatusToDraft() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
        when(patientRepository.findById(patientId)).thenReturn(Optional.of(patient));
        when(hospitalRepository.findById(hospitalId)).thenReturn(Optional.of(hospital));
        PharmacyClaim entity = PharmacyClaim.builder()
                .amount(new BigDecimal("5000"))
                .currency("XOF")
                .hospital(hospital)
                .claimStatus(null)
                .build();
        when(claimMapper.toEntity(any(), any(), any(), any())).thenReturn(entity);
        when(claimRepository.save(any())).thenAnswer(inv -> {
            PharmacyClaim c = inv.getArgument(0);
            c.setId(UUID.randomUUID());
            return c;
        });
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.createClaim(request());

        verify(claimRepository).save(org.mockito.ArgumentMatchers.argThat(
                c -> c.getClaimStatus() == PharmacyClaimStatus.DRAFT));
    }

    @Test
    @DisplayName("submitClaim: rejects when current user id is null")
    void submitRejectsWhenCurrentUserIdNull() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(roleValidator.getCurrentUserId()).thenReturn(null);
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.submitClaim(claimId))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.currentUserUnknown");
    }

    @Test
    @DisplayName("submitClaim: rejects when user record is missing")
    void submitRejectsWhenUserMissing() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(roleValidator.getCurrentUserId()).thenReturn(userId);
        when(userRepository.findById(userId)).thenReturn(Optional.empty());
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.submitClaim(claimId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("markRejected: transitions SUBMITTED -> REJECTED with reason")
    void rejectSucceedsWithReason() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markRejected(UUID.randomUUID(), "missing coverage");

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.REJECTED);
        assertThat(existing.getRejectionReason()).isEqualTo("missing coverage");
    }

    @Test
    @DisplayName("markRejected: rejects null reason")
    void rejectRequiresNonNullReason() {
        UUID claimId = UUID.randomUUID();
        assertThatThrownBy(() -> service.markRejected(claimId, null))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.rejectionReasonRequired");
    }

    @Test
    @DisplayName("markAccepted: null notes leaves original notes untouched")
    void acceptWithNullNotesDoesNotUpdateNotes() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        existing.setNotes("original");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markAccepted(UUID.randomUUID(), null);

        assertThat(existing.getClaimStatus()).isEqualTo(PharmacyClaimStatus.ACCEPTED);
        assertThat(existing.getNotes()).isEqualTo("original");
    }

    @Test
    @DisplayName("markAccepted: blank notes leaves original notes untouched")
    void acceptWithBlankNotes() {
        PharmacyClaim existing = draftClaim();
        existing.setClaimStatus(PharmacyClaimStatus.SUBMITTED);
        existing.setNotes("original");
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(claimMapper.toResponseDTO(any())).thenReturn(PharmacyClaimResponseDTO.builder().build());

        service.markAccepted(UUID.randomUUID(), "   ");

        assertThat(existing.getNotes()).isEqualTo("original");
    }

    @Test
    @DisplayName("markAccepted: rejects transition from DRAFT")
    void acceptRejectsFromDraft() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.markAccepted(claimId, null))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("loadInScope: rejects claim with null hospital")
    void loadRejectsNullHospital() {
        PharmacyClaim existing = draftClaim();
        existing.setHospital(null);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getClaim(claimId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("loadInScope: rejects when claim is not found")
    void loadRejectsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.empty());
        UUID claimId = UUID.randomUUID();

        assertThatThrownBy(() -> service.getClaim(claimId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getClaim: happy path returns response DTO")
    void getClaimHappyPath() {
        PharmacyClaim existing = draftClaim();
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findById(any())).thenReturn(Optional.of(existing));
        when(claimMapper.toResponseDTO(existing))
                .thenReturn(PharmacyClaimResponseDTO.builder().build());

        assertThat(service.getClaim(UUID.randomUUID())).isNotNull();
    }

    @Test
    @DisplayName("listByHospital: delegates with active hospital id")
    void listByHospitalDelegates() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByHospitalId(any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByHospital(org.springframework.data.domain.PageRequest.of(0, 10)))
                .isNotNull();
    }

    @Test
    @DisplayName("listByDispense: delegates to repository")
    void listByDispenseDelegates() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByDispenseIdAndHospitalId(any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByDispense(UUID.randomUUID(),
                org.springframework.data.domain.PageRequest.of(0, 10))).isNotNull();
    }

    @Test
    @DisplayName("listByPatient: delegates to repository")
    void listByPatientDelegates() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(claimRepository.findByPatientIdAndHospitalId(any(), any(), any()))
                .thenReturn(org.springframework.data.domain.Page.empty());

        assertThat(service.listByPatient(UUID.randomUUID(),
                org.springframework.data.domain.PageRequest.of(0, 10))).isNotNull();
    }

    // ── QA D1613 (round 44): a claim is bounded by its dispense ─────────────

    private void inScope() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        when(dispenseRepository.findLockedById(dispenseId)).thenReturn(Optional.of(dispense));
    }

    @Test
    @DisplayName("D1613: a claim above the dispense charge is refused on amount, naming what is billable")
    void refusesClaimAboveCharge() {
        inScope();
        PharmacyClaimRequestDTO dto = request();
        dto.setAmount(new BigDecimal("999999"));

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .satisfies(e -> {
                    com.example.hms.exception.FieldBusinessException fe =
                            (com.example.hms.exception.FieldBusinessException) e;
                    assertThat(fe.getField()).isEqualTo("amount");
                    assertThat(fe.getMessageKey()).isEqualTo("pharmacy.claim.amountAboveBillable");
                });
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("D1613: the claim is bounded by the charge less what the patient already paid at the checkout")
    void boundIsChargeLessPaid() {
        inScope();
        when(paymentRepository.sumPostedForDispense(dispenseId)).thenReturn(new BigDecimal("2000"));
        PharmacyClaimRequestDTO dto = request(); // 5 000 > 6 000 - 2 000

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.amountAboveBillable");
    }

    @Test
    @DisplayName("D1613: a dispense paid in full at the checkout has nothing left to claim")
    void paidDispenseHasNothingToClaim() {
        inScope();
        when(paymentRepository.sumPostedForDispense(dispenseId)).thenReturn(new BigDecimal("6000"));
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.nothingBillable");
    }

    @Test
    @DisplayName("D1613: an unpriced dispense has no billable amount, so no claim")
    void unpricedDispenseRefused() {
        dispense.setMedicationCatalogItem(null);
        inScope();
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.noPrice");
    }

    @Test
    @DisplayName("D1613: a cancelled dispense has nothing to claim")
    void cancelledDispenseRefused() {
        dispense.setStatus(com.example.hms.enums.DispenseStatus.CANCELLED);
        inScope();
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.dispenseCancelled");
    }

    @Test
    @DisplayName("D1613: a second live claim for one dispense is refused on dispenseId")
    void secondLiveClaimRefused() {
        inScope();
        when(claimRepository.existsByDispenseIdAndClaimStatusNot(dispenseId, PharmacyClaimStatus.REJECTED))
                .thenReturn(true);
        PharmacyClaimRequestDTO dto = request();

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(com.example.hms.exception.FieldBusinessException.class)
                .satisfies(e -> assertThat(((com.example.hms.exception.FieldBusinessException) e).getField())
                        .isEqualTo("dispenseId"))
                .extracting("messageKey").isEqualTo("pharmacy.claim.liveClaimExists");
        verify(claimRepository, never()).save(any());
    }

    @Test
    @DisplayName("D1613: a claim cannot be created already accepted or paid")
    void createdStatusMustBeDraft() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        PharmacyClaimRequestDTO dto = request();
        dto.setClaimStatus(PharmacyClaimStatus.ACCEPTED);

        assertThatThrownBy(() -> service.createClaim(dto))
                .isInstanceOf(BusinessException.class)
                .extracting("messageKey").isEqualTo("pharmacy.claim.startsAsDraft");
    }
}
