package com.example.hms.payload.dto;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
// Provide ONE of each group: (patientId | patientIdentifier), (staffId | staffIdentifier | staffEmail), (hospitalId | hospitalIdentifier)
@OneOf(fields = {"patientId", "patientIdentifier"}, message = "Provide either patientId or patientIdentifier.")
@OneOf(fields = {"staffId", "staffIdentifier", "staffEmail"}, message = "Provide staffId, staffIdentifier, or staffEmail.")
@OneOf(fields = {"hospitalId", "hospitalIdentifier"}, message = "Provide hospitalId or hospitalIdentifier (receptionists will be scoped by JWT).")

public class EncounterRequestDTO {

    /** Optional for update flows */
    private UUID id;

    /** Patient resolution */
    private UUID patientId;
    private String patientIdentifier;      // MRN/username/email/etc.

    /** Staff resolution */
    private UUID staffId;
    private String staffIdentifier;        // username/license/code
    @Email
    private String staffEmail;             // optional; if present must be a valid email

    /** Hospital resolution (receptionist JWT overrides anyway) */
    private UUID hospitalId;
    private String hospitalIdentifier;     // name/code if you support it

    /** Optional appointment binding */
    private UUID appointmentId;

    /** Required: encounter type is a domain enum */
    @NotNull
    private EncounterType encounterType;

    /** Optional: defaults to now() in controller/service if null */
    @PastOrPresent(message = "Encounter date cannot be in the future")
    private LocalDateTime encounterDate;

    /** Optional notes */
    @Size(max = 2000, message = "Notes must be at most 2000 characters")
    private String notes;

    /** Department is usually required for check-in/queueing */
    @NotNull
    private UUID departmentId;
    private String departmentIdentifier;   // alternate lookup if you support it

    /** Audit fields (usually set server-side) */
    private String createdBy;
    private String updatedBy;

    /** Optional: if null, controller/service should default to ARRIVED for reception check-ins */
    private EncounterStatus status;

    /** Extensibility */
    private Map<String, Object> extraFields;

    /** Optional structured note payload */
    private EncounterNoteRequestDTO note;
}
