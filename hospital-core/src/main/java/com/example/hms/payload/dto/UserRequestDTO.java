package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.UUID;

@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
@Schema(name = "UserRequestDTO", description = "Create/update user payload")
public class UserRequestDTO extends BaseUserDTO {
    @Schema(description = "User ID for updates; omit for creation")
    private UUID id;

    /** Optional for updates; required for creation (enforce in service). */
    @Size(min = 8, max = 64, message = "Password length must be 8–64 characters")
    @Schema(description = "Raw password; only provide on creation or password change")
    private String password;

    // With boolean fields, Lombok generates isActive(); use @JsonProperty to keep JSON "active"
    @JsonProperty("active")
    @Schema(description = "Whether the user is active (defaults to true when omitted)")
    private Boolean active;

    @Schema(description = "Default/related hospital to attach context to")
    private UUID hospitalId;

    @Size(max = 100)
    private String emergencyContactName;

    @Pattern(regexp = "^[+\\d][\\d\\-()\\s]{6,20}$",
        message = "Emergency contact phone has invalid format")
    private String emergencyContactPhone;
}
