package com.example.hms.payload.dto.identifiersearch;

import jakarta.validation.constraints.NotBlank;

/** One identifier (a UUID, a username, an e-mail or a licence number) to look up, in the body rather than the URL. */
public record IdentifierQueryDTO(@NotBlank String identifier) {
}
