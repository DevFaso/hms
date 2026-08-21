package com.example.hms.repository;

import com.example.hms.model.OnCallSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface OnCallScheduleRepository extends JpaRepository<OnCallSchedule, UUID> {

    /**
     * Returns all on-call entries for a staff member whose schedule window
     * contains the given moment (i.e. startTime ≤ now ≤ endTime).
     */
    @Query("SELECT o FROM OnCallSchedule o WHERE o.staff.id = :staffId " +
           "AND o.startTime <= :now AND o.endTime >= :now " +
           "ORDER BY o.startTime ASC")
    List<OnCallSchedule> findActiveByStaffIdAt(
            @Param("staffId") UUID staffId,
            @Param("now") OffsetDateTime now);

    List<OnCallSchedule> findByStaff_IdOrderByStartTimeDesc(UUID staffId);

    /**
     * Roster for a hospital over a window. Scoped through {@code staff.hospital}
     * because OnCallSchedule has no hospital of its own — the rota belongs to
     * whoever is on it.
     */
    @Query("SELECT o FROM OnCallSchedule o "
        + "WHERE o.staff.hospital.id = :hospitalId "
        + "AND o.startTime <= :to AND o.endTime >= :from "
        + "ORDER BY o.startTime ASC")
    List<OnCallSchedule> findByHospitalAndWindow(@Param("hospitalId") UUID hospitalId,
                                                 @Param("from") OffsetDateTime from,
                                                 @Param("to") OffsetDateTime to);

    /**
     * Overlapping entries for the same staff member, excluding one id (so an
     * update does not collide with itself). Double-booking a clinician on call
     * is the failure this exists to prevent: two rotas both believing they have
     * cover is worse than one rota believing it has none.
     */
    @Query("SELECT o FROM OnCallSchedule o "
        + "WHERE o.staff.id = :staffId "
        + "AND (:excludeId IS NULL OR o.id <> :excludeId) "
        + "AND o.startTime < :end AND o.endTime > :start")
    List<OnCallSchedule> findOverlapping(@Param("staffId") UUID staffId,
                                         @Param("start") OffsetDateTime start,
                                         @Param("end") OffsetDateTime end,
                                         @Param("excludeId") UUID excludeId);
}
