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
