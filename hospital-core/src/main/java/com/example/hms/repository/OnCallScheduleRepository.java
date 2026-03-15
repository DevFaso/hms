package com.example.hms.repository;

import com.example.hms.model.OnCallSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
            @Param("now") LocalDateTime now);

    List<OnCallSchedule> findByStaff_IdOrderByStartTimeDesc(UUID staffId);
}
