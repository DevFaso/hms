package com.example.hms.repository;

import com.example.hms.model.labor.ObstetricAlertEscalation;
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
     * V386: ids of every alert nobody has acknowledged or closed, the oldest first: there is no cap on the steps,
     * so an alert stays on this list until it is answered. An alert whose first notice is still owed is listed
     * once it is older than {@code owedBefore} (until then the delivery that follows its commit has it). The alerts
     * left over from the capped rule ({@code ladderBacklog}) are not listed.
     */
    @Query("""
        SELECT e.id FROM ObstetricAlertEscalation e
        WHERE e.acknowledgedAt IS NULL
          AND e.ladderBacklog = false
          AND (e.firstNoticeFiledAt IS NOT NULL OR e.createdAt < :owedBefore)
        ORDER BY e.createdAt ASC
        """)
    List<UUID> findOpenIds(@Param("owedBefore") LocalDateTime owedBefore);

    /**
     * V386: claims the first notice of an owed row. It succeeds once: the delivery that follows the commit and the
     * sweep never both deliver, and the claim is undone with its transaction when the delivery fails.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
        UPDATE ObstetricAlertEscalation e
           SET e.firstNoticeFiledAt = :now, e.lastEscalationAt = :now, e.updatedAt = :now
         WHERE e.id = :id AND e.firstNoticeFiledAt IS NULL AND e.acknowledgedAt IS NULL
        """)
    int claimFirstNotice(@Param("id") UUID id, @Param("now") LocalDateTime now);

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

    /**
     * QA D3862: an alert of this patient, source and type that nobody has acknowledged or closed and that is
     * still escalating: a vital set's early warning repeated within it is not filed a second time.
     */
    @Query("""
        SELECT COUNT(e) > 0 FROM ObstetricAlertEscalation e
        WHERE e.hospitalId = :hospitalId AND e.patientId = :patientId AND e.sourceType = :sourceType
          AND COALESCE(e.alertType, '') = :alertType
          AND e.acknowledgedAt IS NULL AND e.ladderBacklog = false
        """)
    boolean existsOpenForPatient(@Param("hospitalId") UUID hospitalId, @Param("patientId") UUID patientId,
                                 @Param("sourceType") String sourceType, @Param("alertType") String alertType);

    /** V386: an alert of this record that nobody has acknowledged or closed and that is still escalating. */
    @Query("""
        SELECT COUNT(e) > 0 FROM ObstetricAlertEscalation e
        WHERE e.sourceType = :sourceType AND e.sourceId = :sourceId
          AND e.acknowledgedAt IS NULL AND e.ladderBacklog = false
        """)
    boolean existsOpenForSource(@Param("sourceType") String sourceType, @Param("sourceId") UUID sourceId);

    /**
     * Review of #1370: the rows {@link #existsOpenForPatient} counts. An item of one may already be acknowledged
     * while the sweep has not closed it yet; the repeat check closes such a row rather than hold a new reading on it.
     */
    @Query("""
        SELECT e FROM ObstetricAlertEscalation e
        WHERE e.hospitalId = :hospitalId AND e.patientId = :patientId AND e.sourceType = :sourceType
          AND COALESCE(e.alertType, '') = :alertType
          AND e.acknowledgedAt IS NULL AND e.ladderBacklog = false
        """)
    java.util.List<ObstetricAlertEscalation> findOpenForPatient(@Param("hospitalId") UUID hospitalId,
                                                                @Param("patientId") UUID patientId,
                                                                @Param("sourceType") String sourceType,
                                                                @Param("alertType") String alertType);

    /** Review of #1370: the rows {@link #existsOpenForSource} counts (see {@link #findOpenForPatient}). */
    @Query("""
        SELECT e FROM ObstetricAlertEscalation e
        WHERE e.sourceType = :sourceType AND e.sourceId = :sourceId
          AND e.acknowledgedAt IS NULL AND e.ladderBacklog = false
        """)
    java.util.List<ObstetricAlertEscalation> findOpenForSource(@Param("sourceType") String sourceType,
                                                               @Param("sourceId") UUID sourceId);

    /** V386: the alerts of one record that nobody has acknowledged or closed (its countersignature, once signed). */
    @Query("""
        SELECT e FROM ObstetricAlertEscalation e
        WHERE e.sourceType = :sourceType AND e.sourceId = :sourceId AND e.acknowledgedAt IS NULL
        """)
    List<ObstetricAlertEscalation> findOpenBySource(@Param("sourceType") String sourceType,
                                                    @Param("sourceId") UUID sourceId);

    /** Whether this alert of this record was ever escalated, open or closed (a correction never replays it). */
    boolean existsBySourceTypeAndSourceIdAndAlertMessageKey(String sourceType, UUID sourceId, String alertMessageKey);

    /**
     * QA D1801: the escalations of one source and alert sentence that nobody has acknowledged or closed, so a
     * re-raised alert can replace them instead of escalating beside them.
     */
    @Query("""
        SELECT e FROM ObstetricAlertEscalation e
        WHERE e.hospitalId = :hospitalId AND e.sourceType = :sourceType AND e.sourceId = :sourceId
          AND e.alertMessageKey = :messageKey AND e.acknowledgedAt IS NULL
        """)
    List<ObstetricAlertEscalation> findOpenBySourceAndMessageKey(@Param("hospitalId") UUID hospitalId,
                                                                 @Param("sourceType") String sourceType,
                                                                 @Param("sourceId") UUID sourceId,
                                                                 @Param("messageKey") String messageKey);
}
