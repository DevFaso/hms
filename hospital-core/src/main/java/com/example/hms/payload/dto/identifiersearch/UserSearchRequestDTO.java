package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;

/** The filters of {@code POST /users/search}. Every field is optional; paging stays in the query. */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record UserSearchRequestDTO(String name, String role, String email) {

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "UserSearchRequestDTO[name=" + Redacted.presence(name)
            + ", role=" + Redacted.presence(role)
            + ", email=" + Redacted.presence(email) + "]";
    }
}
