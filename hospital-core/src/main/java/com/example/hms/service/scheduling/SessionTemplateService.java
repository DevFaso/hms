package com.example.hms.service.scheduling;

import com.example.hms.payload.dto.scheduling.SessionTemplateRequestDTO;
import com.example.hms.payload.dto.scheduling.SessionTemplateResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Session-template CRUD (P2 #11): the recurring clinic sessions that
 * {@code POST /slots/generate} materialises into bookable slots. Without a
 * writer for this table the generator had nothing to apply and answered
 * {@code slotsCreated=0} forever.
 */
public interface SessionTemplateService {

    List<SessionTemplateResponseDTO> list(boolean includeInactive);

    SessionTemplateResponseDTO create(SessionTemplateRequestDTO request);

    SessionTemplateResponseDTO update(UUID id, SessionTemplateRequestDTO request);

    SessionTemplateResponseDTO deactivate(UUID id);

    SessionTemplateResponseDTO reactivate(UUID id);
}
