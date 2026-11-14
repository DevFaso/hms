package com.example.hms.repository;

import com.example.hms.enums.SafeguardingReviewStatus;
import com.example.hms.model.safeguarding.SafeguardingReview;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** r53: safeguarding reviews (V296). Every read names the hospital: a review is never read across hospitals. */
public interface SafeguardingReviewRepository extends JpaRepository<SafeguardingReview, UUID> {

    boolean existsByPatientIdAndHospitalIdAndTriggerCodeAndStatus(UUID patientId, UUID hospitalId, String triggerCode,
                                                                  SafeguardingReviewStatus status);

    List<SafeguardingReview> findByHospitalIdAndStatusOrderByRaisedAtDesc(UUID hospitalId,
                                                                         SafeguardingReviewStatus status);

    List<SafeguardingReview> findByPatientIdAndHospitalIdOrderByRaisedAtDesc(UUID patientId, UUID hospitalId);
}
