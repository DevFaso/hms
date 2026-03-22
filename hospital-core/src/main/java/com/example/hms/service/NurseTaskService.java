package com.example.hms.service;

import com.example.hms.payload.dto.nurse.NurseAdmissionSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseAnnouncementDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseCareNoteResponseDTO;
import com.example.hms.payload.dto.nurse.NurseDashboardSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseFlowBoardDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffChecklistUpdateResponseDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NurseHandoffSummaryDTO;
import com.example.hms.payload.dto.nurse.NurseInboxItemDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationAdministrationRequestDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseOrderTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseTaskCompleteRequestDTO;
import com.example.hms.payload.dto.nurse.NurseTaskCreateRequestDTO;
import com.example.hms.payload.dto.nurse.NurseTaskItemDTO;
import com.example.hms.payload.dto.nurse.NurseVitalCaptureRequestDTO;
import com.example.hms.payload.dto.nurse.NurseVitalTaskResponseDTO;
import com.example.hms.payload.dto.nurse.NurseWorkboardPatientDTO;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

public interface NurseTaskService {

    List<NurseVitalTaskResponseDTO> getDueVitals(UUID nurseUserId, UUID hospitalId, Duration window);

    List<NurseMedicationTaskResponseDTO> getMedicationTasks(UUID nurseUserId, UUID hospitalId, String statusFilter);

    List<NurseOrderTaskResponseDTO> getOrderTasks(UUID nurseUserId, UUID hospitalId, String statusFilter, int limit);

    List<NurseHandoffSummaryDTO> getHandoffSummaries(UUID nurseUserId, UUID hospitalId, int limit);

    void completeHandoff(UUID handoffId, UUID nurseUserId, UUID hospitalId);

    NurseHandoffSummaryDTO createHandoff(UUID nurseUserId, UUID hospitalId, NurseHandoffCreateRequestDTO request);

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

    NurseDashboardSummaryDTO getDashboardSummary(UUID nurseUserId, UUID hospitalId);

    // ── MVP 12: Nurse Station Phase 2 ──────────────────────────────────

    List<NurseWorkboardPatientDTO> getWorkboard(UUID nurseUserId, UUID hospitalId);

    NurseFlowBoardDTO getPatientFlow(UUID hospitalId, UUID departmentId);

    void captureVitals(UUID patientId, UUID nurseUserId, UUID hospitalId, NurseVitalCaptureRequestDTO request);

    List<NurseAdmissionSummaryDTO> getPendingAdmissions(UUID hospitalId, UUID departmentId);

    // ── MVP 13: Nurse Station Phase 3 ──────────────────────────────────

    List<NurseTaskItemDTO> getNursingTaskBoard(UUID hospitalId, String statusFilter);

    NurseTaskItemDTO createNursingTask(UUID nurseUserId, UUID hospitalId, NurseTaskCreateRequestDTO request);

    NurseTaskItemDTO completeNursingTask(UUID taskId, UUID nurseUserId, UUID hospitalId, NurseTaskCompleteRequestDTO request);

    List<NurseInboxItemDTO> getNurseInboxItems(String nurseUsername, int limit);

    void markNurseInboxRead(UUID itemId, String nurseUsername);

    NurseCareNoteResponseDTO createCareNote(UUID patientId, UUID nurseUserId, UUID hospitalId, NurseCareNoteRequestDTO request);
}
