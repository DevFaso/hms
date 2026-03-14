package com.example.hms.service;

import com.example.hms.payload.dto.clinical.ClinicalInboxItemDTO;
import com.example.hms.payload.dto.clinical.DoctorResultQueueItemDTO;

import java.util.List;
import java.util.UUID;

/**
 * Service for the results review queue and clinical inbox item-level data.
 */
public interface ResultReviewService {

    /**
     * Return lab/imaging results ordered by this physician, grouped by severity.
     */
    List<DoctorResultQueueItemDTO> getResultReviewQueue(UUID userId);

    /**
     * Return categorized clinical inbox items with item-level detail.
     */
    List<ClinicalInboxItemDTO> getInboxItems(UUID userId);
}
