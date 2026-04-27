package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.MtmReviewRequestDTO;
import com.example.hms.payload.dto.pharmacy.MtmReviewResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * P-09: Pharmacist-led Medication Therapy Management review.
 */
public interface MtmReviewService {

    MtmReviewResponseDTO startReview(MtmReviewRequestDTO dto);

    MtmReviewResponseDTO updateReview(UUID id, MtmReviewRequestDTO dto);

    MtmReviewResponseDTO getReview(UUID id);

    Page<MtmReviewResponseDTO> listByHospital(UUID hospitalId, Pageable pageable);

    Page<MtmReviewResponseDTO> listByPatient(UUID patientId, Pageable pageable);
}
