package com.example.hms.payload.dto.pharmacy;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.UUID;

/**
 * QA D2679: the witness of a controlled-drug movement confirms with their OWN factor: the current code of their
 * authenticator (or one of their backup codes) when they have one, else their password. Verified on this call
 * and dropped: never stored and never logged ({@code toString} excludes it).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "secret")
public class WitnessConfirmationRequestDTO {

    /** The colleague who confirms; never the caller. */
    @NotNull
    private UUID witnessedBy;

    /** The witness's password, or the code of their authenticator. */
    @NotBlank
    @Size(max = 256)
    private String secret;
}
