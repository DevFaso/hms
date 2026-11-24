package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;

/** What the desk has typed, for {@code POST /reception/patients/duplicate-candidates}. Every field is optional. */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record DuplicateCandidateRequestDTO(String name, String dob, String phone) {
}
