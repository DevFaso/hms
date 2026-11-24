package com.example.hms.payload.dto.identifiersearch;

/**
 * The identifiers of {@code POST /patients/lookup}: one of identifier, email, phone, username or
 * MRN. {@code mri} is the legacy name of {@code mrn}, read only when {@code mrn} is blank.
 */
public record PatientLookupRequestDTO(String identifier, String email, String phone, String username, String mrn,
                                      String mri) {
}
