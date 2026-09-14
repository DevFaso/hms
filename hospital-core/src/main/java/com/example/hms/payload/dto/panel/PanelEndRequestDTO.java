package com.example.hms.payload.dto.panel;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** End an empanelment without a successor (moved away, deceased, opted out). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PanelEndRequestDTO {

    @NotBlank(message = "{panelEnd.reason.required}")
    @Size(max = 500)
    private String reason;
}
