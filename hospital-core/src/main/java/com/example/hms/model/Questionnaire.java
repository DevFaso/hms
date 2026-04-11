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
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.EqualsAndHashCode;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(
    name = "questionnaires",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_questionnaire_hospital", columnList = "hospital_id"),
        @Index(name = "idx_questionnaire_dept", columnList = "department_id"),
        @Index(name = "idx_questionnaire_active", columnList = "active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"hospital", "department"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class Questionnaire extends BaseEntity {

    @NotBlank
    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 1024)
    private String description;

    /**
     * JSON array of question objects, e.g.
     * [{"id":"q1","text":"Do you have allergies?","type":"YES_NO"},
     *  {"id":"q2","text":"Rate your pain 1-10","type":"SCALE","min":1,"max":10}]
     */
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String questions;

    @NotNull
    @Column(nullable = false)
    @Builder.Default
    private Integer version = 1;

    @NotNull
    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id",
        foreignKey = @ForeignKey(name = "fk_questionnaire_department"))
    private Department department;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "hospital_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_questionnaire_hospital"))
    private Hospital hospital;
}
