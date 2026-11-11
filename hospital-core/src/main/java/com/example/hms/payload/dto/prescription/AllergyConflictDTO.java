package com.example.hms.payload.dto.prescription;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.enums.AllergyReviewDecision;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Policy 4: the allergy conflict a signed order meets now, as the prescriber reviews it, and the review
 * on record. {@code reviewCurrent} is true only while the recorded review answers this very conflict.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AllergyConflictDTO {

    private boolean conflict;
    private String allergen;
    private List<CdsCard> cards;
    /** What the review sends back as {@code allergyToken}. Null when there is no conflict. */
    private String reviewToken;

    private AllergyReviewDecision decision;
    private String plan;
    private String rationale;
    private String reviewedByName;
    private LocalDateTime reviewedAt;
    private boolean reviewCurrent;
}
