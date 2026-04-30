package com.example.hms.service;

import com.example.hms.payload.dto.AdmissionOrderSetRequestDTO;
import com.example.hms.payload.dto.AdmissionOrderSetResponseDTO;
import com.example.hms.payload.dto.orderset.ApplyOrderSetRequestDTO;
import com.example.hms.payload.dto.orderset.AppliedOrderSetSummaryDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * CPOE order-set template management + application to admissions.
 *
 * <p>P1 #4 surface. Templates are versioned via {@code parent_order_set_id}
 * — every update freezes the previous active row and creates a new
 * active row pointing at it. Apply runs the items through the existing
 * order services so CDS, validation, and authorisation behave the
 * same as a hand-entered order.
 */
public interface AdmissionOrderSetService {

    /** List active templates scoped to a hospital, with optional name search and pagination. */
    Page<AdmissionOrderSetResponseDTO> list(UUID hospitalId, String search, Pageable pageable);

    /** Single template by id. */
    AdmissionOrderSetResponseDTO getById(UUID id);

    /** Walk the parent chain anchored at the given template id (newest → oldest). */
    List<AdmissionOrderSetResponseDTO> getVersionHistory(UUID id);

    /** Author a brand-new v1 template. */
    AdmissionOrderSetResponseDTO create(AdmissionOrderSetRequestDTO request);

    /**
     * Edit a template. Implemented as freeze-and-replace: the existing
     * row is deactivated and a new active row is created with
     * {@code version = parent.version + 1} and
     * {@code parentOrderSet = parent}.
     */
    AdmissionOrderSetResponseDTO update(UUID id, AdmissionOrderSetRequestDTO request);

    /** Soft-deactivate a template; deactivation reason is required. */
    AdmissionOrderSetResponseDTO deactivate(UUID id, String reason, UUID actingStaffId);

    /**
     * Apply the template to an admission, fanning each {@code orderItem}
     * out into the appropriate order service. Transactional: a critical
     * CDS block on any medication rolls the entire bundle back.
     */
    AppliedOrderSetSummaryDTO applyToAdmission(
        UUID admissionId,
        UUID orderSetId,
        ApplyOrderSetRequestDTO request,
        Locale locale
    );
}
