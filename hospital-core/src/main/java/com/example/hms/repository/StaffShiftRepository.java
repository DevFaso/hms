package com.example.hms.repository;

import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.model.StaffShift;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffShiftRepository extends JpaRepository<StaffShift, UUID> {

    /**
     * Detects overlapping shifts on the same date for the same staff member.
     *
     * <p>Handles cross-midnight shifts (endTime &lt; startTime) correctly by splitting the check
     * into four cases:
     * <ol>
     *   <li>Both existing and new shift are same-day (start &lt; end): classic interval overlap</li>
     *   <li>Existing is cross-midnight, new is same-day: new start falls within the existing window</li>
     *   <li>Existing is same-day, new is cross-midnight: existing window overlaps new start portion</li>
     *   <li>Both are cross-midnight: they always overlap (both span midnight)</li>
     * </ol>
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
           "FROM StaffShift s " +
           "WHERE s.staff.id = :staffId " +
           "AND s.shiftDate = :shiftDate " +
           "AND s.status <> com.example.hms.enums.StaffShiftStatus.CANCELLED " +
           "AND (:excludeId IS NULL OR s.id <> :excludeId) " +
           "AND (" +
           "  (s.startTime < s.endTime AND :startTime < :endTime AND s.startTime < :endTime AND s.endTime > :startTime) " +
           "  OR (s.startTime >= s.endTime AND :startTime < :endTime AND (:startTime >= s.startTime OR :endTime <= s.endTime)) " +
           "  OR (s.startTime < s.endTime AND :startTime >= :endTime AND (s.startTime >= :startTime OR s.endTime <= :endTime)) " +
           "  OR (s.startTime >= s.endTime AND :startTime >= :endTime)" +
           ")")
    boolean existsOverlappingShift(@Param("staffId") UUID staffId,
                                   @Param("shiftDate") LocalDate shiftDate,
                                   @Param("startTime") LocalTime startTime,
                                   @Param("endTime") LocalTime endTime,
                                   @Param("excludeId") UUID excludeId);

    @EntityGraph(attributePaths = {"staff", "staff.user", "hospital", "department", "scheduledBy", "lastModifiedBy"})
    Optional<StaffShift> findDetailedById(UUID id);

    List<StaffShift> findByHospital_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
        UUID hospitalId,
        LocalDate startDate,
        LocalDate endDate
    );

    List<StaffShift> findByDepartment_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
        UUID departmentId,
        LocalDate startDate,
        LocalDate endDate
    );

    List<StaffShift> findByStaff_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
        UUID staffId,
        LocalDate startDate,
        LocalDate endDate
    );

    @Query("SELECT s FROM StaffShift s " +
           "WHERE s.staff.id = :staffId " +
           "AND s.shiftDate BETWEEN :startDate AND :endDate " +
           "AND s.status <> com.example.hms.enums.StaffShiftStatus.CANCELLED")
    List<StaffShift> findActiveShiftsBetween(@Param("staffId") UUID staffId,
                                             @Param("startDate") LocalDate startDate,
                                             @Param("endDate") LocalDate endDate);

    List<StaffShift> findByStaff_IdAndShiftDateInAndStatusIn(
        UUID staffId,
        Collection<LocalDate> dates,
        Collection<StaffShiftStatus> statuses
    );

    List<StaffShift> findByShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
        LocalDate startDate,
        LocalDate endDate
    );
}
