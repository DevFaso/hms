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

    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
           "FROM StaffShift s " +
           "WHERE s.staff.id = :staffId " +
           "AND s.shiftDate = :shiftDate " +
           "AND s.status <> com.example.hms.enums.StaffShiftStatus.CANCELLED " +
           "AND (:excludeId IS NULL OR s.id <> :excludeId) " +
           "AND s.startTime < :endTime " +
           "AND s.endTime > :startTime")
    boolean existsOverlappingShift(@Param("staffId") UUID staffId,
                                   @Param("shiftDate") LocalDate shiftDate,
                                   @Param("startTime") LocalTime startTime,
                                   @Param("endTime") LocalTime endTime,
                                   @Param("excludeId") UUID excludeId);

    /**
     * Check overlap for an overnight shift (endTime &lt; startTime, i.e. crosses midnight).
     * A conflict exists when another non-cancelled shift on the same date has any time
     * portion after the new shift's start time (the pre-midnight leg).
     */
    @Query("SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END " +
           "FROM StaffShift s " +
           "WHERE s.staff.id = :staffId " +
           "AND s.shiftDate = :shiftDate " +
           "AND s.status <> com.example.hms.enums.StaffShiftStatus.CANCELLED " +
           "AND (:excludeId IS NULL OR s.id <> :excludeId) " +
           "AND s.endTime > :startTime")
    boolean existsOvernightOverlappingShift(@Param("staffId") UUID staffId,
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
