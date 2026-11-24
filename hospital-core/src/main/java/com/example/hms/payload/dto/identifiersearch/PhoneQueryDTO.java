package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;
import jakarta.validation.constraints.NotBlank;

/** One phone number to look up, in the body rather than the URL. */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record PhoneQueryDTO(@NotBlank String phone) {

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "PhoneQueryDTO[phone=" + Redacted.presence(phone) + "]";
    }
}
