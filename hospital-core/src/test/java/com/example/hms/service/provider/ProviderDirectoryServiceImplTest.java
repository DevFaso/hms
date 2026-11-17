package com.example.hms.service.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.payload.dto.provider.ProviderDirectoryPageDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The provider directory (provider plan §6.5, AC-14): who may read it (a
 * directory role held live at the HOSPITAL the request acts at) and what it
 * offers.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ProviderDirectoryServiceImplTest {

    @Mock private HospitalRepository hospitalRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private ProviderVerificationRepository verificationRepository;
    @Mock private ProviderOrganisationsFlag organisationsFlag;

    private ProviderDirectoryServiceImpl service;

    private final UUID callerId = UUID.randomUUID();
    private final Hospital hospital = facility(FacilityType.HOSPITAL);
    private final Hospital pharmacy = facility(FacilityType.PHARMACY);

    @BeforeEach
    void setUp() {
        when(organisationsFlag.isEnabled()).thenReturn(true);
        service = new ProviderDirectoryServiceImpl(organisationsFlag, ActingScopeTestSupport.resolver(), hospitalRepository,
            assignmentRepository, verificationRepository);
        when(hospitalRepository.findClinicalById(hospital.getId())).thenReturn(Optional.of(hospital));
        when(hospitalRepository.findClinicalById(pharmacy.getId())).thenReturn(Optional.empty());
        when(verificationRepository.findDirectory(anyCollection(), any(), any(Pageable.class)))
            .thenReturn(List.of(verified(pharmacy, "LIC-1")));
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a doctor acting at their hospital reads the directory; the entry carries the licence number")
    void doctorReads() {
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_DOCTOR", hospital));

        ProviderDirectoryPageDTO page = service.search(null, null);

        assertThat(page.isHasMore()).isFalse();
        assertThat(page.getEntries()).singleElement().satisfies(entry -> {
            assertThat(entry.getId()).isEqualTo(pharmacy.getId());
            assertThat(entry.getLicenceNumber()).isEqualTo("LIC-1");
            assertThat(entry.getFacilityType()).isEqualTo(FacilityType.PHARMACY);
        });
    }

    @Test
    @DisplayName("AC-14 flag off: empty for every caller, whatever they send; nothing is read, nobody is refused")
    void flagOffIsEmpty() {
        when(organisationsFlag.isEnabled()).thenReturn(false);
        ActingScopeTestSupport.globalSuperAdmin(callerId);

        for (String type : Arrays.asList(null, "PHARMACY", "HOSPITAL", "not-a-type")) {
            ProviderDirectoryPageDTO page = service.search(type, "x");
            assertThat(page.getEntries()).as(String.valueOf(type)).isEmpty();
            assertThat(page.isHasMore()).isFalse();
        }
        verifyNoInteractions(verificationRepository, hospitalRepository, assignmentRepository);
    }

    @Test
    @DisplayName("more than the cap matched: the first 50, and hasMore")
    void capSaysHasMore() {
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_DOCTOR", hospital));
        List<ProviderVerification> many = new ArrayList<>();
        for (int i = 0; i <= ProviderDirectoryService.MAX_RESULTS; i++) {
            many.add(verified(facility(FacilityType.PHARMACY), "LIC-" + i));
        }
        when(verificationRepository.findDirectory(anyCollection(), any(), any(Pageable.class))).thenReturn(many);

        ProviderDirectoryPageDTO page = service.search(null, null);

        assertThat(page.getEntries()).hasSize(ProviderDirectoryService.MAX_RESULTS);
        assertThat(page.isHasMore()).isTrue();
        ArgumentCaptor<Pageable> asked = ArgumentCaptor.forClass(Pageable.class);
        verify(verificationRepository).findDirectory(anyCollection(), any(), asked.capture());
        assertThat(asked.getValue().getPageSize()).isEqualTo(ProviderDirectoryService.MAX_RESULTS + 1);
    }

    @Test
    @DisplayName("a surgeon is a doctor here, as the annotation sees them")
    void surgeonReads() {
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_SURGEON", hospital));

        assertThat(service.search("PHARMACY", "x").getEntries()).hasSize(1);
    }

    @Test
    @DisplayName("the role must be held at the ACTING hospital, live")
    void roleElsewhereIsRefused() {
        Hospital other = facility(FacilityType.HOSPITAL);
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_DOCTOR", other), row("ROLE_RECEPTIONIST", hospital));

        assertThatThrownBy(() -> service.search(null, null)).isInstanceOf(AccessDeniedException.class);
        verify(verificationRepository, never()).findDirectory(anyCollection(), any(), any(Pageable.class));
    }

    @Test
    @DisplayName("a refused caller gets the refusal whatever the type: access is decided before the parameters")
    void accessBeforeParameters() {
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_RECEPTIONIST", hospital));

        for (String type : List.of("PHARMACY", "HOSPITAL", "not-a-type")) {
            assertThatThrownBy(() -> service.search(type, null)).as(type).isInstanceOf(AccessDeniedException.class);
        }
    }

    @Test
    @DisplayName("acting at a provider facility (a super-admin naming one) is refused")
    void providerActingFacilityIsRefused() {
        ActingScopeTestSupport.superAdminAt(callerId, pharmacy.getId());

        assertThatThrownBy(() -> service.search(null, null)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @DisplayName("a verified super-admin acting at a hospital reads it; in global view they must pick a hospital")
    void superAdmin() {
        ActingScopeTestSupport.superAdminAt(callerId, hospital.getId());
        assertThat(service.search(null, null).getEntries()).hasSize(1);

        HospitalContextHolder.clear();
        ActingScopeTestSupport.globalSuperAdmin(callerId);
        assertThatThrownBy(() -> service.search(null, null)).isInstanceOf(HospitalScopeRefusedException.class);
    }

    @Test
    @DisplayName("type: blank is both, PHARMACY or LABORATORY narrows, anything else is a 400")
    @SuppressWarnings("unchecked")
    void types() {
        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_NURSE", hospital));
        ArgumentCaptor<Collection<FacilityType>> types = ArgumentCaptor.forClass(Collection.class);

        service.search(" ", null);
        service.search("laboratory", null);
        verify(verificationRepository, times(2))
            .findDirectory(types.capture(), isNull(), any(Pageable.class));
        assertThat(types.getAllValues().get(0)).containsExactlyInAnyOrder(FacilityType.PHARMACY, FacilityType.LABORATORY);
        assertThat(types.getAllValues().get(1)).containsExactly(FacilityType.LABORATORY);

        for (String bad : List.of("HOSPITAL", "clinic")) {
            assertThatThrownBy(() -> service.search(bad, null)).isInstanceOf(BusinessException.class);
        }
    }

    @Test
    @DisplayName("the name filter is lower-cased, LIKE-escaped and capped")
    void namePattern() {
        assertThat(ProviderDirectoryServiceImpl.namePattern(null)).isNull();
        assertThat(ProviderDirectoryServiceImpl.namePattern("  ")).isNull();
        assertThat(ProviderDirectoryServiceImpl.namePattern(" Ph_ar%ma\\ ")).isEqualTo("%ph\\_ar\\%ma\\\\%");
        assertThat(ProviderDirectoryServiceImpl.namePattern("a".repeat(300))).hasSize(102);

        ActingScopeTestSupport.actingAt(callerId, hospital.getId());
        callerHolds(row("ROLE_PHARMACIST", hospital));
        service.search(null, "Centre");
        verify(verificationRepository).findDirectory(anyCollection(), eq("%centre%"), any(Pageable.class));
    }

    // ── helpers ────────────────────────────────────────────────────────

    private void callerHolds(UserRoleHospitalAssignment... rows) {
        when(assignmentRepository.findByUser_IdAndActiveTrue(callerId)).thenReturn(List.of(rows));
    }

    private static ProviderVerification verified(Hospital facility, String licence) {
        ProviderVerification v = new ProviderVerification();
        v.setHospital(facility);
        v.setLicenceNumber(licence);
        return v;
    }

    private static Hospital facility(FacilityType type) {
        Hospital h = new Hospital();
        h.setId(UUID.randomUUID());
        h.setFacilityType(type);
        h.setName(type + " facility");
        return h;
    }

    private static UserRoleHospitalAssignment row(String roleCode, Hospital at) {
        Role role = new Role();
        role.setCode(roleCode);
        role.setName(roleCode);
        UserRoleHospitalAssignment row = new UserRoleHospitalAssignment();
        row.setId(UUID.randomUUID());
        row.setRole(role);
        row.setHospital(at);
        row.setActive(true);
        return row;
    }
}
