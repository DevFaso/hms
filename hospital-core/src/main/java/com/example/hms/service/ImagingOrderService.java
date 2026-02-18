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

    ImagingOrderResponseDTO updateOrder(UUID orderId, ImagingOrderRequestDTO request);

    ImagingOrderResponseDTO updateOrderStatus(UUID orderId, ImagingOrderStatusUpdateRequestDTO request);

    ImagingOrderResponseDTO captureProviderSignature(UUID orderId, ImagingOrderSignatureRequestDTO request);

    ImagingOrderResponseDTO getOrder(UUID orderId);

    List<ImagingOrderResponseDTO> getOrdersByPatient(UUID patientId, ImagingOrderStatus status);

    List<ImagingOrderResponseDTO> getOrdersByHospital(UUID hospitalId, ImagingOrderStatus status);

    List<ImagingOrderResponseDTO> getAllOrders(ImagingOrderStatus status);

    List<ImagingOrderDuplicateMatchDTO> previewDuplicates(UUID patientId, ImagingModality modality, String bodyRegion, Integer lookbackDays);
}
