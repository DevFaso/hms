package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;

/**
 * The identifiers of {@code POST /patients/search}. Every field is optional, as the query
 * parameters were; {@code dob} keeps the formats the service reads (yyyy-mm-dd, dd/mm/yyyy,
 * dd-mm-yyyy). Paging and the hospital scope stay query parameters: they identify no one.
 */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record PatientSearchRequestDTO(String mrn, String name, String dob, String phone, String email) {

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "PatientSearchRequestDTO[mrn=" + Redacted.presence(mrn)
            + ", name=" + Redacted.presence(name)
            + ", dob=" + Redacted.presence(dob)
            + ", phone=" + Redacted.presence(phone)
            + ", email=" + Redacted.presence(email) + "]";
    }
}
