package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.RoutingDecisionRequestDTO;
import com.example.hms.payload.dto.pharmacy.RoutingDecisionResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockCheckResultDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

public interface StockOutRoutingService {

    StockCheckResultDTO checkStock(UUID prescriptionId);

    RoutingDecisionResponseDTO routeToPartner(UUID prescriptionId, RoutingDecisionRequestDTO dto);

    RoutingDecisionResponseDTO printForPatient(UUID prescriptionId);

    RoutingDecisionResponseDTO backOrder(UUID prescriptionId, LocalDate estimatedRestockDate);

    RoutingDecisionResponseDTO partnerRespond(UUID routingDecisionId, boolean accepted);

    RoutingDecisionResponseDTO confirmPartnerDispense(UUID routingDecisionId);

    Page<RoutingDecisionResponseDTO> listByPrescription(UUID prescriptionId, Pageable pageable);

    Page<RoutingDecisionResponseDTO> listByPatient(UUID patientId, Pageable pageable);
}
