package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AboGroup;
import com.example.hms.enums.RhFactor;
import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * What the server's emergency rules say for one request, so the form asks for what will be required
 * instead of holding a second copy of the rules. The server still decides on submit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EmergencyReleaseOptionsDTO {

    /** A positive antibody screen on the patient's record: this needs transfusion-service review. */
    private boolean antibodyWarning;
    /** What the laboratory wrote with that screen, when it wrote anything. */
    private String antibodyDetail;
    /**
     * O negative of this product the system shows in stock, when this patient takes O negative first.
     * It can be wrong: O positive stays offered, with a statement that this cannot be obtained in time.
     */
    private List<Unit> negativeStock;

    /** Group O bags that can go out with no crossmatch. */
    private List<Unit> units;
    /** Bags with an incompatible result for this request, each saying whether the action can release it. */
    private List<IncompatibleUnit> incompatibleUnits;

    /** Who can be named as making an O positive decision. */
    private List<StockWitnessCandidateDTO> deciders;
    /** Who can authorise a serologically incompatible release: never the caller. */
    private List<StockWitnessCandidateDTO> authorisers;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Unit {
        private UUID id;
        private String unitNumber;
        private AboGroup aboGroup;
        private RhFactor rhFactor;
        private LocalDate expiresOn;
        private boolean decisionRequired;
        /** Releasing it needs the statement that the O negative shown cannot be obtained in time. */
        private boolean statementRequired;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IncompatibleUnit {
        private UUID id;
        private String unitNumber;
        private AboGroup aboGroup;
        private RhFactor rhFactor;
        private String incompatibilityReason;
        /** False when the patient's group excludes this bag, or it is held for another request: refused either way. */
        private boolean releasable;
        /** Releasing it needs the statement that the O negative shown cannot be obtained in time. */
        private boolean statementRequired;
    }
}
