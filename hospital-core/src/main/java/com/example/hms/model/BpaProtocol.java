package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Catalog of Best-Practice-Advisory protocols. Rules look up the row by
 * stable {@code protocolCode} and use the localised {@code name} +
 * {@code summary} when rendering their CDS card. Persisting the metadata
 * here (rather than hard-coding card text in Java) keeps editorial
 * changes — links, summary tweaks, retiring a protocol — to a migration
 * instead of a code change.
 *
 * <p>Seeded by V64 with MALARIA_FEVER, SEPSIS_QSOFA, OB_HEMORRHAGE.
 */
@Entity
@Table(
    name = "bpa_protocols",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_bpa_protocols_code", columnNames = "protocol_code")
    },
    indexes = {
        @Index(name = "idx_bpa_protocols_active_code", columnList = "protocol_code")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class BpaProtocol extends BaseEntity {

    @NotBlank
    @Size(max = 60)
    @Column(name = "protocol_code", nullable = false, length = 60)
    private String protocolCode;

    @NotBlank
    @Size(max = 200)
    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @NotBlank
    @Size(max = 500)
    @Column(name = "summary", nullable = false, length = 500)
    private String summary;

    @Size(max = 500)
    @Column(name = "protocol_url", length = 500)
    private String protocolUrl;

    @Builder.Default
    @Column(name = "is_active", nullable = false)
    private boolean active = true;
}
