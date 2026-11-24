package com.example.hms.payload.dto.identifiersearch;

import java.time.LocalDate;

/**
 * What the registration form has typed, for {@code POST /patients/registration-match}: a contact
 * and/or the identity (QA D1061 / D1064). Every field is optional; the birth date is ISO
 * (yyyy-mm-dd), as the query parameter was.
 */
public record RegistrationMatchRequestDTO(String email, String phone, String firstName, String lastName,
                                          LocalDate dateOfBirth) {
}
