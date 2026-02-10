package com.example.hms.payload.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.Period;

@Getter
@Setter
public abstract class BaseUserDTO {
    @NotBlank(message = "Username is required.")
    @Size(min = 3, max = 20)
    private String username;

    @NotBlank(message = "Email is required.")
    @Email
    private String email;

    @NotBlank(message = "First name is required.")
    private String firstName;

    @NotBlank(message = "Last name is required.")
    private String lastName;

    @NotBlank(message = "Phone number is required.")
    private String phoneNumber;

    private LocalDate dateOfBirth;

    public boolean isMinor() {
        return dateOfBirth != null && Period.between(dateOfBirth, LocalDate.now()).getYears() < 18;
    }
}
