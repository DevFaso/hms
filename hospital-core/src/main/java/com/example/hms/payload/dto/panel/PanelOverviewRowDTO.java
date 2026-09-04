package com.example.hms.payload.dto.panel;

import com.example.hms.enums.PanelRole;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/** One provider's live panel size, for the admin overview. */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PanelOverviewRowDTO {

    private UUID providerStaffId;
    private String providerName;
    private PanelRole panelRole;
    private long activeCount;
}
