package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Whether the current request is running under a support-impersonation token, and if so who.")
public class ImpersonationActiveResponseDTO {

    private boolean impersonating;

    private UUID impersonatorUserId;
    private String impersonatorUsername;
    private UUID targetUserId;
    private String targetUsername;

    /**
     * MVP-4b: ISO-8601 expiry of the active impersonation token (null
     * when not impersonating). Lets the frontend re-arm its countdown
     * on a page refresh without having to decode the JWT itself.
     */
    private Instant expiresAt;
}
