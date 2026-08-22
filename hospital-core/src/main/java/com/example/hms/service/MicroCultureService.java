package com.example.hms.service;

import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.payload.dto.MicroCultureRequestDTO;
import com.example.hms.payload.dto.MicroCultureResponseDTO;
import com.example.hms.payload.dto.MicroCultureUpdateDTO;
import com.example.hms.payload.dto.MicroIsolateRequestDTO;
import com.example.hms.payload.dto.MicroSusceptibilityRequestDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

/**
 * Microbiology culture reports: cultures, isolates, susceptibilities
 * (P3 #19). Every mutation returns the full report so the caller re-renders
 * in one round trip.
 */
public interface MicroCultureService {

    MicroCultureResponseDTO createCulture(UUID hospitalId, UUID actorUserId, MicroCultureRequestDTO request);

    MicroCultureResponseDTO getCulture(UUID cultureId, UUID hospitalId);

    List<MicroCultureResponseDTO> getForPatient(UUID patientId, UUID hospitalId);

    Page<MicroCultureResponseDTO> getForHospital(UUID hospitalId, MicroCultureStatus status, Pageable pageable);

    MicroCultureResponseDTO updateCulture(UUID cultureId, UUID hospitalId, MicroCultureUpdateDTO request);

    MicroCultureResponseDTO finalizeCulture(UUID cultureId, UUID hospitalId, UUID actorUserId);

    MicroCultureResponseDTO addIsolate(UUID cultureId, UUID hospitalId, MicroIsolateRequestDTO request);

    MicroCultureResponseDTO updateIsolate(UUID cultureId, UUID isolateId, UUID hospitalId, MicroIsolateRequestDTO request);

    MicroCultureResponseDTO deleteIsolate(UUID cultureId, UUID isolateId, UUID hospitalId, String correctionReason);

    MicroCultureResponseDTO addSusceptibility(UUID cultureId, UUID isolateId, UUID hospitalId,
                                              MicroSusceptibilityRequestDTO request);

    MicroCultureResponseDTO deleteSusceptibility(UUID cultureId, UUID isolateId, UUID susceptibilityId,
                                                 UUID hospitalId, String correctionReason);
}
