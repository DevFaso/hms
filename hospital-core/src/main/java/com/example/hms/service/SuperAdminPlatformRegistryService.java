package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.PlatformRegistrySnapshotDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;

import java.util.List;

public interface SuperAdminPlatformRegistryService {

    SuperAdminPlatformRegistrySummaryDTO getRegistrySummary();

    PlatformReleaseWindowResponseDTO scheduleReleaseWindow(PlatformReleaseWindowRequestDTO request);

    PlatformRegistrySnapshotDTO getRegistrySnapshot();

    /** Release windows, newest start first, at most {@code limit} (default and ceiling in the implementation). */
    List<PlatformReleaseWindowResponseDTO> listReleaseWindows(Integer limit);
}
