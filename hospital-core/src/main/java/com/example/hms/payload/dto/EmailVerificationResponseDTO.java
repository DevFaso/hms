package com.example.hms.payload.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
public class EmailVerificationResponseDTO {
    private String message;
    private boolean verified;
}
