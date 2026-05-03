package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EmergencyBroadcastRequestDTO {

    @NotBlank
    @Size(min = 5, max = 1000)
    private String message;

    /** INFO | WARN | CRITICAL — frontend uses this to colour the banner. */
    @Size(max = 20)
    private String severity;
}
