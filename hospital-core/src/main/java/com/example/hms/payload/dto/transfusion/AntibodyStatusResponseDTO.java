package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AntibodyWorkupStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The patient's allo-immunisation: the permanent flag and what the biologists recorded, at every hospital (the
 * flag follows the patient). A record of another hospital carries the fact, the antibody, the date and that
 * hospital's name, and nothing else of its text.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AntibodyStatusResponseDTO {

    private UUID patientId;
    private String patientName;
    /** A positive antibody screen, or a biologist's record, is on file, here or elsewhere. Never cleared. */
    private boolean alloImmunised;
    /** When the newest positive screen on record was performed; null when the flag rests on a work-up only. */
    private LocalDateTime positiveScreenAt;
    /** The hospital that recorded that positive screen. */
    private String positiveScreenHospitalName;
    /** The hospital whose biologist identified the antibody shown in {@link #antibodySpecificities}. */
    private String identifiedHospitalName;
    /** When that identification was recorded. */
    private LocalDateTime identifiedAt;
    /** The other hospitals this safety information comes from; empty when it is all on record here. */
    private List<String> otherHospitalNames;
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
        /** The hospital that made this record. */
        private String hospitalName;
        /**
         * Made at another hospital: an identification only, with the antibody, the date and the hospital's name.
         * Another hospital's referral decision is not listed at all: here it is "a positive screen, not identified".
         */
        private boolean otherHospital;
    }
}
