package com.example.hms.payload.dto.panel;

import com.example.hms.enums.PanelRole;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** Empanel (or re-empanel) a patient to a provider/CHW (Tier 2 item 37). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PanelAssignmentRequestDTO {

    @NotNull(message = "providerStaffId is required")
    private UUID providerStaffId;

    @NotNull(message = "panelRole is required")
    private PanelRole panelRole;

    /** Optional — defaults to today; may predate the row for backfilled paper panels. */
    private LocalDate assignedOn;
}
