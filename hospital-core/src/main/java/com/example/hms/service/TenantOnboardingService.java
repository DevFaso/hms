package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.TenantOnboardingStatusDTO;

import java.util.UUID;

public interface TenantOnboardingService {

    TenantOnboardingStatusDTO getOnboardingStatus(UUID organizationId);
}
