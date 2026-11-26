package com.example.hms.payload.dto.billing;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Insurer claims plan PR 1 (QA D3984): the insurer's pre-authorisation number (« bon / accord préalable ») the desk or
 * billing records on an invoice line or on an imaging order. Letters, digits, spaces and {@code . / _ # : -} only;
 * the service checks it again.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PreAuthorisationRequestDTO {

    @NotBlank
    @Size(max = 120)
    private String authorizationNumber;
}
