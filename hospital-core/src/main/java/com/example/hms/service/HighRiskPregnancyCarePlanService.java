package com.example.hms.service;

import com.example.hms.payload.dto.highrisk.HighRiskBloodPressureLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskCareTeamNoteRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskMedicationLogRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanRequestDTO;
import com.example.hms.payload.dto.highrisk.HighRiskPregnancyCarePlanResponseDTO;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface HighRiskPregnancyCarePlanService {

    HighRiskPregnancyCarePlanResponseDTO createPlan(HighRiskPregnancyCarePlanRequestDTO request, String username);

    HighRiskPregnancyCarePlanResponseDTO updatePlan(UUID planId, HighRiskPregnancyCarePlanRequestDTO request, String username);

    HighRiskPregnancyCarePlanResponseDTO getPlan(UUID planId, String username);

    List<HighRiskPregnancyCarePlanResponseDTO> getPlansForPatient(UUID patientId, String username);

    HighRiskPregnancyCarePlanResponseDTO getActivePlan(UUID patientId, String username);

    HighRiskPregnancyCarePlanResponseDTO addBloodPressureLog(UUID planId, HighRiskBloodPressureLogRequestDTO request, String username);

    HighRiskPregnancyCarePlanResponseDTO addMedicationLog(UUID planId, HighRiskMedicationLogRequestDTO request, String username);

    HighRiskPregnancyCarePlanResponseDTO addCareTeamNote(UUID planId, HighRiskCareTeamNoteRequestDTO request, String username);

    HighRiskPregnancyCarePlanResponseDTO markMilestoneComplete(UUID planId, UUID milestoneId, LocalDate completionDate, String username);
}
