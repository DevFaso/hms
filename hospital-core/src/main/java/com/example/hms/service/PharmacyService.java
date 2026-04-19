package com.example.hms.service;

import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PharmacyService {

    PharmacyResponseDTO create(PharmacyRequestDTO dto);

    PharmacyResponseDTO getById(UUID id, UUID hospitalId);

    Page<PharmacyResponseDTO> listByHospital(UUID hospitalId, Pageable pageable);

    Page<PharmacyResponseDTO> search(UUID hospitalId, String query, Pageable pageable);

    PharmacyResponseDTO update(UUID id, PharmacyRequestDTO dto);

    void deactivate(UUID id, UUID hospitalId);
}
