package com.example.hms.payload.dto.identifiersearch;

import jakarta.validation.constraints.NotBlank;

/** One username to look up, in the body rather than the URL. */
public record UsernameQueryDTO(@NotBlank String username) {
}
