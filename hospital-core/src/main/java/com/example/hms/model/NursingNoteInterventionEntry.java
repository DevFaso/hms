package com.example.hms.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Documents a specific nursing intervention associated with a structured note.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class NursingNoteInterventionEntry {

    @Size(max = 400)
    @Column(name = "description", length = 400)
    private String description;

    @Column(name = "linked_order_id")
    private UUID linkedOrderId;

    @Column(name = "linked_medication_task_id")
    private UUID linkedMedicationTaskId;

    @Size(max = 400)
    @Column(name = "follow_up_actions", length = 400)
    private String followUpActions;
}
