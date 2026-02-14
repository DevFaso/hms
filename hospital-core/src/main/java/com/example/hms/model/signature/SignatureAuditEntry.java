package com.example.hms.model.signature;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Embeddable audit entry for tracking digital signature lifecycle events.
 * Story #17: Generic Report Signing API
 */
@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode
public class SignatureAuditEntry {

    /**
     * Action performed on the signature (e.g., "SIGNED", "REVOKED", "VERIFIED")
     */
    @NotBlank
    @Size(max = 50)
    @Column(name = "action", nullable = false, length = 50)
    private String action;

    /**
     * User ID who performed the action
     */
    @NotNull
    @Column(name = "performed_by_user_id", nullable = false)
    private UUID performedByUserId;

    /**
     * Display name of user who performed the action
     */
    @Size(max = 255)
    @Column(name = "performed_by_display", length = 255)
    private String performedByDisplay;

    /**
     * Timestamp when the action occurred
     */
    @NotNull
    @Column(name = "performed_at", nullable = false)
    private LocalDateTime performedAt;

    /**
     * Additional details about the action
     */
    @Size(max = 1000)
    @Column(name = "details", length = 1000)
    private String details;

    /**
     * IP address from which the action was performed
     */
    @Size(max = 45)
    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    /**
     * Device/user agent information
     */
    @Size(max = 500)
    @Column(name = "device_info", length = 500)
    private String deviceInfo;
}
