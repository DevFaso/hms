package com.example.hms.service.formulary;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.MedicationCatalogChangeType;
import com.example.hms.enums.MedicationCatalogDecisionBasis;
import com.example.hms.enums.MedicationCatalogVersionStatus;
import com.example.hms.enums.PediatricDoseLimitStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.medication.DoseLimitSnapshot;
import com.example.hms.model.medication.FormularySnapshot;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.model.medication.MedicationCatalogVersion;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.MedicationCatalogVersionRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.context.ImpersonationContext;
import com.example.hms.security.context.ImpersonationContextHolder;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Go-live D2 (V385): the approval circuit of the medication catalogue. A proposal changes nothing that
 * prescribing or dispensing read; an approval by a different person with the right role AT the hospital does; a
 * refusal carries its reason; every step leaves its row. The real policy runs, on a stubbed roles table.
 */
@DisplayName("Medication catalogue versions: proposed, approved by a second person, then in force (V385)")
class MedicationCatalogVersionServiceTest {

    private final MedicationCatalogVersionRepository versions = mock(MedicationCatalogVersionRepository.class);
    private final MedicationCatalogItemRepository catalogue = mock(MedicationCatalogItemRepository.class);
    private final HospitalRepository hospitals = mock(HospitalRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final RoleValidator roleValidator = mock(RoleValidator.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final MedicationCatalogApprovalPolicy policy =
        new MedicationCatalogApprovalPolicy(assignments, hospitals, roleValidator);
    private final MedicationCatalogVersionService service = new MedicationCatalogVersionService(
        versions, catalogue, hospitals, users, roleValidator, policy, audit);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();
    private final UUID admin = UUID.randomUUID();
    private final UUID pharmacist = UUID.randomUUID();
    private final UUID otherPharmacist = UUID.randomUUID();
    private final UUID chiefPhysician = UUID.randomUUID();
    private final UUID doctor = UUID.randomUUID();

    /** The version table, in memory: what was saved is what a later step finds. */
    private final List<MedicationCatalogVersion> stored = new ArrayList<>();
    private Hospital hospital;
    private MedicationCatalogItem item;

    @BeforeEach
    void setUp() {
        hospital = new Hospital();
        ReflectionTestUtils.setField(hospital, "id", hospitalId);
        hospital.setCriticalMedicalLeadUserId(chiefPhysician);
        item = MedicationCatalogItem.builder().nameFr("Paracétamol").genericName("Paracetamol")
            .form("TABLET").strength("500").strengthUnit("mg").route("ORAL").hospital(hospital).build();
        ReflectionTestUtils.setField(item, "id", itemId);

        when(hospitals.findById(hospitalId)).thenReturn(Optional.of(hospital));
        when(hospitals.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
        when(catalogue.findById(itemId)).thenReturn(Optional.of(item));
        when(catalogue.save(any(MedicationCatalogItem.class))).thenAnswer(call -> {
            MedicationCatalogItem saved = call.getArgument(0);
            if (saved.getId() == null) {
                ReflectionTestUtils.setField(saved, "id", UUID.randomUUID());
            }
            return saved;
        });
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);

        when(versions.save(any(MedicationCatalogVersion.class))).thenAnswer(call -> keep(call.getArgument(0)));
        when(versions.saveAndFlush(any(MedicationCatalogVersion.class))).thenAnswer(call -> keep(call.getArgument(0)));
        when(versions.findForDecision(any(), any())).thenAnswer(call -> stored.stream()
            .filter(v -> v.getId().equals(call.getArgument(0)))
            .filter(v -> java.util.Objects.equals(v.getHospitalId(), call.getArgument(1)))
            .findFirst());
        when(versions.findByCatalogItemIdAndKindAndStatus(any(), any(), any())).thenAnswer(call -> stored.stream()
            .filter(v -> java.util.Objects.equals(v.getCatalogItemId(), call.getArgument(0)))
            .filter(v -> v.getKind() == call.getArgument(1) && v.getStatus() == call.getArgument(2))
            .toList());
        when(versions.highestVersionNumber(any(), any())).thenAnswer(call -> stored.stream()
            .filter(v -> java.util.Objects.equals(v.getCatalogItemId(), call.getArgument(0)))
            .filter(v -> v.getKind() == call.getArgument(1))
            .mapToInt(MedicationCatalogVersion::getVersionNumber).max().orElse(0));

        when(assignments.countActiveUsersWithRoleCodeAtHospital(hospitalId, "ROLE_PHARMACIST")).thenReturn(2L);
        holdsAt(pharmacist, "ROLE_PHARMACIST");
        holdsAt(otherPharmacist, "ROLE_PHARMACIST");
        holdsAt(chiefPhysician, "ROLE_DOCTOR");
        holdsAt(doctor, "ROLE_DOCTOR");
    }

    @AfterEach
    void clearImpersonation() {
        ImpersonationContextHolder.clear();
    }

    private MedicationCatalogVersion keep(MedicationCatalogVersion version) {
        if (version.getId() == null) {
            ReflectionTestUtils.setField(version, "id", UUID.randomUUID());
            stored.add(version);
        }
        return version;
    }

    /** {@code user} holds {@code role} at THE hospital, and nowhere else. */
    private void holdsAt(UUID user, String role) {
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(eq(user), eq(hospitalId), anySet()))
            .thenAnswer(call -> {
                Set<String> wanted = call.getArgument(2);
                return wanted.contains(role);
            });
    }

    private void actingAs(UUID user) {
        when(roleValidator.getCurrentUserId()).thenReturn(user);
    }

    private FormularySnapshot liveWith(String strength, boolean controlled) {
        FormularySnapshot live = FormularySnapshot.of(item);
        return new FormularySnapshot(live.nameFr(), live.genericName(), live.brandName(), live.atcCode(), live.form(),
            strength, live.strengthUnit(), live.rxnormCode(), live.tallManName(), live.route(), controlled,
            live.active(), live.category(), live.essentialList(), live.description(), live.sensitivityCategory());
    }

    private static DoseLimitSnapshot paracetamolLimits() {
        return new DoseLimitSnapshot(new BigDecimal("15"), new BigDecimal("60"), new BigDecimal("1000"),
            new BigDecimal("4000"), null, List.of(), "WHO Pocket Book 2013");
    }

    private MedicationCatalogVersion proposedStrengthChange() {
        actingAs(admin);
        return service.proposeFormulary(item, liveWith("1000", true), "Nouveau dosage référencé");
    }

    // ── a formulary entry ──

    @Test
    void aProposedChangeLeavesTheEntryAsItIs_untilAnotherPersonApprovesIt() {
        MedicationCatalogVersion version = proposedStrengthChange();

        assertThat(version.getStatus()).isEqualTo(MedicationCatalogVersionStatus.PENDING_APPROVAL);
        assertThat(version.getChangeType()).isEqualTo(MedicationCatalogChangeType.UPDATE);
        assertThat(version.getVersionNumber()).isEqualTo(1);
        assertThat(version.getHospitalId()).isEqualTo(hospitalId);
        assertThat(version.getProposedBy()).isEqualTo(admin);
        assertThat(version.getChangeReason()).isEqualTo("Nouveau dosage référencé");
        assertThat(item.getStrength()).isEqualTo("500");
        assertThat(item.isControlled()).isFalse();
        assertThat(item.getFormularyApprovedVersion()).isNull();
        verify(catalogue, never()).save(any());

        actingAs(pharmacist);
        MedicationCatalogVersionResponseDTO approved = service.approve(version.getId(), null);

        assertThat(approved.getStatus()).isEqualTo(MedicationCatalogVersionStatus.APPROVED);
        assertThat(approved.getDecidedById()).isEqualTo(pharmacist);
        assertThat(approved.getChangedFields()).containsExactlyInAnyOrder("strength", "controlled");
        assertThat(approved.getPreviousFormulary().strength()).isEqualTo("500");
        assertThat(item.getStrength()).isEqualTo("1000");
        assertThat(item.isControlled()).isTrue();
        assertThat(item.getFormularyApprovedVersion()).isEqualTo(1);
    }

    @Test
    void nobodyApprovesTheirOwnProposal_whateverTheirRole() {
        actingAs(pharmacist);
        MedicationCatalogVersion version = service.proposeDoseLimits(item, paracetamolLimits(), "Limites OMS");

        assertThatThrownBy(() -> service.approve(version.getId(), null))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.fourEyes");
        assertThat(item.getPediatricMaxDoseMgPerKg()).isNull();
        assertThat(version.getStatus()).isEqualTo(MedicationCatalogVersionStatus.PENDING_APPROVAL);
    }

    @Test
    void aFormularyEntryIsApprovedByAPharmacistOfTheHospital_notByADoctorNorItsChiefPhysicianNorAnAdmin() {
        MedicationCatalogVersion version = proposedStrengthChange();

        for (UUID notAPharmacist : List.of(doctor, chiefPhysician, UUID.randomUUID())) {
            actingAs(notAPharmacist);
            assertThatThrownBy(() -> service.approve(version.getId(), null))
                .isInstanceOf(AccessDeniedException.class);
        }
        assertThat(item.getStrength()).isEqualTo("500");
        verify(catalogue, never()).save(any());
    }

    @Test
    void aPlatformAdministratorWhoIsNoPharmacistHere_approvesNothingOfAHospital() {
        MedicationCatalogVersion version = proposedStrengthChange();
        UUID superAdmin = UUID.randomUUID();
        actingAs(superAdmin);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);

        assertThatThrownBy(() -> service.approve(version.getId(), null)).isInstanceOf(AccessDeniedException.class);
        assertThat(item.getStrength()).isEqualTo("500");
    }

