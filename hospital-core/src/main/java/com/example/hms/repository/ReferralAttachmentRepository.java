package com.example.hms.repository;

import com.example.hms.model.referral.ReferralAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ReferralAttachmentRepository extends JpaRepository<ReferralAttachment, UUID> {
    List<ReferralAttachment> findByReferral_Id(UUID referralId);
}
