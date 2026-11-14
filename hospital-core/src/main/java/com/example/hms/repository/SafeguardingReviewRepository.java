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

    /**
     * Rex on #1168: the review to record, locked (SELECT ... FOR UPDATE) and only at its own hospital. Two clinicians
     * recording at once are serialised: the second reads the review already REVIEWED and is refused, so the first
     * note is never overwritten.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query(
        "SELECT r FROM SafeguardingReview r WHERE r.id = :id AND r.hospitalId = :hospitalId")
    java.util.Optional<SafeguardingReview> lockByIdAtHospital(
        @org.springframework.data.repository.query.Param("id") UUID id,
        @org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId);
}
