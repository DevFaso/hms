package com.example.hms.payload.dto.portal;

import com.example.hms.enums.PatientDocumentType;
import jakarta.validation.constraints.NotNull;
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

    @NotNull(message = "Document type is required")
    private PatientDocumentType documentType;

    /** Optional date when the document was originally created/collected. */
    private LocalDate collectionDate;

    private String notes;
}
