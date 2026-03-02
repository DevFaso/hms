package com.example.hms.service;

import com.example.hms.payload.dto.PatientRecordDTO;
import com.example.hms.payload.dto.RecordShareResultDTO;

import java.util.UUID;

public interface PatientRecordSharingService {
    PatientRecordDTO getPatientRecord(UUID patientId, UUID fromHospitalId, UUID toHospitalId);
    byte[] exportPatientRecord(UUID patientId, UUID fromHospitalId, UUID toHospitalId, String format);

    /**
     * Smart resolver: automatically determines the most-appropriate source hospital
     * (SAME_HOSPITAL → INTRA_ORG → CROSS_ORG) and returns the record together with
     * full provenance metadata about how the share was authorised.
     *
     * @param patientId            patient whose records are requested
     * @param requestingHospitalId hospital that wants to read the records
     * @return rich result envelope with scope metadata + full patient record
     */
    RecordShareResultDTO resolveAndShare(UUID patientId, UUID requestingHospitalId);
}

