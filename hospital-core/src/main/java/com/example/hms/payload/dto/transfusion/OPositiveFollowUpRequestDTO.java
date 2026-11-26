package com.example.hms.payload.dto.transfusion;

import com.example.hms.enums.AntiDDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What a clinician records after an RhD positive exposure: the decision, that the patient was told, or both. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OPositiveFollowUpRequestDTO {

    private AntiDDecision antiDDecision;
    private Boolean patientInformed;
    private String note;
}
