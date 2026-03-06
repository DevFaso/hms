package com.example.hms.payload.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;
import java.util.UUID;

/**
 * DTO for partial user updates (PUT /users/{id}).
 *
 * <p>Every field is <b>nullable</b>: when a field is {@code null} or blank the service
 * preserves the existing value. This avoids forcing the caller to re-send the full
 * resource just to change one field.</p>
 *
 * <p>Password is only validated when non-blank — omitting it (or sending "") means
 * "leave existing password unchanged".</p>
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(name = "UpdateUserRequestDTO", description = "Partial update payload — only provide fields you want to change")
public class UpdateUserRequestDTO {

    @Size(min = 3, max = 20, message = "Username must be 3–20 characters")
    @Schema(description = "New username; omit to keep current")
    private String username;

    @Email(message = "Email should be valid")
    @Schema(description = "New email; omit to keep current")
    private String email;

    @Size(min = 8, max = 64, message = "Password length must be 8–64 characters")
    @Schema(description = "New password; omit or send blank to keep current")
    private String password;

    @Schema(description = "First name; omit to keep current")
    private String firstName;

    @Schema(description = "Last name; omit to keep current")
    private String lastName;

    @Schema(description = "Phone number; omit to keep current")
    private String phoneNumber;

    @JsonProperty("active")
    @Schema(description = "Active flag; omit to keep current")
    private Boolean active;

    @Schema(description = "Role codes to assign; omit to keep current roles")
    private Set<String> roleNames;

    @Schema(description = "Hospital ID for assignment context; omit to keep current")
    private UUID hospitalId;
}
