package com.example.hms.model.questionnaire;

import com.example.hms.model.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * A pre-visit questionnaire template created by hospital staff.
 * <p>
 * Questions are stored as a JSON array in {@code questionsJson}.  Each element
 * has the shape:
 * <pre>
 * {"id":"q1","question":"text…","type":"TEXT|YES_NO|SCALE|CHOICE",
 *  "required":true,"options":["A","B"]}
 * </pre>
 * Patients fill out an associated {@link QuestionnaireResponse} for each
 * template they are asked to complete.
 */
@Entity
@Table(
    name = "pre_visit_questionnaires",
    schema = "clinical",
    indexes = {
        @Index(name = "idx_pvq_hospital",   columnList = "hospital_id"),
        @Index(name = "idx_pvq_department", columnList = "department_id"),
        @Index(name = "idx_pvq_active",     columnList = "active")
    }
)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(callSuper = true)
public class PreVisitQuestionnaire extends BaseEntity {

    @Column(name = "hospital_id", nullable = false)
    private UUID hospitalId;

    /** Optional — if set, questionnaire is scoped to one department. */
    @Column(name = "department_id")
    private UUID departmentId;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(length = 1000)
    private String description;

    /**
     * JSON array of question definitions.  Stored as TEXT to avoid a separate
     * join table while keeping the data inspectable in the DB.
     */
    @Column(name = "questions_json", nullable = false, columnDefinition = "TEXT")
    private String questionsJson;

    /** Only active questionnaires are shown to patients. */
    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;
}
