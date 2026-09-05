package com.example.hms.mapper;

import com.example.hms.model.platform.ApiKey;
import com.example.hms.payload.dto.platform.ApiKeyResponseDTO;
import org.springframework.stereotype.Component;

/**
 * API key row → DTO (Tier 2 item 45), per the house mapper convention.
 * Never maps the hash — the prefix is the only key material a read
 * response carries.
 */
@Component
public class ApiKeyMapper {

    public ApiKeyResponseDTO toDto(ApiKey k) {
        if (k == null) {
            return null;
        }
        return ApiKeyResponseDTO.builder()
            .id(k.getId())
            .label(k.getLabel())
            .keyPrefix(k.getKeyPrefix())
            .status(k.getStatus())
            .expiresOn(k.getExpiresOn())
            .lastUsedAt(k.getLastUsedAt())
            .revokedAt(k.getRevokedAt())
            .createdAt(k.getCreatedAt())
            .build();
    }
}
