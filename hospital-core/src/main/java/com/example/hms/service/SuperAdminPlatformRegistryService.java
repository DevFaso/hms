package com.example.hms.service;

import com.example.hms.payload.dto.superadmin.PlatformRegistrySnapshotDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowRequestDTO;
import com.example.hms.payload.dto.superadmin.PlatformReleaseWindowResponseDTO;
import com.example.hms.payload.dto.superadmin.SuperAdminPlatformRegistrySummaryDTO;

import java.util.List;

public interface SuperAdminPlatformRegistryService {

    SuperAdminPlatformRegistrySummaryDTO getRegistrySummary();

    PlatformReleaseWindowResponseDTO scheduleReleaseWindow(PlatformReleaseWindowRequestDTO request);

    /** QA D4082: one release window. */
    PlatformReleaseWindowResponseDTO getReleaseWindow(java.util.UUID id);

    /** QA D4082: corrects a window that has not started; a started, finished or cancelled one is refused (409). */
    PlatformReleaseWindowResponseDTO updateReleaseWindow(java.util.UUID id, PlatformReleaseWindowRequestDTO request);

    /** QA D4082: withdraws a window that has not finished; it stays in the list as cancelled. */
    PlatformReleaseWindowResponseDTO cancelReleaseWindow(java.util.UUID id);

    PlatformRegistrySnapshotDTO getRegistrySnapshot();

    /** Release windows, newest start first, at most {@code limit} (default and ceiling in the implementation). */
    List<PlatformReleaseWindowResponseDTO> listReleaseWindows(Integer limit);
}
