package com.example.hms.repository;

import com.example.hms.model.ReferralEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReferralEventRepository extends JpaRepository<ReferralEvent, UUID> {

    /** Chronological transition history for a single referral. */
    List<ReferralEvent> findByReferralIdOrderByRecordedAtAsc(UUID referralId);
}
