package com.example.hms.payload.dto.identifiersearch;

import jakarta.validation.constraints.NotBlank;

/** One phone number to look up, in the body rather than the URL. */
public record PhoneQueryDTO(@NotBlank String phone) {
}
