package com.example.hms.service;

import com.example.hms.payload.dto.GeneralReferralRequestDTO;
import com.example.hms.payload.dto.GeneralReferralResponseDTO;
import com.example.hms.payload.dto.referral.ReferralEventResponseDTO;
import com.example.hms.payload.dto.referral.RejectReferralRequestDTO;
import com.example.hms.payload.dto.referral.ScheduleReferralRequestDTO;
import org.springframework.data.domain.Pageable;

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

    GeneralReferralResponseDTO scheduleReferral(UUID referralId, ScheduleReferralRequestDTO request);

    GeneralReferralResponseDTO startReferral(UUID referralId);

    GeneralReferralResponseDTO completeReferral(UUID referralId, String summary, String followUp);

    GeneralReferralResponseDTO rejectReferral(UUID referralId, RejectReferralRequestDTO request);

    void cancelReferral(UUID referralId, String reason);
    
    List<GeneralReferralResponseDTO> getReferralsByPatient(UUID patientId);

    /**
     * The portal patient's own referrals, at every hospital that holds any
     * (design Q1: a patient is bounded by ownership, not by a hospital). No
     * hospital scope is read. Callers MUST have bound {{@code patientId}} to the
     * principal first: PatientPortalServiceImpl resolves it from the caller
     * (or verifies a proxy grant); nothing else calls this.
     */
    List<GeneralReferralResponseDTO> getReferralsForPortalPatient(UUID patientId);
    
    List<GeneralReferralResponseDTO> getReferralsByReferringProvider(UUID providerId);
    
    List<GeneralReferralResponseDTO> getReferralsByReceivingProvider(UUID providerId);
    
    List<GeneralReferralResponseDTO> getReferralsByHospital(UUID hospitalId, String status);
    
    List<GeneralReferralResponseDTO> getAllReferrals(String status);

    /**
     * Cross-tenant paged fetch for super-admin dashboards.
     * Caller is responsible for enforcing the SUPER_ADMIN role at the boundary.
     */
    List<GeneralReferralResponseDTO> getRecentForSuperAdmin(Pageable pageable);
    
    List<GeneralReferralResponseDTO> getOverdueReferrals();

    /** Chronological state-machine audit trail for one referral. */
    List<ReferralEventResponseDTO> getReferralEvents(UUID referralId);
}
