package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.PlatformRegistrySnapshotDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;

public interface SuperAdminPlatformRegistryService {

    SuperAdminPlatformRegistrySummaryDTO getRegistrySummary();

    PlatformReleaseWindowResponseDTO scheduleReleaseWindow(PlatformReleaseWindowRequestDTO request);

    PlatformRegistrySnapshotDTO getRegistrySnapshot();
}
