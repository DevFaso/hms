package com.example.hms.repository;

import com.example.hms.model.referral.ObgynReferralMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ObgynReferralMessageRepository extends JpaRepository<ObgynReferralMessage, UUID> {
    List<ObgynReferralMessage> findByReferral_IdOrderBySentAtAsc(UUID referralId);
}
