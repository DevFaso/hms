package com.example.hms.repository;

import com.example.hms.model.labor.ObstetricAlertEscalation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** QA D605: escalation rows of URGENT obstetric and neonatal alerts. */
@Repository
public interface ObstetricAlertEscalationRepository extends JpaRepository<ObstetricAlertEscalation, UUID> {

    /**
     * Ids of the alerts nobody has acknowledged whose last notification is
     * older than the cutoff and which have rounds left, oldest first.
     */
    @Query("""
        SELECT e.id FROM ObstetricAlertEscalation e
        WHERE e.acknowledgedAt IS NULL
          AND e.escalationLevel < :maxRounds
          AND COALESCE(e.lastEscalationAt, e.createdAt) < :cutoff
        ORDER BY COALESCE(e.lastEscalationAt, e.createdAt) ASC
        """)
    List<UUID> findIdsAwaitingEscalation(@Param("cutoff") LocalDateTime cutoff,
                                         @Param("maxRounds") short maxRounds,
                                         Pageable page);

    /**
     * Claims the next round: succeeds only while the row is still at
     * {@code current} and unacknowledged, so a row acknowledged or already
     * escalated since it was read is never re-notified.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE ObstetricAlertEscalation e
           SET e.escalationLevel = :next, e.lastEscalationAt = :now, e.updatedAt = :now
         WHERE e.id = :id AND e.escalationLevel = :current AND e.acknowledgedAt IS NULL
        """)
    int claimRound(@Param("id") UUID id, @Param("current") short current,
                   @Param("next") short next, @Param("now") LocalDateTime now);
}
