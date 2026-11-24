package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;

/**
 * The identifiers of {@code POST /patients/lookup}: one of identifier, email, phone, username or
 * MRN. {@code mri} is the legacy name of {@code mrn}, read only when {@code mrn} is blank.
 */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record PatientLookupRequestDTO(String identifier, String email, String phone, String username, String mrn,
                                      String mri) {

    /** Never prints a value: a search's terms identify people. */
    @Override
    public String toString() {
        return "PatientLookupRequestDTO[identifier=" + Redacted.presence(identifier)
            + ", email=" + Redacted.presence(email)
            + ", phone=" + Redacted.presence(phone)
            + ", username=" + Redacted.presence(username)
            + ", mrn=" + Redacted.presence(mrn)
            + ", mri=" + Redacted.presence(mri) + "]";
    }
}
