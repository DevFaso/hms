package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.payload.dto.EncounterNoteAddendumRequestDTO;
import com.example.hms.payload.dto.EncounterNoteAddendumResponseDTO;
import com.example.hms.payload.dto.EncounterNoteHistoryResponseDTO;
import com.example.hms.payload.dto.EncounterNoteRequestDTO;
import com.example.hms.payload.dto.EncounterNoteResponseDTO;
import com.example.hms.payload.dto.EncounterRequestDTO;
import com.example.hms.payload.dto.EncounterResponseDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@SuppressWarnings("java:S107") // list() method uses individual filter parameters for controller binding
public interface EncounterService {

    EncounterResponseDTO createEncounter(EncounterRequestDTO request, Locale locale);

    EncounterResponseDTO getEncounterById(UUID id, Locale locale);
    List<EncounterResponseDTO> getEncountersByDoctorIdentifier(String identifier, Locale locale);

    Page<EncounterResponseDTO> list(UUID patientId,
                                    UUID staffId,
                                    UUID hospitalId,
                                    LocalDateTime from,
                                    LocalDateTime to,
                                    EncounterStatus status,
                                    Pageable pageable,
                                    Locale locale);

    EncounterResponseDTO updateEncounter(UUID id, EncounterRequestDTO request, Locale locale);

    void deleteEncounter(UUID id, Locale locale);

    // Legacy list endpoints (kept for compatibility)
    List<EncounterResponseDTO> getEncountersByPatientId(UUID patientId, Locale locale);
    List<EncounterResponseDTO> getEncountersByPatientIdentifier(String identifier, Locale locale);
    List<EncounterResponseDTO> getEncountersByDoctorId(UUID staffId, Locale locale);

    EncounterNoteResponseDTO upsertEncounterNote(UUID encounterId, EncounterNoteRequestDTO request, Locale locale);

    EncounterNoteAddendumResponseDTO addEncounterNoteAddendum(UUID encounterId,
                                                              EncounterNoteAddendumRequestDTO request,
                                                              Locale locale);

    java.util.List<EncounterNoteHistoryResponseDTO> getEncounterNoteHistory(UUID encounterId, Locale locale);
}
