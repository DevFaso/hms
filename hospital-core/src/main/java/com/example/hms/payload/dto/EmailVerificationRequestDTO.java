package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailVerificationRequestDTO {

    @NotBlank(message = "{emailVerification.email.required}")
    @Email(message = "{emailVerification.email.invalid}")
    private String email;

    @NotBlank(message = "{emailVerification.token.required}")
    private String token;
}
