package com.example.hms.service;

import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationRequestDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumObservationResponseDTO;
import com.example.hms.payload.dto.clinical.postpartum.PostpartumScheduleDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface PostpartumCareService {

    PostpartumObservationResponseDTO recordObservation(
        UUID patientId,
        PostpartumObservationRequestDTO request,
        UUID recorderUserId
    );

    List<PostpartumObservationResponseDTO> getRecentObservations(
        UUID patientId,
        UUID hospitalId,
        UUID carePlanId,
        int limit
    );

    List<PostpartumObservationResponseDTO> searchObservations(
        UUID patientId,
        UUID hospitalId,
        UUID carePlanId,
        LocalDateTime from,
        LocalDateTime to,
        int page,
        int size
    );

    PostpartumScheduleDTO getSchedule(UUID patientId, UUID hospitalId, UUID carePlanId);
}
