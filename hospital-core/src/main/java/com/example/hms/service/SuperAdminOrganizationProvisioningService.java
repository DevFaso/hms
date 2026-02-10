package com.example.hms.service;

import com.example.hms.payload.dto.OrganizationResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminCreateOrganizationRequestDTO;

public interface SuperAdminOrganizationProvisioningService {

    OrganizationResponseDTO createOrganization(SuperAdminCreateOrganizationRequestDTO request);
}
