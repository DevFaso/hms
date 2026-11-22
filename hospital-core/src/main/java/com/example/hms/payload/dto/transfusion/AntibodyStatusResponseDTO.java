package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AntibodyWorkupStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** The patient's allo-immunisation at this hospital: the permanent flag and what the biologist recorded. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AntibodyStatusResponseDTO {

    private UUID patientId;
    private String patientName;
    /** A positive antibody screen, or a biologist's record, is on file here. Never cleared by a later screen. */
    private boolean alloImmunised;
    /** When the newest positive screen on record here was performed; null when the flag rests on a work-up only. */
    private LocalDateTime positiveScreenAt;
    /** The antibody is identified: routine red cells may be made compatible for this patient. */
    private boolean identified;
    private String antibodySpecificities;
    private List<Workup> workups;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Workup {
        private UUID id;
        private AntibodyWorkupStatus status;
        private String antibodySpecificities;
        private String referralCentre;
        private String decisionReason;
        private UUID transfusionRequestId;
        private String recordedByDisplay;
        private LocalDateTime recordedAt;
        private String notes;
    }
}
