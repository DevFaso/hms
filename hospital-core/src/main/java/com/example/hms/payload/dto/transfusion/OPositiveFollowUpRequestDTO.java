package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AntiDDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What a clinician records after an RhD positive exposure: the decision, that the patient was told, or both. An
 * empty body is refused at the edge, on the decision.
 */
@com.example.hms.payload.dto.OneOf(fields = {"antiDDecision", "patientInformed", "bagNotGiven", "note"},
    message = "{transfusion.oPositive.followUp.nothingRecorded}")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OPositiveFollowUpRequestDTO {

    private AntiDDecision antiDDecision;
    private Boolean patientInformed;
    /** Asks to close the follow-up of a bag that came back unused; whether it did is the server's to say. */
    private Boolean bagNotGiven;
    private String note;
}
