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
    Page<LabOrderResponseDTO> searchLabOrders(UUID patientId, LocalDateTime fromDate, LocalDateTime toDate, Pageable pageable, Locale locale);

    LabOrderResponseDTO updateLabOrder(UUID id, LabOrderRequestDTO requestDTO, Locale locale);

    void deleteLabOrder(UUID id, Locale locale);


    List<LabOrderResponseDTO> getLabOrdersByPatientId(UUID patientId, Locale locale);

    List<LabOrderResponseDTO> getLabOrdersByStaffId(UUID staffId, Locale locale);

    List<LabOrderResponseDTO> getLabOrdersByLabTestDefinitionId(UUID labTestDefinitionId, Locale locale);

    List<LabOrderResponseDTO> getLabOrdersByStatus(LabOrderStatus status, Locale locale);
}

