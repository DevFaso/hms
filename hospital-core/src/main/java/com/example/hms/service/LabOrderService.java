package com.example.hms.service;

import com.example.hms.enums.LabOrderStatus;
import com.example.hms.payload.dto.LabOrderRequestDTO;
import com.example.hms.payload.dto.LabOrderResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface LabOrderService {

    LabOrderResponseDTO createLabOrder(LabOrderRequestDTO requestDTO, Locale locale);

    LabOrderResponseDTO getLabOrderById(UUID id, Locale locale);

    List<LabOrderResponseDTO> getAllLabOrders(Locale locale);

    /**
     * Lab orders, optionally filtered by patient and order date.
     *
     * <p>Filtering by patient requires a hospital scope. Without one — a real
     * super-admin in global view, the only caller
     * {@code RoleValidator.requireActiveHospitalId()} returns null for — this
     * throws rather than returning that patient's orders from every hospital,
     * because a cross-hospital disclosure has no acting hospital to be recorded
     * against and so cannot be accounted. The unfiltered worklist is not
     * affected: it is a worklist, not somebody's record.
     *
     * @throws com.example.hms.exception.ResourceNotFoundException (404) when
     *         {@code patientId} is given and no hospital scope resolves
     */
    Page<LabOrderResponseDTO> searchLabOrders(UUID patientId, LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable, Locale locale);

    LabOrderResponseDTO updateLabOrder(UUID id, LabOrderRequestDTO requestDTO, Locale locale);

    void deleteLabOrder(UUID id, Locale locale);


    /**
     * One patient's lab orders. Requires a hospital scope, for the reason given
     * on {@link #searchLabOrders}: without one this is a patient's record
     * collected across every tenant with no way to account the disclosure.
     *
     * @throws com.example.hms.exception.ResourceNotFoundException (404) when no
     *         hospital scope resolves
     */
    List<LabOrderResponseDTO> getLabOrdersByPatientId(UUID patientId, Locale locale);

    /**
     * One clinician's lab orders. Requires a hospital scope for the same
     * reason, and it is not obvious why: a {@code Staff} row is pinned to one
     * hospital, but nothing binds the ordering staff to the ORDER's hospital,
     * so one staff id can own orders at several — see the implementation.
     *
     * @throws com.example.hms.exception.ResourceNotFoundException (404) when no
     *         hospital scope resolves
     */
    List<LabOrderResponseDTO> getLabOrdersByStaffId(UUID staffId, Locale locale);

    List<LabOrderResponseDTO> getLabOrdersByLabTestDefinitionId(UUID labTestDefinitionId, Locale locale);

    List<LabOrderResponseDTO> getLabOrdersByStatus(LabOrderStatus status, Locale locale);

    /**
     * Transitions a lab order to the given {@code toStatus}, enforcing
     * role-based transition rules.
     */
    default LabOrderResponseDTO transitionLabOrderStatus(UUID id, LabOrderStatus toStatus, Locale locale) {
        return transitionLabOrderStatus(id, toStatus, null, locale);
    }

    /**
     * QA D673: as above, with the reason a cancellation requires. A cancel
     * without a non-blank reason is refused with
     * {@code lab.order.cancelReasonRequired}; the reason is ignored otherwise.
     */
    LabOrderResponseDTO transitionLabOrderStatus(UUID id, LabOrderStatus toStatus, String reason, Locale locale);

    /**
     * QA D820: marks the order, and with it every result on it, as entered in
     * error, from any state including COMPLETED. The reason is required and
     * kept on the order; the rows are never deleted, and the results stop
     * loading anywhere. An ordering-side decision: the lab manager or lab
     * director of the ordering hospital, or a super-admin. Refused once the
     * order is already voided, and for a cancelled order (already closed, its
     * reason column holding why it was cancelled).
     */
    LabOrderResponseDTO voidLabOrder(UUID id, String reason, Locale locale);

    /** B1: the laboratories the caller may route an order to — every active hospital but the acting one. */
    List<com.example.hms.payload.dto.PerformingLabOptionDTO> listPerformingLabs();
}

