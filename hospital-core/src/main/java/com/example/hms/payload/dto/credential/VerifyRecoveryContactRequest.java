package com.example.hms.payload.dto.credential;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class VerifyRecoveryContactRequest {

    @NotBlank
    @Size(min = 6, max = 6)
    private String code;
}
