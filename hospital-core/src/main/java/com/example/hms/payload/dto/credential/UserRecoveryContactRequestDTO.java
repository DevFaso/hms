package com.example.hms.payload.dto.credential;

import com.example.hms.enums.RecoveryContactType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class UserRecoveryContactRequestDTO {

    private String id;

    @NotNull
    private RecoveryContactType contactType;

    @NotBlank
    private String contactValue;

    private boolean verified;

    private boolean primaryContact;

    private String notes;
}
