package com.example.hms.service;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.payload.dto.consultation.CompleteConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationRequestDTO;
import com.example.hms.payload.dto.consultation.ConsultationResponseDTO;
import com.example.hms.payload.dto.consultation.ConsultationStatsDTO;
import com.example.hms.payload.dto.consultation.ConsultationUpdateDTO;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ConsultationService {

    ConsultationResponseDTO createConsultation(ConsultationRequestDTO request, UUID requestingProviderId);

    ConsultationResponseDTO getConsultation(UUID consultationId);

    List<ConsultationResponseDTO> getConsultationsForPatient(UUID patientId);

    List<ConsultationResponseDTO> getConsultationsForHospital(UUID hospitalId, ConsultationStatus status);

    List<ConsultationResponseDTO> getAllConsultations(ConsultationStatus status);

    List<ConsultationResponseDTO> getConsultationsRequestedBy(UUID providerId);

    List<ConsultationResponseDTO> getConsultationsAssignedTo(UUID consultantId, ConsultationStatus status);

    ConsultationResponseDTO acknowledgeConsultation(UUID consultationId, UUID consultantId);

    ConsultationResponseDTO updateConsultation(UUID consultationId, ConsultationUpdateDTO updateDTO);

    ConsultationResponseDTO scheduleConsultation(UUID consultationId, LocalDateTime scheduledAt, String scheduleNote);

    ConsultationResponseDTO startConsultation(UUID consultationId);

    ConsultationResponseDTO completeConsultation(UUID consultationId, CompleteConsultationRequestDTO request);

    ConsultationResponseDTO declineConsultation(UUID consultationId, String declineReason);

    ConsultationResponseDTO cancelConsultation(UUID consultationId, String cancellationReason);

    ConsultationResponseDTO assignConsultation(UUID consultationId, UUID consultantId, UUID assignedById, String assignmentNote);

    ConsultationResponseDTO reassignConsultation(UUID consultationId, UUID consultantId, UUID assignedById, String reassignmentReason);

    List<ConsultationResponseDTO> getPendingConsultations(UUID hospitalId);

    List<ConsultationResponseDTO> getMyConsultations(UUID consultantStaffId);

    List<ConsultationResponseDTO> getOverdueConsultations(UUID hospitalId);

    ConsultationStatsDTO getStats(UUID hospitalId);
}
