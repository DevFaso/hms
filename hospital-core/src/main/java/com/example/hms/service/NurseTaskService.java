package com.example.hms.service;

import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface NurseTaskService {

    List<NurseVitalTaskResponseDTO> getDueVitals(UUID nurseUserId, UUID hospitalId, Duration window);

    List<NurseMedicationTaskResponseDTO> getMedicationTasks(UUID nurseUserId, UUID hospitalId, String statusFilter);

    List<NurseOrderTaskResponseDTO> getOrderTasks(UUID nurseUserId, UUID hospitalId, String statusFilter, int limit);

    List<NurseHandoffSummaryDTO> getHandoffSummaries(UUID nurseUserId, UUID hospitalId, int limit);

    void completeHandoff(UUID handoffId, UUID nurseUserId, UUID hospitalId);

    List<NurseAnnouncementDTO> getAnnouncements(UUID hospitalId, int limit);

    NurseMedicationTaskResponseDTO recordMedicationAdministration(
        UUID medicationTaskId,
        UUID nurseUserId,
        UUID hospitalId,
        NurseMedicationAdministrationRequestDTO request
    );

    NurseHandoffChecklistUpdateResponseDTO updateHandoffChecklistItem(
        UUID handoffId,
        UUID taskId,
        UUID nurseUserId,
        UUID hospitalId,
        boolean completed
    );
}
