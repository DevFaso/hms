package com.example.hms.payload.dto.bed;

import com.example.hms.enums.BedStatus;
import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.enums.WardType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * The ward board (Tier 2 item 31): ward → room → bed, with who is in it.
 *
 * <p>The existing occupancy tiles answer "how many beds are free". They cannot
 * answer "who is in bay 3 and can the next admission go beside them", which is
 * the question the board exists for.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BedBoardDTO {

    private UUID hospitalId;
    private LocalDateTime generatedAt;
    private CensusDTO census;
    private List<WardBoardDTO> wards;

    /**
     * The numbers the admin dashboard can trust.
     *
     * <p>{@code inpatientCount} is counted from ADMISSIONS holding a bed, not
     * from beds marked OCCUPIED. The two should agree, and
     * {@code orphanedOccupiedBeds} reports it when they do not rather than
     * quietly picking one — a bed left OCCUPIED by a failed discharge is a bed
     * nobody can allocate, and hiding it is how it stays lost.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CensusDTO {
        private long totalBeds;
        private long occupiedBeds;
        private long availableBeds;
        private long reservedBeds;
        private long outOfServiceBeds;
        private BigDecimal occupancyRate;

        private long inpatientCount;
        private long orphanedOccupiedBeds;
        private long expectedDischargesToday;
        private long patientsOnIsolation;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WardBoardDTO {
        private UUID wardId;
        private String wardName;
        private String wardCode;
        private WardType wardType;
        private Integer floor;

        private long totalBeds;
        private long occupiedBeds;
        private long availableBeds;
        private BigDecimal occupancyRate;

        /** True when the ward can hold an airborne case. */
        private boolean isolationCapable;

        private List<RoomBoardDTO> rooms;
    }

    /** Beds with no room number are grouped under a null room rather than dropped. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RoomBoardDTO {
        private String roomNumber;
        private List<BedBoardEntryDTO> beds;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BedBoardEntryDTO {
        private UUID bedId;
        private String bedNumber;
        private String bedType;
        private BedStatus status;
        private String notes;

        /** Null when the bed is not occupied. */
        private OccupantDTO occupant;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OccupantDTO {
        private UUID admissionId;
        private UUID patientId;
        private String patientName;
        private String mrn;
        private LocalDateTime admittedAt;
        private LocalDateTime expectedDischargeAt;
        private Integer lengthOfStayDays;
        private String attendingPhysicianName;
        private String primaryDiagnosis;

        /** Every precaution in force, because concurrent ones are normal. */
        private List<IsolationPrecautionType> isolationPrecautions;

        /** True when any active precaution constrains placement (airborne). */
        private boolean requiresIsolationWard;

        /**
         * True when the patient needs an isolation ward and is not in one.
         * Surfaced rather than merely computed: this is the board's whole
         * reason for showing precautions.
         */
        private boolean isolationMismatch;
    }
}
