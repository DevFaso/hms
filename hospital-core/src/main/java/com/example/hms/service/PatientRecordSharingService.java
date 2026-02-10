package com.example.hms.service;

import com.example.hms.payload.dto.PatientRecordDTO;

import java.util.UUID;

public interface PatientRecordSharingService {
    PatientRecordDTO getPatientRecord(UUID patientId, UUID fromHospitalId, UUID toHospitalId);
    byte[] exportPatientRecord(UUID patientId, UUID fromHospitalId, UUID toHospitalId, String format);
}

