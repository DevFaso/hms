package com.example.hms.mapper.pharmacy;

import com.example.hms.model.pharmacy.MtmReview;
import com.example.hms.payload.dto.pharmacy.MtmReviewResponseDTO;
import org.springframework.stereotype.Component;

/**
 * P-09: MTM review mapper. Hand-written for parity with PharmacySaleMapper.
 */
@Component
public class MtmReviewMapper {

    public MtmReviewResponseDTO toResponseDTO(MtmReview review) {
        if (review == null) {
            return null;
        }
        return MtmReviewResponseDTO.builder()
                .id(review.getId())
                .patientId(review.getPatient() != null ? review.getPatient().getId() : null)
                .hospitalId(review.getHospital() != null ? review.getHospital().getId() : null)
                .pharmacistUserId(review.getPharmacistUser() != null ? review.getPharmacistUser().getId() : null)
                .reviewDate(review.getReviewDate())
                .chronicConditionFocus(review.getChronicConditionFocus())
                .adherenceConcern(review.isAdherenceConcern())
                .polypharmacyAlert(review.isPolypharmacyAlert())
                .interventionSummary(review.getInterventionSummary())
                .recommendedActions(review.getRecommendedActions())
                .status(review.getStatus() != null ? review.getStatus().name() : null)
                .followUpDate(review.getFollowUpDate())
                .createdAt(review.getCreatedAt())
                .updatedAt(review.getUpdatedAt())
                .build();
    }
}
