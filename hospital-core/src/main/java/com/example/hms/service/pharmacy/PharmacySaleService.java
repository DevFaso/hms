package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.PharmacySaleRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacySaleResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

/**
 * P-07: OTC walk-in pharmacy sales (cash transactions not tied to a prescription).
 */
public interface PharmacySaleService {

    PharmacySaleResponseDTO createSale(PharmacySaleRequestDTO dto);

    PharmacySaleResponseDTO getSale(UUID id);

    Page<PharmacySaleResponseDTO> listByHospital(UUID hospitalId, Pageable pageable);

    Page<PharmacySaleResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable);

    Page<PharmacySaleResponseDTO> listByPatient(UUID patientId, Pageable pageable);
}
