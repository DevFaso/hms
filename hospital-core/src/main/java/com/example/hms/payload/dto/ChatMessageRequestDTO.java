package com.example.hms.payload.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Data
public class ChatMessageRequestDTO {
    /** Recipient email – used by hospital-context sends. Optional if recipientId is set. */
    private String recipientEmail;

    /** Hospital name – used by hospital-context sends. Optional for SUPER_ADMIN. */
    private String hospitalName;

    @NotBlank
    private String content;

    private String roleCode;

    /**
     * Deprecated: ignored by the current messaging implementation.
     * The sender is always derived from the SecurityContext.
     * @deprecated since 1.0, forRemoval in a future release.
     */
    @Deprecated(since = "1.0", forRemoval = true)
    private UUID senderId;

    /** Recipient UUID – alternative to recipientEmail. */
    private UUID recipientId;
}
