package com.example.hms.service.pharmacy;

import com.example.hms.payload.dto.pharmacy.DispenseRequestDTO;
import com.example.hms.payload.dto.pharmacy.DispenseResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface DispenseService {

    DispenseResponseDTO createDispense(DispenseRequestDTO dto);

    DispenseResponseDTO getDispense(UUID id);

    Page<DispenseResponseDTO> listByPrescription(UUID prescriptionId, Pageable pageable);

    Page<DispenseResponseDTO> listByPatient(UUID patientId, Pageable pageable);

    Page<DispenseResponseDTO> listByPharmacy(UUID pharmacyId, Pageable pageable);

    DispenseResponseDTO cancelDispense(UUID id);
}
