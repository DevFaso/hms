package com.example.hms.payload.dto.credential;

import jakarta.validation.constraints.NotBlank;

/** The saved destination the caller has explicitly read back before requesting delivery. */
public record RecoveryContactConfirmationRequest(
    @NotBlank(message = "{users.recovery.confirmationRequired}") String confirmedContactValue) {
}
