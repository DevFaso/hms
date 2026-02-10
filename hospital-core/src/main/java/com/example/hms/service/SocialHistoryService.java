package com.example.hms.service;

import com.example.hms.payload.dto.medicalhistory.SocialHistoryRequestDTO;
import com.example.hms.payload.dto.medicalhistory.SocialHistoryResponseDTO;

import java.util.List;
import java.util.UUID;

public interface SocialHistoryService {

    /**
     * Create a new social history record
     */
    SocialHistoryResponseDTO createSocialHistory(SocialHistoryRequestDTO requestDTO);

    /**
     * Get social history by ID
     */
    SocialHistoryResponseDTO getSocialHistoryById(UUID id);

    /**
     * Get all social histories for a patient
     */
    List<SocialHistoryResponseDTO> getSocialHistoriesByPatientId(UUID patientId);

    /**
     * Get current active social history for a patient
     */
    SocialHistoryResponseDTO getCurrentSocialHistory(UUID patientId);

    /**
     * Update social history
     */
    SocialHistoryResponseDTO updateSocialHistory(UUID id, SocialHistoryRequestDTO requestDTO);

    /**
     * Delete (soft delete) social history
     */
    void deleteSocialHistory(UUID id);
}
