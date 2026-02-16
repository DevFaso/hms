package com.example.hms.service;

import com.example.hms.payload.dto.PharmacyLocationResponseDTO;

import java.util.List;
import java.util.UUID;

public interface PharmacyDirectoryService {

    List<PharmacyLocationResponseDTO> listPatientPharmacies(UUID patientId, UUID hospitalId);
}
