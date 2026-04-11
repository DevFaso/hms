package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
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

import java.time.LocalDateTime;

@Entity
@Table(
    name = "questionnaire_responses",
    schema = "clinical",
    uniqueConstraints = {
        @UniqueConstraint(name = "uq_qr_questionnaire_appointment",
            columnNames = {"questionnaire_id", "appointment_id"})
    },
    indexes = {
        @Index(name = "idx_qr_patient", columnList = "patient_id"),
        @Index(name = "idx_qr_appointment", columnList = "appointment_id")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@ToString(exclude = {"questionnaire", "patient", "appointment"})
@EqualsAndHashCode(callSuper = true, onlyExplicitlyIncluded = true)
public class QuestionnaireResponse extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "questionnaire_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_qr_questionnaire"))
    private Questionnaire questionnaire;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "patient_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_qr_patient"))
    private Patient patient;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "appointment_id", nullable = false,
        foreignKey = @ForeignKey(name = "fk_qr_appointment"))
    private Appointment appointment;

    /**
     * JSON object mapping question IDs to patient answers, e.g.
     * {"q1": true, "q2": 7, "q3": "No known allergies"}
     */
    @NotNull
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String responses;

    @NotNull
    @Column(name = "submitted_at", nullable = false)
    private LocalDateTime submittedAt;
}
