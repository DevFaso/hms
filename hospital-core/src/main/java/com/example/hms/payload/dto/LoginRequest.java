package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class LoginRequest {
    @NotBlank
    private String username;

    @NotBlank
    private String password;

    /**
     * Optional — when a user holds multiple roles, the frontend sends a second
     * login request with the role the user picked.  When {@code null} the backend
     * returns the full role list so the UI can show a role-picker.
     */
    private String selectedRole;
}

