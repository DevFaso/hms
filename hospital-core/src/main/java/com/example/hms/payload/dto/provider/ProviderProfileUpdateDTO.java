package com.example.hms.payload.dto.provider;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a PROVIDER_ADMIN may change about its own facility
 * ({@code PUT /provider/profile}): the operational contact only. A PUT, so
 * every field is replaced; a blank optional field clears it.
 *
 * <p>Nothing of the verified identity is here (name, licence, registered
 * address, legal numbers): those come from the registration documents and
 * change only through a super-admin's new verification. Opening hours and
 * the accepting-orders switch have no column yet (no migration in this slice).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "The operational contact a provider admin may change.")
// Anything else sent (a name, a licence number) is ignored, never applied.
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProviderProfileUpdateDTO {

    /** {@code http://} or {@code https://} (any case), a host, then an optional path, query or fragment; no whitespace. */
    public static final String WEBSITE_PATTERN = "(?i)^https?://[^\\s/?#]+(?:[/?#][^\\s]*)?$";

    /** A hospital's phone rule ({@code HospitalRequestDTO}): 10 to 15 characters. */
    @NotBlank(message = "{hospital.phoneNumber.required}")
    @Size(min = 10, max = 15, message = "{hospital.phoneNumber.size}")
    private String phoneNumber;

    @Email(message = "{hospital.email.invalid}")
    @Size(max = 255)
    private String email;

    /** An http or https address only: never {@code javascript:}, {@code data:} or a bare string a portal would link. */
    @Size(max = 255)
    @Pattern(regexp = WEBSITE_PATTERN, message = "{provider.website.invalid}")
    private String website;
}
