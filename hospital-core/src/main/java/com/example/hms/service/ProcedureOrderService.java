package com.example.hms.service;

import com.example.hms.enums.ProcedureOrderStatus;
import com.example.hms.payload.dto.procedure.ProcedureOrderRequestDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderResponseDTO;
import com.example.hms.payload.dto.procedure.ProcedureOrderUpdateDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ProcedureOrderService {

    ProcedureOrderResponseDTO createProcedureOrder(ProcedureOrderRequestDTO request, UUID orderingProviderId);

    ProcedureOrderResponseDTO getProcedureOrder(UUID orderId);

    List<ProcedureOrderResponseDTO> getProcedureOrdersForPatient(UUID patientId);

    List<ProcedureOrderResponseDTO> getProcedureOrdersForHospital(UUID hospitalId, ProcedureOrderStatus status);

    List<ProcedureOrderResponseDTO> getProcedureOrdersOrderedBy(UUID providerId);

    List<ProcedureOrderResponseDTO> getProcedureOrdersScheduledBetween(UUID hospitalId, LocalDateTime startDate, LocalDateTime endDate);

    ProcedureOrderResponseDTO updateProcedureOrder(UUID orderId, ProcedureOrderUpdateDTO updateDTO);

    ProcedureOrderResponseDTO cancelProcedureOrder(UUID orderId, String cancellationReason);

    List<ProcedureOrderResponseDTO> getPendingConsentOrders(UUID hospitalId);
}
