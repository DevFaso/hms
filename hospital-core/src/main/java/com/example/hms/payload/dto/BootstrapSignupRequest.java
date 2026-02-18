package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class BootstrapSignupRequest {
    @NotBlank
    @Size(min = 3, max = 50)
    private String username;

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 8, max = 120)
    private String password;

    @NotBlank
    private String firstName;

    @NotBlank
    private String lastName;

    private String phoneNumber;
}
