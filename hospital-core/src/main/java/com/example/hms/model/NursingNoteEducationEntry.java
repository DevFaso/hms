package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Captures structured patient education details recorded as part of a nursing note.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class NursingNoteEducationEntry {

    @Size(max = 150)
    @Column(name = "topic", length = 150)
    private String topic;

    @Size(max = 120)
    @Column(name = "teaching_method", length = 120)
    private String teachingMethod;

    @Size(max = 400)
    @Column(name = "patient_understanding", length = 400)
    private String patientUnderstanding;

    @Size(max = 400)
    @Column(name = "reinforcement_actions", length = 400)
    private String reinforcementActions;

    @Column(name = "education_summary", columnDefinition = "TEXT")
    private String educationSummary;
}
