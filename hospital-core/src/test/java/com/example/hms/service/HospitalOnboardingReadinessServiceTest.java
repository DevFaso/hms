package com.example.hms.service;

import com.example.hms.controller.HospitalOnboardingController;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.payload.dto.HospitalOnboardingReadinessDTO;
import com.example.hms.payload.dto.HospitalOnboardingReadinessDTO.Item;
import com.example.hms.payload.dto.HospitalOnboardingReadinessDTO.Step;
import com.example.hms.repository.BedRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.PayerRepository;
import com.example.hms.repository.PriceListItemRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.scheduling.SessionTemplateRepository;
import com.example.hms.service.scheduling.AppointmentSlotPolicy;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * QA r65 D2731: the onboarding checklist says, for one hospital, what is
 * missing before a patient can book, and only its own admins may read it.
 */
@ExtendWith(MockitoExtension.class)
class HospitalOnboardingReadinessServiceTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private SessionTemplateRepository sessionTemplateRepository;
    @Mock private BedRepository bedRepository;
    @Mock private PriceListItemRepository priceListItemRepository;
    @Mock private PayerRepository payerRepository;
    @Mock private com.example.hms.repository.UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private AppointmentSlotPolicy slotPolicy;
    @Mock private com.example.hms.service.clinicalrules.HospitalRuleSignoffService ruleSignoffService;

    @InjectMocks private HospitalOnboardingReadinessService service;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID medicine = UUID.randomUUID();
    private final UUID maternity = UUID.randomUUID();

    @BeforeEach
    void hospital() {
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        lenient().when(hospitalRepository.findClinicalById(hospitalId)).thenReturn(Optional.of(hospital));
    }

    @AfterEach
    void clear() {
        HospitalContextHolder.clear();
    }

    private static Item item(HospitalOnboardingReadinessDTO readiness, Step step) {
        return readiness.items().stream().filter(i -> i.step() == step).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("the r65 set-up (departments and staff, nobody attached, no rota): nothing to book, and it says which steps are missing")
    void freshHospitalIsNotBookable() {
        when(departmentRepository.countByHospitalId(hospitalId)).thenReturn(9L);
        when(staffRepository.countActiveByHospitalId(hospitalId)).thenReturn(46L);
        when(staffRepository.findDepartmentIdsWithActiveProviders(hospitalId)).thenReturn(Set.of());
        when(slotPolicy.departmentsWithOpenSlots(eq(hospitalId), any())).thenReturn(Set.of());

        HospitalOnboardingReadinessDTO readiness = service.readiness(hospitalId);

        assertThat(readiness.patientBookable()).isFalse();
        assertThat(item(readiness, Step.DEPARTMENTS)).isEqualTo(new Item(Step.DEPARTMENTS, true, 9, true));
        assertThat(item(readiness, Step.STAFF)).isEqualTo(new Item(Step.STAFF, true, 46, true));
        assertThat(item(readiness, Step.PROVIDERS_IN_DEPARTMENT).done()).isFalse();
        assertThat(item(readiness, Step.SESSION_TEMPLATES).done()).isFalse();
        assertThat(item(readiness, Step.OPEN_SLOTS).done()).isFalse();
        // What billing and inpatient care need is listed, and never blocks booking.
        assertThat(item(readiness, Step.BEDS).requiredForBooking()).isFalse();
        assertThat(item(readiness, Step.PRICE_LIST).requiredForBooking()).isFalse();
        assertThat(item(readiness, Step.PAYERS).requiredForBooking()).isFalse();
    }

    @Test
    @DisplayName("bookable only when ONE department has both a clinician attached and a slot to come")
    void bookableNeedsTheSameDepartmentOnBothSides() {
        when(staffRepository.findDepartmentIdsWithActiveProviders(hospitalId)).thenReturn(Set.of(medicine));
        // Slots exist, but in a department nobody bookable is attached to.
        when(slotPolicy.departmentsWithOpenSlots(eq(hospitalId), any())).thenReturn(Set.of(maternity));
        when(sessionTemplateRepository.countByHospital_IdAndActiveTrue(hospitalId)).thenReturn(5L);

        HospitalOnboardingReadinessDTO readiness = service.readiness(hospitalId);

        assertThat(readiness.patientBookable()).isFalse();
        assertThat(item(readiness, Step.PROVIDERS_IN_DEPARTMENT).done()).isTrue();
        assertThat(item(readiness, Step.SESSION_TEMPLATES).done()).isTrue();
        assertThat(item(readiness, Step.OPEN_SLOTS)).isEqualTo(new Item(Step.OPEN_SLOTS, false, 0, true));

        when(slotPolicy.departmentsWithOpenSlots(eq(hospitalId), any()))
            .thenReturn(Set.of(maternity, medicine));

        HospitalOnboardingReadinessDTO ready = service.readiness(hospitalId);

        assertThat(ready.patientBookable()).isTrue();
        assertThat(item(ready, Step.OPEN_SLOTS)).isEqualTo(new Item(Step.OPEN_SLOTS, true, 1, true));
    }

    @Test
    @DisplayName("review of #1246: the first step is the hospital's own administrator; without one, a department cannot be created")
    void theFirstStepIsTheHospitalsAdministrator() {
        when(staffRepository.findDepartmentIdsWithActiveProviders(hospitalId)).thenReturn(Set.of());
        when(slotPolicy.departmentsWithOpenSlots(eq(hospitalId), any())).thenReturn(Set.of());

        HospitalOnboardingReadinessDTO fresh = service.readiness(hospitalId);

        assertThat(fresh.items().get(0)).isEqualTo(new Item(Step.HOSPITAL_ADMIN, false, 0, true));
        assertThat(fresh.items().get(1).step()).isEqualTo(Step.DEPARTMENTS);

        // Registered, activated or still waiting for the code: the step is done.
        when(assignmentRepository.countHospitalAdminAssignmentsAt(hospitalId)).thenReturn(1L);
        assertThat(service.readiness(hospitalId).items().get(0)).isEqualTo(new Item(Step.HOSPITAL_ADMIN, true, 1, true));
    }

    @Test
    @DisplayName("every count is asked for the hospital named, and only that one")
    void everyCountIsScopedToTheHospital() {
        when(staffRepository.findDepartmentIdsWithActiveProviders(hospitalId)).thenReturn(Set.of());
        when(slotPolicy.departmentsWithOpenSlots(eq(hospitalId), any())).thenReturn(Set.of());

        service.readiness(hospitalId);

        verify(departmentRepository).countByHospitalId(hospitalId);
        verify(staffRepository).countActiveByHospitalId(hospitalId);
        verify(staffRepository).findDepartmentIdsWithActiveProviders(hospitalId);
        verify(sessionTemplateRepository).countByHospital_IdAndActiveTrue(hospitalId);
        verify(slotPolicy).departmentsWithOpenSlots(eq(hospitalId), any());
        verify(bedRepository).countByWard_Hospital_IdAndActiveTrue(hospitalId);
        verify(priceListItemRepository).countByHospitalIdAndActiveTrue(hospitalId);
        verify(payerRepository).countByHospitalIdAndActiveTrue(hospitalId);
    }

    @Test
    @DisplayName("a pharmacy or laboratory row, or an unknown id, has no checklist: 404 before any count")
    void providerFacilityIsNotFound() {
        UUID pharmacy = UUID.randomUUID();
        when(hospitalRepository.findClinicalById(pharmacy)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.readiness(pharmacy)).isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(departmentRepository, staffRepository, sessionTemplateRepository, bedRepository,
            priceListItemRepository, payerRepository, slotPolicy);
    }

    // ── who may read it ─────────────────────────────────────────────────────

    @Test
    @DisplayName("the endpoint admits the hospital admin and the super-admin, nobody else")
    void endpointRoles() throws NoSuchMethodException {
        PreAuthorize gate = HospitalOnboardingController.class.getMethod("get", UUID.class)
            .getAnnotation(PreAuthorize.class);
        assertThat(gate.value()).isEqualTo("hasAnyAuthority('ROLE_HOSPITAL_ADMIN','ROLE_SUPER_ADMIN')");
    }

    @Test
    @DisplayName("V356: the checklist says how many rule sets the approver signed of the registry's, done only at all")
    void clinicalRulesStepCountsTheSignedRuleSets() {
        long total = com.example.hms.service.clinicalrules.ClinicalRuleRegistry.all().size();
        when(ruleSignoffService.signedCount(hospitalId)).thenReturn(3L);

        Item rules = item(service.readiness(hospitalId), Step.CLINICAL_RULES);

        assertThat(rules).isEqualTo(new Item(Step.CLINICAL_RULES, false, 3, false, total));

        when(ruleSignoffService.signedCount(hospitalId)).thenReturn(total);
        assertThat(item(service.readiness(hospitalId), Step.CLINICAL_RULES).done()).isTrue();
    }

    @Test
    @DisplayName("a hospital admin reads the hospital they act at, and is refused another hospital's before anything is read")
    void hospitalAdminIsPinnedToTheirHospital() {
        HospitalOnboardingReadinessService readinessService = mock(HospitalOnboardingReadinessService.class);
        HospitalOnboardingController controller = new HospitalOnboardingController(readinessService);
        HospitalContextHolder.setContext(HospitalContext.builder().activeHospitalId(hospitalId).superAdmin(false).build());

        controller.get(hospitalId);
        verify(readinessService).readiness(hospitalId);

        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> controller.get(other)).isInstanceOf(AccessDeniedException.class);
        verify(readinessService, never()).readiness(other);
    }

    @Test
    @DisplayName("a super-admin (global scope) reads any hospital's checklist")
    void superAdminReadsAny() {
        HospitalOnboardingReadinessService readinessService = mock(HospitalOnboardingReadinessService.class);
        HospitalOnboardingController controller = new HospitalOnboardingController(readinessService);
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(UUID.randomUUID()).superAdmin(true).build());

        controller.get(hospitalId);

        verify(readinessService).readiness(hospitalId);
    }
}
