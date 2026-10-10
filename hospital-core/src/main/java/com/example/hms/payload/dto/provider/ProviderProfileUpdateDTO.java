package com.example.hms.payload.dto.provider;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
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
public class ProviderProfileUpdateDTO {

    @NotBlank(message = "{hospital.phoneNumber.required}")
    @Size(max = 30)
    private String phoneNumber;

    @Email(message = "{hospital.email.invalid}")
    @Size(max = 255)
    private String email;

    @Size(max = 255)
    private String website;
}
