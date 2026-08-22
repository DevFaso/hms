package com.example.hms.model.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Singleton row for the platform read-only (downtime) mode (P3 #23a).
 * Enforced by {@link com.example.hms.security.ReadOnlyModeFilter}; the
 * portal banner reads it via GET /downtime/status. V80
 * security_revocations pattern: one row, id=1, DB CHECK.
 */
@Entity
@Table(name = "platform_downtime_state", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PlatformDowntimeState {

    public static final Integer SINGLETON_ID = 1;

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "read_only", nullable = false)
    private boolean readOnly;

    @Column(name = "message", length = 500)
    private String message;

    @Column(name = "activated_at")
    private Instant activatedAt;

    @Column(name = "activated_by_username", length = 255)
    private String activatedByUsername;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
