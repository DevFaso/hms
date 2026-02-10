package com.example.hms.service;

import com.example.hms.payload.dto.nurse.NursingNoteAddendumRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NursingNoteResponseDTO;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public interface NursingNoteService {

    NursingNoteResponseDTO createNote(NursingNoteCreateRequestDTO request, Locale locale);

    NursingNoteResponseDTO appendAddendum(UUID noteId, UUID hospitalId, NursingNoteAddendumRequestDTO request, Locale locale);

    List<NursingNoteResponseDTO> getRecentNotes(UUID patientId, UUID hospitalId, int limit, Locale locale);

    NursingNoteResponseDTO getNote(UUID noteId, UUID hospitalId, Locale locale);
}
