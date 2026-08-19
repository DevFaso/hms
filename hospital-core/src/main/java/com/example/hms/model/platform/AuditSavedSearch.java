package com.example.hms.model.platform;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Server-side persisted audit-search saved query (MVP-c batch — MVP-8c).
 *
 * <p>Promotes MVP-8b's per-operator localStorage saved searches to a
 * shared store so:
 * <ol>
 *   <li>An operator sees their saved searches across devices.
 *   <li>A super admin can mark a search {@code shared = true} and have
 *       peers see it on their list.
 * </ol>
 *
 * <p>The {@code filterJson} blob is the AuditSearchFilter shape;
 * round-tripped client → server → client without inspection so the
 * filter can evolve without a schema migration. V87 enforces a unique
 * (owner_username, name) constraint so the same operator cannot have
 * two searches with the same name.
 */
@Entity
@Table(name = "audit_saved_search", schema = "platform")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class AuditSavedSearch extends BaseEntity {

    @NotBlank
    @Size(max = 255)
    @Column(name = "owner_username", nullable = false, length = 255)
    private String ownerUsername;

    @NotBlank
    @Size(max = 255)
    @Column(name = "name", nullable = false, length = 255)
    private String name;

    /**
     * Opaque AuditSearchFilter payload as a JSON object string.
     * Stored as jsonb so the future MVP-8c.2 can do server-side
     * inspection (e.g. find shared searches that filter on a given
     * org) without a separate denormalised column.
     */
    @NotBlank
    @Column(name = "filter_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private String filterJson;

    @Column(name = "shared", nullable = false)
    @Builder.Default
    private boolean shared = false;
}
