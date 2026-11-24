package com.example.hms.payload.dto.identifiersearch;

import com.example.hms.validation.RejectsUnknownFields;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/** One e-mail address to look up, in the body rather than the URL. */
@RejectsUnknownFields(RejectsUnknownFields.Kind.SEARCH)
public record EmailQueryDTO(
    @NotBlank @Email(message = "{validation.email.invalid}") String email) {
}
