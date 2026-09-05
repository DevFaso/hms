package com.example.hms.payload.dto.platform;

import com.example.hms.enums.platform.ApiKeyStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One issued API key (Tier 2 item 45). Carries the display prefix only —
 * the raw key exists in exactly one response: the issuance/rotation one.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApiKeyResponseDTO {

    private UUID id;
    private String label;
    private String keyPrefix;
    private ApiKeyStatus status;
    private LocalDate expiresOn;
    private LocalDateTime lastUsedAt;
    private LocalDateTime revokedAt;
    private LocalDateTime createdAt;
}
