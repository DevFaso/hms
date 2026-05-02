package com.example.hms.model;

import com.example.hms.enums.SmartPhraseScope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
 * SmartPhrase / dot-phrase macro — shorthand triggers like {@code .normexam}
 * that expand into a multi-line clinical block when typed in an EncounterNote
 * section.
 *
 * <p>Pairs with the per-section EncounterNote form (item 5). Scopes are
 * GLOBAL (system library, no owner / no hospital), HOSPITAL (visible to every
 * clinician at the hospital), and USER (private to the owner).
 *
 * <p>The {@code trigger} is normalised to lowercase and always starts with a
 * dot, e.g. {@code .normros} or {@code .htn-followup}. Uniqueness is enforced
 * by a partial unique index in the migration so the same trigger can exist at
 * different scopes without colliding.
 */
@Entity
@Table(
    name = "smart_phrases",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_smartphrase_trigger", columnList = "phrase_trigger"),
        @Index(name = "idx_smartphrase_scope_hospital", columnList = "scope,hospital_id"),
        @Index(name = "idx_smartphrase_owner", columnList = "owner_user_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"hospital", "owner"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class SmartPhrase extends BaseEntity {

    /** Lowercase trigger, must start with a dot — e.g. {@code .normexam}. */
    @NotBlank
    @Size(max = 64)
    @Pattern(regexp = "\\.[a-z0-9][a-z0-9_-]{0,62}",
        message = "trigger must start with '.' and use lowercase alphanumerics, dash or underscore")
    @Column(name = "phrase_trigger", nullable = false, length = 64)
    private String trigger;

    @NotBlank
    @Size(max = 200)
    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @NotBlank
    @Column(name = "expansion", nullable = false, columnDefinition = "TEXT")
    private String expansion;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 12)
    private SmartPhraseScope scope;

    /** Required when scope = HOSPITAL or USER (when the user is staff at one hospital). */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "hospital_id",
        foreignKey = @ForeignKey(name = "fk_smartphrase_hospital"))
    private Hospital hospital;

    /** Required when scope = USER. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_user_id",
        foreignKey = @ForeignKey(name = "fk_smartphrase_owner"))
    private User owner;

    @Size(max = 64)
    @Column(name = "specialty", length = 64)
    private String specialty;

    @Column(name = "usage_count", nullable = false)
    @Builder.Default
    private long usageCount = 0L;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @PrePersist
    @PreUpdate
    private void normalize() {
        if (trigger != null) {
            trigger = trigger.trim().toLowerCase();
        }
        if (title != null) {
            title = title.trim();
        }
        if (specialty != null) {
            specialty = specialty.trim();
            if (specialty.isEmpty()) {
                specialty = null;
            }
        }
        if (scope == SmartPhraseScope.GLOBAL && (hospital != null || owner != null)) {
            throw new IllegalStateException(
                "GLOBAL SmartPhrase must not carry a hospital or owner");
        }
        if (scope == SmartPhraseScope.HOSPITAL && hospital == null) {
            throw new IllegalStateException("HOSPITAL SmartPhrase requires a hospital");
        }
        if (scope == SmartPhraseScope.USER && owner == null) {
            throw new IllegalStateException("USER SmartPhrase requires an owner");
        }
    }
}
