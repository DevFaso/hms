package com.example.hms.service;

import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;

import java.util.List;
import java.util.UUID;

/**
 * Service interface for general referral management
 */
public interface GeneralReferralService {

    GeneralReferralResponseDTO createReferral(GeneralReferralRequestDTO request);
    
    GeneralReferralResponseDTO getReferral(UUID referralId);
    
    GeneralReferralResponseDTO submitReferral(UUID referralId);
    
    GeneralReferralResponseDTO acknowledgeReferral(UUID referralId, String notes, UUID receivingProviderId);
    
    GeneralReferralResponseDTO completeReferral(UUID referralId, String summary, String followUp);
    
    void cancelReferral(UUID referralId, String reason);
    
    List<GeneralReferralResponseDTO> getReferralsByPatient(UUID patientId);
    
    List<GeneralReferralResponseDTO> getReferralsByReferringProvider(UUID providerId);
    
    List<GeneralReferralResponseDTO> getReferralsByReceivingProvider(UUID providerId);
    
    List<GeneralReferralResponseDTO> getReferralsByHospital(UUID hospitalId, String status);
    
    List<GeneralReferralResponseDTO> getOverdueReferrals();
}
