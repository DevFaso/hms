package com.example.hms.payload.dto.superadmin;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
}
