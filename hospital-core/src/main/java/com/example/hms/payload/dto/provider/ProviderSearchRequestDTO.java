package com.example.hms.payload.dto.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** The provider list's filters with a typed term (a name, a code or a city). */
@Data
@com.example.hms.validation.RejectsUnknownFields(com.example.hms.validation.RejectsUnknownFields.Kind.SEARCH)
public class ProviderSearchRequestDTO {

    private FacilityType type;
    private ProviderVerificationStatus status;

    /** Required: the list without a term is the GET; a search with nothing typed is refused on this field. */
    @NotBlank
    @Size(max = 100)
    private String term;
}
