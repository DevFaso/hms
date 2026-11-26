package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EmailVerificationRequestDTO {

    /** Optional: the mailed link carries the token alone. Sent by clients that ask for the address beside it. */
    @Email(message = "{emailVerification.email.invalid}")
    private String email;

    @NotBlank(message = "{emailVerification.token.required}")
    private String token;
}
