package com.example.hms.payload.dto.panel;

import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

/** One empanelment row, for the chart card and the panel worklist alike. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PanelAssignmentResponseDTO {

    private UUID id;
    private UUID patientId;
    private String patientName;
    private UUID providerStaffId;
    private String providerName;
    private PanelRole panelRole;
    private PanelAssignmentStatus status;
    private LocalDate assignedOn;
    private String assignedByName;
    private LocalDate endedOn;
    private String endReason;
}
