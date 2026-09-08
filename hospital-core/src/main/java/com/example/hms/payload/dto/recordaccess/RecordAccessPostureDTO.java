package com.example.hms.payload.dto.recordaccess;

import com.example.hms.enums.RecordAccessPosture;
import com.example.hms.enums.TenantIsolationMode;

import java.util.UUID;

/**
 * A hospital's posture, with its isolation mode beside it because the two
 * read together: a SCHEMA tenant is never readable across whatever the
 * posture says, and the portal should show why the switch is inert there.
 */
public record RecordAccessPostureDTO(
    UUID hospitalId,
    RecordAccessPosture posture,
    TenantIsolationMode isolationMode
) {
}
