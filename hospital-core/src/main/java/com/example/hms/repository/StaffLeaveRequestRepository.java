package com.example.hms.repository;

import com.example.hms.enums.StaffLeaveStatus;
import com.example.hms.model.StaffLeaveRequest;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffLeaveRequestRepository extends JpaRepository<StaffLeaveRequest, UUID> {

    @Query("SELECT CASE WHEN COUNT(l) > 0 THEN true ELSE false END " +
           "FROM StaffLeaveRequest l " +
           "WHERE l.staff.id = :staffId " +
           "AND (:excludeId IS NULL OR l.id <> :excludeId) " +
           "AND l.status IN :statuses " +
           "AND l.endDate >= :startDate " +
           "AND l.startDate <= :endDate")
    boolean existsOverlappingLeave(@Param("staffId") UUID staffId,
                                   @Param("startDate") LocalDate startDate,
                                   @Param("endDate") LocalDate endDate,
                                   @Param("statuses") Collection<StaffLeaveStatus> statuses,
                                   @Param("excludeId") UUID excludeId);

    List<StaffLeaveRequest> findByHospital_IdAndStartDateBetweenOrderByStartDateAsc(
        UUID hospitalId,
        LocalDate startDate,
        LocalDate endDate
    );

    List<StaffLeaveRequest> findByStaff_IdAndStartDateBetweenOrderByStartDateAsc(
        UUID staffId,
        LocalDate startDate,
        LocalDate endDate
    );

    List<StaffLeaveRequest> findByDepartment_IdAndStartDateBetweenOrderByStartDateAsc(
        UUID departmentId,
        LocalDate startDate,
        LocalDate endDate
    );

    List<StaffLeaveRequest> findByStatusInAndHospital_IdOrderByStartDateAsc(
        Collection<StaffLeaveStatus> statuses,
        UUID hospitalId
    );

    @EntityGraph(attributePaths = {"staff", "staff.user", "hospital", "department", "requestedBy", "reviewedBy"})
    Optional<StaffLeaveRequest> findDetailedById(UUID id);

    @Query("SELECT l FROM StaffLeaveRequest l " +
           "WHERE l.staff.id = :staffId " +
           "AND l.status IN :statuses " +
           "AND l.endDate >= :targetDate " +
           "AND l.startDate <= :targetDate")
    List<StaffLeaveRequest> findLeavesOverlappingDate(@Param("staffId") UUID staffId,
                                                     @Param("targetDate") LocalDate targetDate,
                                                     @Param("statuses") Collection<StaffLeaveStatus> statuses);

    List<StaffLeaveRequest> findByStartDateBetweenOrderByStartDateAsc(
        LocalDate startDate,
        LocalDate endDate
    );
}
