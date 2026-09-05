package com.example.hms.payload.dto.platform;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The issuance/rotation response (Tier 2 item 45) — the ONLY place the
 * raw key ever appears. It is not stored and cannot be shown again.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyIssuedDTO {

    private ApiKeyResponseDTO key;
    private String rawKey;
}
