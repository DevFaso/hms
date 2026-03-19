package com.example.hms.repository;

import com.example.hms.model.StaffAvailability;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.stereotype.Repository;

import jakarta.persistence.QueryHint;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffAvailabilityRepository extends JpaRepository<StaffAvailability, UUID> {
    Optional<StaffAvailability> findByStaff_IdAndDate(UUID staffId, LocalDate date);
    boolean existsByStaff_IdAndDate(UUID staffId, LocalDate date);

    /**
     * Returns a paged slice of staff availability records, sorted by date descending.
     * DISTINCT is applied at the JPQL level to prevent Hibernate from throwing
     * "More than one row with the given identifier" when the staff table has
     * duplicate rows for the same PK (data integrity issue fixed by V8 migration).
     * PASS_DISTINCT_THROUGH=false tells Hibernate not to emit SQL DISTINCT (which
     * would break pagination) while still deduplicating in-memory.
     */
    @Query("SELECT DISTINCT sa FROM StaffAvailability sa " +
           "LEFT JOIN FETCH sa.staff s " +
           "LEFT JOIN FETCH s.user " +
           "LEFT JOIN FETCH s.department d " +
           "LEFT JOIN FETCH d.departmentTranslations " +
           "LEFT JOIN FETCH sa.hospital " +
           "ORDER BY sa.date DESC")
    @QueryHints(@QueryHint(name = "hibernate.query.passDistinctThrough", value = "false"))
    Page<StaffAvailability> findAllByOrderByDateDesc(Pageable pageable);

    // ── MVP 19: Hospital-scoped leave/absence queries ───────────
    @Query("SELECT sa FROM StaffAvailability sa WHERE sa.hospital.id = :hospitalId AND sa.date = :date AND sa.dayOff = true AND sa.active = true")
    List<StaffAvailability> findOnLeaveByHospitalAndDate(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId, @org.springframework.data.repository.query.Param("date") LocalDate date);

    @Query("SELECT sa FROM StaffAvailability sa WHERE sa.hospital.id = :hospitalId AND sa.date BETWEEN :from AND :to AND sa.dayOff = true AND sa.active = true")
    List<StaffAvailability> findOnLeaveByHospitalAndDateRange(@org.springframework.data.repository.query.Param("hospitalId") UUID hospitalId, @org.springframework.data.repository.query.Param("from") LocalDate from, @org.springframework.data.repository.query.Param("to") LocalDate to);
}
