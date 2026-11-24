package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;
import jakarta.validation.constraints.NotBlank;

/** One medical record number to look up, in the body rather than the URL. */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record MrnQueryDTO(@NotBlank String mrn) {

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "MrnQueryDTO[mrn=" + Redacted.presence(mrn) + "]";
    }
}
