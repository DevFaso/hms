package com.example.hms.payload.dto.recordaccess;

import jakarta.validation.constraints.Size;

/** Body of {@code POST /patients/{id}/record-sharing/opt-out}. The reason is never required. */
public record OptOutRequestDTO(
    @Size(max = 1000) String reason
) {
}
