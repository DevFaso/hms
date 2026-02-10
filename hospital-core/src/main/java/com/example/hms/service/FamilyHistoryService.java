package com.example.hms.service;

import com.example.hms.payload.dto.medicalhistory.FamilyHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.FamilyHistoryResponseDTO;

import java.util.List;
import java.util.UUID;

public interface FamilyHistoryService {

    /**
     * Create a new family history record
     */
    FamilyHistoryResponseDTO createFamilyHistory(FamilyHistoryRequestDTO requestDTO);

    /**
     * Get family history by ID
     */
    FamilyHistoryResponseDTO getFamilyHistoryById(UUID id);

    /**
     * Get all family histories for a patient
     */
    List<FamilyHistoryResponseDTO> getFamilyHistoriesByPatientId(UUID patientId);

    /**
     * Get genetic conditions for a patient
     */
    List<FamilyHistoryResponseDTO> getGeneticConditions(UUID patientId);

    /**
     * Get family histories requiring screening
     */
    List<FamilyHistoryResponseDTO> getScreeningRecommendations(UUID patientId);

    /**
     * Get family histories by condition category
     */
    List<FamilyHistoryResponseDTO> getFamilyHistoriesByConditionCategory(UUID patientId, String category);

    /**
     * Update family history
     */
    FamilyHistoryResponseDTO updateFamilyHistory(UUID id, FamilyHistoryRequestDTO requestDTO);

    /**
     * Delete (soft delete) family history
     */
    void deleteFamilyHistory(UUID id);
}
