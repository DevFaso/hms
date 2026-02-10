package com.example.hms.service;

import com.example.hms.payload.dto.referral.ObgynReferralAcknowledgeRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCancelRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCompletionRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralCreateRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralMessageDTO;
import com.example.hms.payload.dto.referral.ObgynReferralMessageRequestDTO;
import com.example.hms.payload.dto.referral.ObgynReferralResponseDTO;
import com.example.hms.payload.dto.referral.ReferralStatusSummaryDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface ObgynReferralService {

    ObgynReferralResponseDTO createReferral(ObgynReferralCreateRequestDTO request, String username);

    ObgynReferralResponseDTO getReferral(UUID referralId);

    Page<ObgynReferralResponseDTO> getReferralsForPatient(UUID patientId, Pageable pageable);

    Page<ObgynReferralResponseDTO> getReferralsForHospital(UUID hospitalId, Pageable pageable);

    Page<ObgynReferralResponseDTO> getReferralsForObgyn(UUID obgynUserId, Pageable pageable);

    ObgynReferralResponseDTO acknowledgeReferral(
        UUID referralId,
        ObgynReferralAcknowledgeRequestDTO request,
        String username
    );

    ObgynReferralResponseDTO completeReferral(
        UUID referralId,
        ObgynReferralCompletionRequestDTO request,
        String username
    );

    ObgynReferralResponseDTO cancelReferral(
        UUID referralId,
        ObgynReferralCancelRequestDTO request,
        String username
    );

    ObgynReferralMessageDTO addMessage(
        UUID referralId,
        ObgynReferralMessageRequestDTO request,
        String username
    );

    List<ObgynReferralMessageDTO> getMessages(UUID referralId);

    ReferralStatusSummaryDTO getStatusSummary();
}
