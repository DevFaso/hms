package com.example.hms.service;

import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.DoctorPatientChartUpdateRequestDTO;
import com.example.hms.payload.dto.PatientChartUpdateResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PatientChartUpdateService {

    Page<PatientChartUpdateResponseDTO> listPatientChartUpdates(UUID patientId, UUID hospitalId, Pageable pageable);

    PatientChartUpdateResponseDTO getPatientChartUpdate(UUID patientId, UUID hospitalId, UUID updateId);

    PatientChartUpdateResponseDTO createPatientChartUpdate(
        UUID patientId,
        UUID hospitalId,
        UUID requesterUserId,
        UserRoleHospitalAssignment assignment,
        DoctorPatientChartUpdateRequestDTO request
    );
}
