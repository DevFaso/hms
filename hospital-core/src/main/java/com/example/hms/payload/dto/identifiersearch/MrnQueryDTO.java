package com.example.hms.payload.dto.identifiersearch;

import jakarta.validation.constraints.NotBlank;

/** One medical record number to look up, in the body rather than the URL. */
public record MrnQueryDTO(@NotBlank String mrn) {
}
