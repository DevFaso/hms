package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;
import jakarta.validation.constraints.NotBlank;

/** One identifier (a UUID, a username, an e-mail or a licence number) to look up, in the body rather than the URL. */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record IdentifierQueryDTO(@NotBlank String identifier) {

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "IdentifierQueryDTO[identifier=" + Redacted.presence(identifier) + "]";
    }
}
