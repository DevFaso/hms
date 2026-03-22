package com.example.hms.service.impl;

import com.example.hms.model.Organization;
import com.example.hms.payload.dto.superadmin.TenantOnboardingStatusDTO;
import com.example.hms.payload.dto.superadmin.TenantOnboardingStatusDTO.OnboardingStep;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.OrganizationSecurityPolicyRepository;
import com.example.hms.service.TenantOnboardingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class TenantOnboardingServiceImpl implements TenantOnboardingService {

    private final OrganizationRepository organizationRepository;
    private final HospitalRepository hospitalRepository;
    private final OrganizationSecurityPolicyRepository securityPolicyRepository;

    @Override
    public TenantOnboardingStatusDTO getOnboardingStatus(UUID organizationId) {
        Organization org = organizationRepository.findByIdWithHospitals(organizationId)
            .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + organizationId));

        List<OnboardingStep> steps = new ArrayList<>();

        // Step 1: Basic org profile
        boolean hasProfile = org.getName() != null && org.getPrimaryContactEmail() != null
            && org.getDefaultTimezone() != null;
        steps.add(OnboardingStep.builder()
            .key("profile")
            .label("Organization profile completed")
            .completed(hasProfile)
            .detail(hasProfile ? "Name, contact, and timezone configured" : "Missing required profile fields")
            .build());

        // Step 2: At least one hospital assigned
        List<?> hospitals = hospitalRepository.findByOrganizationIdOrderByNameAsc(organizationId);
        boolean hasHospital = !hospitals.isEmpty();
        steps.add(OnboardingStep.builder()
            .key("hospital")
            .label("Hospital assigned")
            .completed(hasHospital)
            .detail(hasHospital ? hospitals.size() + " hospital(s) linked" : "No hospitals assigned yet")
            .build());

        // Step 3: Security policies configured
        var policies = securityPolicyRepository.findByOrganizationIdAndActiveTrue(organizationId);
        boolean hasPolicies = !policies.isEmpty();
        steps.add(OnboardingStep.builder()
            .key("security")
            .label("Security policies configured")
            .completed(hasPolicies)
            .detail(hasPolicies ? policies.size() + " active policy(ies)" : "No security policies set")
            .build());

        // Step 4: Organization is active
        steps.add(OnboardingStep.builder()
            .key("activated")
            .label("Organization activated")
            .completed(org.isActive())
            .detail(org.isActive() ? "Organization is active" : "Organization is inactive")
            .build());

        int completed = (int) steps.stream().filter(OnboardingStep::isCompleted).count();

        return TenantOnboardingStatusDTO.builder()
            .organizationId(org.getId())
            .organizationName(org.getName())
            .organizationCode(org.getCode())
            .completedSteps(completed)
            .totalSteps(steps.size())
            .steps(steps)
            .build();
    }
}
