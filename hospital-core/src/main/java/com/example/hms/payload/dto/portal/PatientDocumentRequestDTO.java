package com.example.hms.payload.dto.portal;

import com.example.hms.enums.PatientDocumentType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PatientDocumentRequestDTO {

    @NotNull(message = "{patientDocument.documentType.required}")
    private PatientDocumentType documentType;

    /** Optional date when the document was originally created/collected. */
    private LocalDate collectionDate;

    /**
     * The column is VARCHAR(2048). The DTO is built by hand in the controller
     * from multipart fields, so bean validation never runs on it: the service
     * checks {@link #NOTES_MAX_LENGTH} itself, which turns an over-long note
     * into a 400 instead of a 500 from the database.
     */
    @Size(max = NOTES_MAX_LENGTH)
    private String notes;

    public static final int NOTES_MAX_LENGTH = 2048;
}
