package com.example.hms.service;

import com.example.hms.payload.dto.*;
import java.util.*;

public interface PatientPrimaryCareService {

    PatientPrimaryCareResponseDTO assignPrimaryCare(UUID patientId, PatientPrimaryCareRequestDTO request);

    PatientPrimaryCareResponseDTO updatePrimaryCare(UUID pcpId, PatientPrimaryCareRequestDTO request);

    PatientPrimaryCareResponseDTO endPrimaryCare(UUID pcpId, java.time.LocalDate endDate);

    Optional<PatientPrimaryCareResponseDTO> getCurrentPrimaryCare(UUID patientId);

    List<PatientPrimaryCareResponseDTO> getPrimaryCareHistory(UUID patientId);

    void deletePrimaryCare(UUID pcpId);
}
