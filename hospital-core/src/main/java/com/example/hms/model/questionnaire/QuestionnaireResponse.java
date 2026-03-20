package com.example.hms.model.questionnaire;

import com.example.hms.enums.QuestionnaireStatus;
import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A patient's submitted response to a {@link PreVisitQuestionnaire}.
 * <p>
 * Answers are stored as a JSON object keyed by question id:
 * <pre>
 * {"q1":"No known allergies","q2":"yes","q3":"3"}
 * </pre>
 */
@Entity
@Table(
    name = "questionnaire_responses",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_qr_patient",        columnList = "patient_id"),
        @Index(name = "idx_qr_questionnaire",  columnList = "questionnaire_id"),
        @Index(name = "idx_qr_appointment",    columnList = "appointment_id"),
        @Index(name = "idx_qr_status",         columnList = "status")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QuestionnaireResponse extends BaseEntity {

    @Column(name = "patient_id", nullable = false)
    private UUID patientId;

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    @Column(name = "questionnaire_id", nullable = false)
    private UUID questionnaireId;

    /** Optional — links the response to a specific upcoming appointment. */
    @Column(name = "appointment_id")
    private UUID appointmentId;

    /**
     * JSON object mapping question id → answer string.
     * Stored as TEXT for simplicity; parsed at the application layer when needed.
     */
    @Column(name = "answers_json", nullable = false, columnDefinition = "TEXT")
    private String answersJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private QuestionnaireStatus status = QuestionnaireStatus.SUBMITTED;

    @Column(name = "submitted_at")
    private LocalDateTime submittedAt;

    /** Title snapshot — copied from the template at submit time for read-only display. */
    @Column(name = "questionnaire_title", nullable = false, length = 255)
    private String questionnaireTitle;
}
