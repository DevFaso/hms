package com.example.hms.service;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.ImagingOrderStatus;
import com.example.hms.payload.dto.imaging.ImagingOrderDuplicateMatchDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderResponseDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderSignatureRequestDTO;
import com.example.hms.payload.dto.imaging.ImagingOrderStatusUpdateRequestDTO;

import java.util.List;
import java.util.UUID;

public interface ImagingOrderService {

    ImagingOrderResponseDTO createOrder(ImagingOrderRequestDTO request, UUID orderingUserId);

    ImagingOrderResponseDTO updateOrder(UUID orderId, ImagingOrderRequestDTO request, UUID callerUserId);

    ImagingOrderResponseDTO updateOrderStatus(UUID orderId, ImagingOrderStatusUpdateRequestDTO request);

    ImagingOrderResponseDTO captureProviderSignature(UUID orderId, ImagingOrderSignatureRequestDTO request, UUID signerUserId);

    /** Provisional rule 1: records the pre-authorisation number, clearing a pending flag and its task. */
    ImagingOrderResponseDTO recordAuthorization(UUID orderId,
                                                com.example.hms.payload.dto.imaging.ImagingOrderAuthorizationRequestDTO request);

    /**
     * Insurer claims plan PR 1 (QA D3984): the desk or billing records the pre-authorisation number of an order of
     * the acting hospital. The answer carries no clinical field; the number reaches the order's invoice lines.
     */
    com.example.hms.payload.dto.imaging.ImagingPreAuthorisationDeskDTO recordAuthorizationAtDesk(UUID orderId,
                                                                                               String number);

    /**
     * Insurer claims plan PR 1 (QA D3984): a number recorded on an invoice line that bills this order, at
     * {@code hospitalId}, written on the order too (the caller has checked who may). False when the order is not
     * found there or is cancelled without a study done: the line keeps the number either way.
     */
    boolean recordAuthorizationFromInvoiceLine(UUID orderId, UUID hospitalId, String number);

    /** Policy rule 3 (provisional): the patient is checked in at the imaging desk; recorded once. */
    ImagingOrderResponseDTO checkInPatient(UUID orderId);

    /** Policy rule 3 (provisional): how the exam went (technical note, contrast, dose), on the order. */
    ImagingOrderResponseDTO recordExam(UUID orderId,
                                       com.example.hms.payload.dto.imaging.ImagingExamRecordRequestDTO request);

    ImagingOrderResponseDTO getOrder(UUID orderId);

    List<ImagingOrderResponseDTO> getOrdersByPatient(UUID patientId, ImagingOrderStatus status);

    List<ImagingOrderResponseDTO> getOrdersByHospital(UUID hospitalId, ImagingOrderStatus status);

    List<ImagingOrderResponseDTO> getAllOrders(ImagingOrderStatus status);

    /**
     * The possible duplicates of an order about to be placed: this patient's
     * orders at the caller's hospital only ({@code hospitalId} is honoured for a
     * super-admin in global view, and must match the caller's hospital
     * otherwise), inside a window the server caps (QA D1920).
     */
    List<ImagingOrderDuplicateMatchDTO> previewDuplicates(UUID patientId, UUID hospitalId, ImagingModality modality, String bodyRegion, Integer lookbackDays);
}
