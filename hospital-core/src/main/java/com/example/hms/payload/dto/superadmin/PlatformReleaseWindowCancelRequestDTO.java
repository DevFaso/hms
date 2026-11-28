package com.example.hms.payload.dto.superadmin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** Why a release window is cancelled: kept on the window and in its audit row. */
@Data
public class PlatformReleaseWindowCancelRequestDTO {

    @NotBlank
    @Size(max = 500)
    private String reason;
}
