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
    @NotBlank(message = "{user.username.required}")
    @Size(min = 3, max = 20)
    private String username;

    @NotBlank(message = "{user.email.required}")
    @Email
    private String email;

    @NotBlank(message = "{user.firstName.required}")
    private String firstName;

    @NotBlank(message = "{user.lastName.required}")
    private String lastName;

    @NotBlank(message = "{user.phoneNumber.required}")
    private String phoneNumber;

    private LocalDate dateOfBirth;

    public boolean isMinor() {
        return dateOfBirth != null && Period.between(dateOfBirth, LocalDate.now()).getYears() < 18;
    }
}