    @Test
    void aSupportSessionActingAsThePharmacist_decidesNothing() {
        MedicationCatalogVersion version = proposedStrengthChange();
        actingAs(pharmacist);
        ImpersonationContextHolder.set(ImpersonationContext.of(UUID.randomUUID(), "support"));

        assertThatThrownBy(() -> service.approve(version.getId(), null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> service.reject(version.getId(), "non")).isInstanceOf(AccessDeniedException.class);
        assertThat(item.getStrength()).isEqualTo("500");
    }

    @Test
    void aVersionOfAnotherHospitalIsNotFound() {
        MedicationCatalogVersion version = proposedStrengthChange();
        actingAs(pharmacist);
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.approve(version.getId(), null))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.reject(version.getId(), "non"))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThat(item.getStrength()).isEqualTo("500");
    }

    @Test
    void aNewEntryIsInTheCatalogueOnlyOnceApproved() {
        actingAs(admin);
        FormularySnapshot proposed = new FormularySnapshot("Amoxicilline", "Amoxicillin", null, "J01CA04", "CAPSULE",
            "500", "mg", null, null, "ORAL", false, true, "Antibiotique", true, "LNME", null);
        MedicationCatalogVersion version = service.proposeNewEntry(hospital, proposed, "Ajout au formulaire");

        assertThat(version.getCatalogItemId()).isNull();
        assertThat(version.getChangeType()).isEqualTo(MedicationCatalogChangeType.CREATE);
        assertThat(version.getItemLabel()).isEqualTo("Amoxicilline 500 mg (CAPSULE)");
        verify(catalogue, never()).save(any());

        actingAs(pharmacist);
        MedicationCatalogVersionResponseDTO approved = service.approve(version.getId(), "Conforme à la LNME");

        ArgumentCaptor<MedicationCatalogItem> created = ArgumentCaptor.forClass(MedicationCatalogItem.class);
        verify(catalogue).save(created.capture());
        MedicationCatalogItem entry = created.getValue();
        assertThat(entry.getNameFr()).isEqualTo("Amoxicilline");
        assertThat(entry.getAtcCode()).isEqualTo("J01CA04");
        assertThat(entry.getCategory()).isEqualTo("Antibiotique");
        assertThat(entry.isEssentialList()).isTrue();
        assertThat(entry.isActive()).isTrue();
        assertThat(entry.getHospital()).isSameAs(hospital);
        assertThat(entry.getFormularyApprovedVersion()).isEqualTo(1);
        assertThat(approved.getCatalogItemId()).isEqualTo(entry.getId());
        assertThat(approved.getDecisionReason()).isEqualTo("Conforme à la LNME");
    }

    @Test
    void aRefusalNeedsItsReason_andChangesNothing() {
        MedicationCatalogVersion version = proposedStrengthChange();
        actingAs(pharmacist);

        assertThatThrownBy(() -> service.reject(version.getId(), "  "))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(version.getStatus()).isEqualTo(MedicationCatalogVersionStatus.PENDING_APPROVAL);

        MedicationCatalogVersionResponseDTO refused = service.reject(version.getId(), "Dosage non référencé à la CAMEG");

        assertThat(refused.getStatus()).isEqualTo(MedicationCatalogVersionStatus.REJECTED);
        assertThat(refused.getDecisionReason()).isEqualTo("Dosage non référencé à la CAMEG");
        assertThat(refused.getDecidedById()).isEqualTo(pharmacist);
        assertThat(item.getStrength()).isEqualTo("500");
        verify(catalogue, never()).save(any());
        // Decided once: it is neither approved nor refused again.
        assertThatThrownBy(() -> service.approve(version.getId(), null))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.alreadyDecided");
    }

    @Test
    void onlyTheAuthorWithdrawsAWaitingVersion() {
        MedicationCatalogVersion version = proposedStrengthChange();

        actingAs(pharmacist);
        assertThatThrownBy(() -> service.withdraw(version.getId()))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.withdrawOwnOnly");

        actingAs(admin);
        assertThat(service.withdraw(version.getId()).getStatus()).isEqualTo(MedicationCatalogVersionStatus.WITHDRAWN);
        assertThat(item.getStrength()).isEqualTo("500");
    }

    @Test
    void oneVersionInProgressAtATime_andAProposalNeedsItsReason() {
        actingAs(admin);
        FormularySnapshot change = liveWith("1000", false);
        assertThatThrownBy(() -> service.proposeFormulary(item, change, " "))
            .isInstanceOf(FieldBusinessException.class);
        assertThat(stored).isEmpty();

        service.proposeFormulary(item, change, "Nouveau dosage");
        FormularySnapshot another = liveWith("250", false);
        assertThatThrownBy(() -> service.proposeFormulary(item, another, "Autre dosage"))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.pendingExists");
        // The dose limits are another circuit: a waiting formulary version does not hold them.
        assertThat(service.proposeDoseLimits(item, paracetamolLimits(), "Limites OMS").getStatus())
            .isEqualTo(MedicationCatalogVersionStatus.PENDING_APPROVAL);
    }

    @Test
    void anEntryNobodyApprovedIsSubmittedAsItStands_once() {
        actingAs(admin);
        MedicationCatalogVersion version = service.proposeFormulary(item, FormularySnapshot.of(item), "Revue initiale");
        assertThat(version.getChangeType()).isEqualTo(MedicationCatalogChangeType.RATIFY);

        actingAs(pharmacist);
        service.approve(version.getId(), null);
        assertThat(item.getFormularyApprovedVersion()).isEqualTo(1);
        assertThat(item.getStrength()).isEqualTo("500");

        // Approved as it stands: submitting the same values again is nothing to approve.
        actingAs(admin);
        FormularySnapshot same = FormularySnapshot.of(item);
        assertThatThrownBy(() -> service.proposeFormulary(item, same, "Encore"))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.unchanged");
    }

    @Test
    void aSecondApprovedVersionSupersedesTheFirst_andTheHistoryKeepsBoth() {
        MedicationCatalogVersion first = proposedStrengthChange();
        actingAs(pharmacist);
        service.approve(first.getId(), null);

        actingAs(admin);
        MedicationCatalogVersion second = service.proposeFormulary(item, liveWith("250", true), "Retour au 250 mg");
        assertThat(second.getVersionNumber()).isEqualTo(2);
        actingAs(otherPharmacist);
        service.approve(second.getId(), null);

        assertThat(first.getStatus()).isEqualTo(MedicationCatalogVersionStatus.SUPERSEDED);
        assertThat(second.getStatus()).isEqualTo(MedicationCatalogVersionStatus.APPROVED);
        assertThat(item.getStrength()).isEqualTo("250");
        assertThat(item.getFormularyApprovedVersion()).isEqualTo(2);
        // The first is flushed out of "in force" before the second lands (the partial unique index).
        verify(versions).saveAndFlush(first);
    }

    @Test
    void anEntryWithdrawnWhileAVersionWaited_staysWithdrawnWhenThatVersionIsApproved() {
        // Proposed while the medicine was on the list; recalled before the approval.
        MedicationCatalogVersion version = proposedStrengthChange();
        item.deactivate();

        actingAs(pharmacist);
        service.approve(version.getId(), null);

        assertThat(item.getStrength()).isEqualTo("1000");
        assertThat(item.isActive()).as("an approval of a new strength never puts a recalled medicine back").isFalse();
    }

    @Test
    void puttingAWithdrawnEntryBackIsAVersion_inForceOnlyOnceApproved() {
        item.deactivate();
        actingAs(admin);
        FormularySnapshot back = FormularySnapshot.of(item).withActive(true);
        MedicationCatalogVersion version = service.proposeFormulary(item, back, "Rappel de lot levé");

        assertThat(version.getChangeType()).isEqualTo(MedicationCatalogChangeType.UPDATE);
        assertThat(item.isActive()).isFalse();

        actingAs(pharmacist);
        assertThat(service.approve(version.getId(), null).getChangedFields()).containsExactly("active");
        assertThat(item.isActive()).isTrue();
    }

    // ── the hospital with no pharmacist ──

    /** No active user holds the pharmacist role at the hospital. */
    private void theHospitalHasNoPharmacist() {
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(eq(pharmacist), eq(hospitalId), anySet()))
            .thenReturn(false);
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(eq(otherPharmacist), eq(hospitalId), anySet()))
            .thenReturn(false);
        when(assignments.countActiveUsersWithRoleCodeAtHospital(hospitalId, "ROLE_PHARMACIST")).thenReturn(0L);
    }

    @Test
    void whereTheHospitalHasNoPharmacist_itsChiefPhysicianApprovesAFormularyVersion_andTheHistorySaysSo() {
        MedicationCatalogVersion version = proposedStrengthChange();
        theHospitalHasNoPharmacist();

        // Another doctor is still nobody's approver, fallback or not.
        actingAs(doctor);
        assertThatThrownBy(() -> service.approve(version.getId(), null)).isInstanceOf(AccessDeniedException.class);

        actingAs(chiefPhysician);
        assertThat(service.toDto(version).isCallerMayApprove()).isTrue();
        MedicationCatalogVersionResponseDTO approved = service.approve(version.getId(), null);

        assertThat(approved.getStatus()).isEqualTo(MedicationCatalogVersionStatus.APPROVED);
        assertThat(approved.getDecisionBasis())
            .isEqualTo(MedicationCatalogDecisionBasis.MEDICAL_LEAD_NO_PHARMACIST);
        assertThat(version.getDecisionBasis()).isEqualTo(MedicationCatalogDecisionBasis.MEDICAL_LEAD_NO_PHARMACIST);
        assertThat(item.getStrength()).isEqualTo("1000");
    }

    @Test
    void theFallbackNeverLetsTheChiefPhysicianApproveTheirOwnProposal() {
        theHospitalHasNoPharmacist();
        actingAs(chiefPhysician);
        MedicationCatalogVersion version = service.proposeFormulary(item, liveWith("1000", false), "Nouveau dosage");

        assertThatThrownBy(() -> service.approve(version.getId(), null))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.fourEyes");
        assertThat(item.getStrength()).isEqualTo("500");
        assertThat(service.toDto(version).isCallerMayApprove()).isFalse();
    }

    @Test
    void asSoonAsTheHospitalHasAPharmacist_theFormularyIsThePharmacistsAlone() {
        MedicationCatalogVersion version = proposedStrengthChange();
        when(assignments.countActiveUsersWithRoleCodeAtHospital(hospitalId, "ROLE_PHARMACIST")).thenReturn(1L);

        actingAs(chiefPhysician);
        assertThatThrownBy(() -> service.approve(version.getId(), null)).isInstanceOf(AccessDeniedException.class);
        assertThat(item.getStrength()).isEqualTo("500");

        actingAs(pharmacist);
        assertThat(service.approve(version.getId(), null).getDecisionBasis())
            .isEqualTo(MedicationCatalogDecisionBasis.PHARMACIST);
    }

    @Test
    void theHistoryRecordsOnWhatAuthorityAVersionWasDecided() {
        actingAs(pharmacist);
        MedicationCatalogVersion limits = service.proposeDoseLimits(item, paracetamolLimits(), "Limites OMS");
        actingAs(chiefPhysician);
        assertThat(service.approve(limits.getId(), null).getDecisionBasis())
            .isEqualTo(MedicationCatalogDecisionBasis.MEDICAL_LEAD);

        MedicationCatalogVersion refused = proposedStrengthChange();
        actingAs(pharmacist);
        assertThat(service.reject(refused.getId(), "Non référencé").getDecisionBasis())
            .isEqualTo(MedicationCatalogDecisionBasis.PHARMACIST);

        // A version its author took back was decided on nobody's authority.
        MedicationCatalogVersion withdrawn = proposedStrengthChange();
        actingAs(admin);
        assertThat(service.withdraw(withdrawn.getId()).getDecisionBasis()).isNull();
    }

    // ── dose limits ──

    @Test
    void proposedDoseLimitsAreNotInForce_untilAnotherPharmacistApprovesThem() {
        actingAs(pharmacist);
        MedicationCatalogVersion version = service.proposeDoseLimits(item, paracetamolLimits(), "Limites OMS");

        assertThat(item.getPediatricMaxDoseMgPerKg()).isNull();
        assertThat(item.getPediatricDoseStatus()).isNull();
        verify(catalogue, never()).save(any());

        actingAs(otherPharmacist);
        service.approve(version.getId(), null);

        assertThat(item.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("15");
        assertThat(item.getPediatricMaxDailyMg()).isEqualByComparingTo("4000");
        assertThat(item.getPediatricDoseSource()).isEqualTo("WHO Pocket Book 2013");
        assertThat(item.getPediatricDoseStatus()).isEqualTo(PediatricDoseLimitStatus.APPROVED);
        assertThat(item.getPediatricDoseReviewedBy()).isEqualTo(otherPharmacist);
        assertThat(item.getPediatricDoseReviewedAt()).isNotNull();
        assertThat(item.getDoseLimitApprovedVersion()).isEqualTo(1);
        // The formulary entry itself is still nobody's approved version.
        assertThat(item.getFormularyApprovedVersion()).isNull();
    }

    @Test
    void theChiefPhysicianApprovesDoseLimits_anotherDoctorDoesNot() {
        actingAs(pharmacist);
        MedicationCatalogVersion version = service.proposeDoseLimits(item, paracetamolLimits(), "Limites OMS");

        actingAs(doctor);
        assertThatThrownBy(() -> service.approve(version.getId(), null)).isInstanceOf(AccessDeniedException.class);
        assertThat(item.getPediatricMaxDoseMgPerKg()).isNull();

        actingAs(chiefPhysician);
        assertThat(service.approve(version.getId(), null).getStatus())
            .isEqualTo(MedicationCatalogVersionStatus.APPROVED);
        assertThat(item.getPediatricMaxDoseMgPerKg()).isEqualByComparingTo("15");
    }

    @Test
    void limitsSetBeforeTheCircuitAreSubmittedAsTheyStand_andLimitsAlreadyApprovedAreNot() {
        item.setPediatricMaxDoseMgPerKg(new BigDecimal("15.000"));
        item.setPediatricDoseStatus(PediatricDoseLimitStatus.APPROVED);
        actingAs(pharmacist);
        DoseLimitSnapshot same = new DoseLimitSnapshot(new BigDecimal("15"), null, null, null, null, List.of(), null);

        MedicationCatalogVersion version = service.proposeDoseLimits(item, same, "Revue initiale");
        assertThat(version.getChangeType()).isEqualTo(MedicationCatalogChangeType.RATIFY);
        actingAs(otherPharmacist);
        service.approve(version.getId(), null);

        actingAs(pharmacist);
        assertThatThrownBy(() -> service.proposeDoseLimits(item, same, "Encore"))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.unchanged");
    }

    @Test
    void noLimitInForceAndNoneProposed_isNothingToApprove() {
        actingAs(pharmacist);
        DoseLimitSnapshot empty = new DoseLimitSnapshot(null, null, null, null, null, List.of(), null);

        assertThatThrownBy(() -> service.proposeDoseLimits(item, empty, "Rien"))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.unchanged");
    }

    // ── a platform entry ──

    @Test
    void aPlatformEntryIsApprovedByAnotherPlatformAdministrator_inGlobalView() {
        UUID firstSuperAdmin = UUID.randomUUID();
        UUID secondSuperAdmin = UUID.randomUUID();
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        FormularySnapshot proposed = new FormularySnapshot("Artésunate", "Artesunate", null, "P01BE03", "INJECTION",
            "60", "mg", null, null, "IV", false, true, null, true, null, null);

        actingAs(firstSuperAdmin);
        MedicationCatalogVersion version = service.proposeNewEntry(null, proposed, "LNME 2024");
        assertThat(version.getHospitalId()).isNull();
        assertThatThrownBy(() -> service.approve(version.getId(), null))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("medication.catalog.version.fourEyes");

        // A hospital's pharmacist does not see it at their hospital, let alone approve it.
        actingAs(pharmacist);
        when(roleValidator.requireActiveHospitalId()).thenReturn(hospitalId);
        assertThatThrownBy(() -> service.approve(version.getId(), null))
            .isInstanceOf(ResourceNotFoundException.class);

        actingAs(secondSuperAdmin);
        when(roleValidator.requireActiveHospitalId()).thenReturn(null);
        service.approve(version.getId(), null);
        ArgumentCaptor<MedicationCatalogItem> created = ArgumentCaptor.forClass(MedicationCatalogItem.class);
        verify(catalogue).save(created.capture());
        assertThat(created.getValue().getHospital()).isNull();
    }

    // ── the trail ──

    @Test
    void everyStepIsAudited_atTheHospital_withTheVersionAsItsResource() {
        MedicationCatalogVersion version = proposedStrengthChange();
        actingAs(pharmacist);
        service.approve(version.getId(), null);

        ArgumentCaptor<AuditEventRequestDTO> rows = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, atLeastOnce()).logEvent(rows.capture());
        assertThat(rows.getAllValues()).hasSize(2).allSatisfy(row -> {
            assertThat(row.getHospitalId()).isEqualTo(hospitalId);
            assertThat(row.getEventType()).isEqualTo(AuditEventType.CONFIGURATION_CHANGED);
            assertThat(row.getEntityType()).isEqualTo("MEDICATION_CATALOG_VERSION");
        });
        assertThat(rows.getAllValues().get(0).getUserId()).isEqualTo(admin);
        assertThat(rows.getAllValues().get(0).getEventDescription()).contains("PROPOSED");
        assertThat(rows.getAllValues().get(1).getUserId()).isEqualTo(pharmacist);
        assertThat(rows.getAllValues().get(1).getEventDescription()).contains("APPROVED");
    }

    @Test
    void theQueueTellsTheCallerWhatTheyMayDo_asTheServerWillDecideIt() {
        MedicationCatalogVersion version = proposedStrengthChange();

        actingAs(admin);
        MedicationCatalogVersionResponseDTO forTheAuthor = service.toDto(version);
        assertThat(forTheAuthor.isOwnProposal()).isTrue();
        assertThat(forTheAuthor.isCallerMayApprove()).isFalse();

        actingAs(pharmacist);
        MedicationCatalogVersionResponseDTO forThePharmacist = service.toDto(version);
        assertThat(forThePharmacist.isOwnProposal()).isFalse();
        assertThat(forThePharmacist.isCallerMayApprove()).isTrue();
        assertThat(forThePharmacist.isCallerMayReject()).isTrue();

        actingAs(chiefPhysician);
        assertThat(service.toDto(version).isCallerMayApprove()).isFalse();
    }

    @Test
    void theHistoryOfAnotherHospitalsEntryIsNotFound() {
        when(roleValidator.requireActiveHospitalId()).thenReturn(UUID.randomUUID());

        assertThatThrownBy(() -> service.history(itemId)).isInstanceOf(ResourceNotFoundException.class);
        verify(versions, never()).findHistoryInScope(any(), any());
    }
}
