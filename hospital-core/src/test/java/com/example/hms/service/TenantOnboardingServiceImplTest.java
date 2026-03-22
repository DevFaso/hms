package com.example.hms.service;

import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.OrganizationSecurityPolicy;
import com.example.hms.payload.dto.superadmin.TenantOnboardingStatusDTO;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.service.impl.TenantOnboardingServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TenantOnboardingServiceImplTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationSecurityPolicyRepository securityPolicyRepository;

    @InjectMocks private TenantOnboardingServiceImpl service;

    private final UUID ORG_ID = UUID.randomUUID();

    private Organization buildOrg(boolean hasProfile, boolean hasHospitals, boolean active) {
        Organization org = Organization.builder()
                .name(hasProfile ? "Test Org" : null)
                .code("ORG-001")
                .primaryContactEmail(hasProfile ? "admin@test.com" : null)
                .defaultTimezone(hasProfile ? "UTC" : null)
                .active(active)
                .build();
        org.setId(ORG_ID);

        if (hasHospitals) {
            Set<Hospital> hospitals = new HashSet<>();
            Hospital h = new Hospital();
            h.setId(UUID.randomUUID());
            h.setName("Test Hospital");
            hospitals.add(h);
            org.setHospitals(hospitals);
        } else {
            org.setHospitals(new HashSet<>());
        }
        return org;
    }

    @Test
    void getOnboardingStatus_allStepsCompleted() {
        Organization org = buildOrg(true, true, true);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));

        OrganizationSecurityPolicy policy = new OrganizationSecurityPolicy();
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(List.of(policy));

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getOrganizationId()).isEqualTo(ORG_ID);
        assertThat(result.getOrganizationName()).isEqualTo("Test Org");
        assertThat(result.getOrganizationCode()).isEqualTo("ORG-001");
        assertThat(result.getTotalSteps()).isEqualTo(4);
        assertThat(result.getCompletedSteps()).isEqualTo(4);
        assertThat(result.getSteps()).hasSize(4);
        assertThat(result.getSteps()).allMatch(TenantOnboardingStatusDTO.OnboardingStep::isCompleted);
    }

    @Test
    void getOnboardingStatus_noStepsCompleted() {
        Organization org = buildOrg(false, false, false);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(Collections.emptyList());

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getCompletedSteps()).isZero();
        assertThat(result.getTotalSteps()).isEqualTo(4);
        assertThat(result.getSteps()).noneMatch(TenantOnboardingStatusDTO.OnboardingStep::isCompleted);
    }

    @Test
    void getOnboardingStatus_profileOnly() {
        Organization org = buildOrg(true, false, false);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(Collections.emptyList());

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getCompletedSteps()).isEqualTo(1);
        // Profile step is completed
        assertThat(result.getSteps().get(0).isCompleted()).isTrue();
        assertThat(result.getSteps().get(0).getKey()).isEqualTo("profile");
        assertThat(result.getSteps().get(0).getDetail()).contains("configured");
        // Hospital step not completed
        assertThat(result.getSteps().get(1).isCompleted()).isFalse();
        assertThat(result.getSteps().get(1).getKey()).isEqualTo("hospital");
    }

    @Test
    void getOnboardingStatus_hospitalStep_detail() {
        Organization org = buildOrg(false, true, false);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(Collections.emptyList());

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getSteps().get(1).isCompleted()).isTrue();
        assertThat(result.getSteps().get(1).getDetail()).contains("1 hospital(s) linked");
    }

    @Test
    void getOnboardingStatus_securityPoliciesCompleted() {
        Organization org = buildOrg(false, false, false);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));

        OrganizationSecurityPolicy p1 = new OrganizationSecurityPolicy();
        OrganizationSecurityPolicy p2 = new OrganizationSecurityPolicy();
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(List.of(p1, p2));

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getSteps().get(2).isCompleted()).isTrue();
        assertThat(result.getSteps().get(2).getKey()).isEqualTo("security");
        assertThat(result.getSteps().get(2).getDetail()).contains("2 active policy(ies)");
    }

    @Test
    void getOnboardingStatus_activatedStep() {
        Organization org = buildOrg(false, false, true);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(Collections.emptyList());

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getSteps().get(3).isCompleted()).isTrue();
        assertThat(result.getSteps().get(3).getKey()).isEqualTo("activated");
        assertThat(result.getSteps().get(3).getDetail()).isEqualTo("Organization is active");
    }

    @Test
    void getOnboardingStatus_inactiveStep_detail() {
        Organization org = buildOrg(false, false, false);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(Collections.emptyList());

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getSteps().get(3).isCompleted()).isFalse();
        assertThat(result.getSteps().get(3).getDetail()).isEqualTo("Organization is inactive");
    }

    @Test
    void getOnboardingStatus_orgNotFound_throwsResourceNotFoundException() {
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getOnboardingStatus(ORG_ID))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getOnboardingStatus_nullHospitals_treatedAsEmpty() {
        Organization org = buildOrg(true, false, true);
        org.setHospitals(null);
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(Collections.emptyList());

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getSteps().get(1).isCompleted()).isFalse();
        assertThat(result.getSteps().get(1).getDetail()).isEqualTo("No hospitals assigned yet");
    }

    @Test
    void getOnboardingStatus_partialProfile_notCompleted() {
        Organization org = buildOrg(false, false, false);
        org.setName("Org Name");
        org.setPrimaryContactEmail("email@test.com");
        // defaultTimezone is null -> profile not complete
        when(organizationRepository.findByIdWithHospitals(ORG_ID)).thenReturn(Optional.of(org));
        when(securityPolicyRepository.findByOrganizationIdAndActiveTrue(ORG_ID))
                .thenReturn(Collections.emptyList());

        TenantOnboardingStatusDTO result = service.getOnboardingStatus(ORG_ID);

        assertThat(result.getSteps().get(0).isCompleted()).isFalse();
        assertThat(result.getSteps().get(0).getDetail()).contains("Missing");
    }
}
