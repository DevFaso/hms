package com.example.hms.payload.dto.platform;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/** Issue an API key to a third-party client (Tier 2 item 45). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyCreateDTO {

    @NotBlank(message = "A label is required — it names the client this key is for.")
    @Size(max = 120)
    private String label;

    /** Optional — a key without an expiry lives until revoked (the V145 lesson). */
    private LocalDate expiresOn;
}
