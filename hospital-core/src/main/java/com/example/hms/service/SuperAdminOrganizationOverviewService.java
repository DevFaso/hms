package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationHierarchyResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminOrganizationsSummaryDTO;

import java.util.Locale;

public interface SuperAdminOrganizationOverviewService {

    SuperAdminOrganizationsSummaryDTO getOrganizationsSummary();

    SuperAdminOrganizationHierarchyResponseDTO getOrganizationHierarchy(
        boolean includeStaff,
        boolean includePatients,
        Boolean activeOnly,
        String search,
        int staffLimit,
        int patientLimit,
        Locale locale
    );
}
