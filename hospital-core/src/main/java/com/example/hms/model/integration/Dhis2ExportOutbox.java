package com.example.hms.model.integration;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDateTime;

/**
 * One row per data value sent in a {@link Dhis2ExportRun}. The UNIQUE
 * constraint on (run, period, orgUnit, dataElement, categoryOptionCombo)
 * keeps replays idempotent — the orchestrator can safely re-POST the
 * same ADX payload.
 *
 * <p>{@code value} is stored as a string because DHIS2 dataElements can
 * be numeric, boolean, text, or coded; the v0 immunization scope only
 * emits integer counts but the schema is intentionally generic.
 */
@Entity
@Table(
    name = "dhis2_export_outbox",
    schema = "integration",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uq_dhis2_outbox_value",
            columnNames = {"run_id", "period_iso", "org_unit_uid",
                "dataelement_uid", "category_option_combo_uid"})
    },
    indexes = {
        @Index(name = "idx_dhis2_outbox_run", columnList = "run_id"),
        @Index(name = "idx_dhis2_outbox_pending", columnList = "run_id, status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = "run")
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Dhis2ExportOutbox extends BaseEntity {

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_dhis2_outbox_run"))
    private Dhis2ExportRun run;

    @NotBlank
    @Size(max = 16)
    @Column(name = "period_iso", nullable = false, length = 16)
    private String periodIso;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 organisation-unit UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "org_unit_uid", nullable = false, length = 11)
    private String orgUnitUid;

    @NotBlank
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 dataElement UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "dataelement_uid", nullable = false, length = 11)
    private String dataElementUid;

    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9]{10}$",
        message = "DHIS2 categoryOptionCombo UID must be 11 characters, alphanumeric, leading letter")
    @Column(name = "category_option_combo_uid", length = 11)
    private String categoryOptionComboUid;

    @NotBlank
    @Size(max = 64)
    @Column(name = "data_value", nullable = false, length = 64)
    private String value;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    @Builder.Default
    private Dhis2OutboxStatus status = Dhis2OutboxStatus.PENDING;

    @Column(name = "attempts", nullable = false)
    @Builder.Default
    private int attempts = 0;

    @Size(max = 1024)
    @Column(name = "last_error", length = 1024)
    private String lastError;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;
}
