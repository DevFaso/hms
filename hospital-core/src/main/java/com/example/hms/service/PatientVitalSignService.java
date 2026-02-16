package com.example.hms.service;

import com.example.hms.payload.dto.PatientResponseDTO;
import com.example.hms.payload.dto.PatientVitalSignRequestDTO;
import com.example.hms.payload.dto.PatientVitalSignResponseDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PatientVitalSignService {

    PatientVitalSignResponseDTO recordVital(UUID patientId,
                                            PatientVitalSignRequestDTO request,
                                            UUID recorderUserId);

    List<PatientVitalSignResponseDTO> getRecentVitals(UUID patientId,
                                                      UUID hospitalId,
                                                      int limit);

    List<PatientVitalSignResponseDTO> getVitals(UUID patientId,
                                                UUID hospitalId,
                                                LocalDateTime from,
                                                LocalDateTime to,
                                                int page,
                                                int size);

    Optional<PatientResponseDTO.VitalSnapshot> getLatestSnapshot(UUID patientId,
                                                                 UUID hospitalId);
}
