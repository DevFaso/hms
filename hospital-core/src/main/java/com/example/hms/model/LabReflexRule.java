package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(
    name = "reflex_rules",
    schema = "lab",
    indexes = {
        @Index(name = "idx_reflex_rules_trigger", columnList = "trigger_test_id"),
        @Index(name = "idx_reflex_rules_active",  columnList = "active")
    }
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
@ToString(exclude = {"triggerTestDefinition", "reflexTestDefinition"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class LabReflexRule extends BaseEntity {

    /**
     * The test whose result triggers evaluation of this rule.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "trigger_test_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_reflex_rule_trigger_test"))
    private LabTestDefinition triggerTestDefinition;

    /**
     * JSON condition controlling when this rule fires.
     * Examples:
     * <pre>{"severityFlag":"ABNORMAL"}</pre>
     * <pre>{"thresholdOperator":"GT","thresholdValue":11.0}</pre>
     */
    @NotBlank
    @Column(name = "condition", nullable = false, columnDefinition = "TEXT")
    private String condition;

    /**
     * The test that will be auto-ordered when this rule fires.
     */
    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reflex_test_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_reflex_rule_reflex_test"))
    private LabTestDefinition reflexTestDefinition;

    @Column(name = "active", nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "description", length = 512)
    private String description;
}
