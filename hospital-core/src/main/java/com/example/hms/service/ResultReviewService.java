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
     *
     * <p>Scoped to {@code hospitalId}. A clinician has one {@code Staff} row for
     * the whole platform but can own orders at every hospital they are assigned
     * to, so an unscoped read of "this staff id's orders" is that clinician's
     * cross-tenant order history, and every patient on it, with no acting
     * hospital to account the disclosure against.
     *
     * @param hospitalId the caller's resolved hospital scope, from the SAME
     *        resolution the rest of the controller uses. Resolving it again
     *        inside the service would let this read and the patient snapshot on
     *        the same page disagree about which hospital the caller is at.
     * @throws com.example.hms.exception.ResourceNotFoundException (404) when the
     *         caller has a staff row and {@code hospitalId} is {@code null} — which
     *         the controller's resolution reaches only when neither an
     *         {@code X-Hospital-Id} nor any active assignment resolves, NOT merely
     *         because the caller is a super-admin in global view
     */
    List<DoctorResultQueueItemDTO> getResultReviewQueue(UUID userId, UUID hospitalId);

    /**
     * Return categorized clinical inbox items with item-level detail.
     */
    List<ClinicalInboxItemDTO> getInboxItems(UUID userId);
}
