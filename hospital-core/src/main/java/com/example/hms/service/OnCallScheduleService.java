package com.example.hms.service;

import com.example.hms.payload.dto.OnCallScheduleRequestDTO;
import com.example.hms.payload.dto.OnCallScheduleResponseDTO;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * On-call rota writes (P2 #13).
 *
 * <p>{@code GET /me/on-call-status} has read this table since it shipped and
 * NOTHING has ever written to it, so the endpoint has only ever been able to
 * answer "no". An on-call schedule nobody can fill in is an on-call schedule
 * that says everyone is off duty.
 */
public interface OnCallScheduleService {

    List<OnCallScheduleResponseDTO> listForHospital(OffsetDateTime from, OffsetDateTime to);

    List<OnCallScheduleResponseDTO> listForStaff(UUID staffId);

    OnCallScheduleResponseDTO create(OnCallScheduleRequestDTO request);

    OnCallScheduleResponseDTO update(UUID id, OnCallScheduleRequestDTO request);

    void delete(UUID id);
}
