package com.example.hms.payload.dto.identifiersearch;

/** What the desk has typed, for {@code POST /reception/patients/duplicate-candidates}. Every field is optional. */
public record DuplicateCandidateRequestDTO(String name, String dob, String phone) {
}
