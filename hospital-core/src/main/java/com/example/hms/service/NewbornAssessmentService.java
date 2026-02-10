package com.example.hms.service;

import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentRequestDTO;
import com.example.hms.payload.dto.clinical.newborn.NewbornAssessmentResponseDTO;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface NewbornAssessmentService {

    NewbornAssessmentResponseDTO recordAssessment(
        UUID patientId,
        NewbornAssessmentRequestDTO request,
        UUID recorderUserId
    );

    List<NewbornAssessmentResponseDTO> getRecentAssessments(
        UUID patientId,
        UUID hospitalId,
        int limit
    );

    List<NewbornAssessmentResponseDTO> searchAssessments(
        UUID patientId,
        UUID hospitalId,
        LocalDateTime from,
        LocalDateTime to,
        int page,
        int size
    );
}
