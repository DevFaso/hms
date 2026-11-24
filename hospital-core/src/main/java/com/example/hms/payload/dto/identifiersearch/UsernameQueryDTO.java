package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;
import jakarta.validation.constraints.NotBlank;

/** One username to look up, in the body rather than the URL. */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record UsernameQueryDTO(@NotBlank String username) {

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "UsernameQueryDTO[username=" + Redacted.presence(username) + "]";
    }
}
