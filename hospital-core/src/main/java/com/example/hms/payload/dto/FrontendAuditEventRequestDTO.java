package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Lightweight event emitted by the SPA for client-side actions.")
public class FrontendAuditEventRequestDTO {

    @NotBlank
    @Schema(description = "Event type identifier", example = "PRESCRIPTION_INIT")
    private String type;

    @Schema(description = "Identifier for the actor (user id, email, etc)")
    private String actor;

    @Schema(description = "Arbitrary metadata payload keyed by string")
    private Map<String, Object> meta;

    @Schema(description = "Client supplied ISO-8601 timestamp", example = "2025-01-12T10:15:00Z")
    private String ts;

    @Schema(description = "User agent reported by the frontend")
    private String userAgent;

    @Schema(description = "Client supplied IP address override (optional)")
    private String ipAddress;

    public Map<String, Object> getMeta() {
        if (meta == null) {
            meta = new HashMap<>();
        }
        return meta;
    }
}
