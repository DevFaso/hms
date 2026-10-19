package com.example.hms.service.pharmacy;

import com.example.hms.enums.StockTransactionType;
import com.example.hms.payload.dto.pharmacy.StockTransactionRequestDTO;
import com.example.hms.payload.dto.pharmacy.StockTransactionResponseDTO;
import com.example.hms.payload.dto.pharmacy.StockWitnessCandidateDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface StockTransactionService {

    StockTransactionResponseDTO recordTransaction(StockTransactionRequestDTO dto);

    StockTransactionResponseDTO getTransaction(UUID id);

    /** QA D157: who in the active hospital may witness a controlled-substance movement. */
    List<StockWitnessCandidateDTO> listWitnessCandidates();

    Page<StockTransactionResponseDTO> listByInventoryItem(UUID inventoryItemId, Pageable pageable);

    Page<StockTransactionResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable);

    Page<StockTransactionResponseDTO> listByPharmacyAndDateRange(
            UUID pharmacyId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<StockTransactionResponseDTO> listByPharmacyAndType(
            UUID pharmacyId, StockTransactionType type, Pageable pageable);
}
