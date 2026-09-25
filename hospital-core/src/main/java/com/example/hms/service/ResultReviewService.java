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
     * <p>Scoped to the hospital the caller is acting at. A clinician has one
     * {@code Staff} row for the whole platform but can own orders at every
     * hospital they are assigned to, so an unscoped read of "this staff id's
     * orders" is that clinician's cross-tenant order history, and every patient
     * on it, with no acting hospital to account the disclosure against.
     *
     * @throws com.example.hms.exception.ResourceNotFoundException (404) when the
     *         caller has a staff row but no hospital scope resolves — a
     *         super-admin in global view
     * @throws com.example.hms.exception.BusinessException when no hospital can
     *         be determined for a non-super-admin
     *         ({@code RoleValidator.requireActiveHospitalId})
     */
    List<DoctorResultQueueItemDTO> getResultReviewQueue(UUID userId);

    /**
     * Return categorized clinical inbox items with item-level detail.
     */
    List<ClinicalInboxItemDTO> getInboxItems(UUID userId);
}
