package com.example.hms.repository;

import com.example.hms.model.StaffAvailability;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffAvailabilityRepository extends JpaRepository<StaffAvailability, UUID> {
    Optional<StaffAvailability> findByStaff_IdAndDate(UUID staffId, LocalDate date);
    boolean existsByStaff_IdAndDate(UUID staffId, LocalDate date);

    @EntityGraph(attributePaths = {
        "staff",
        "staff.user",
        "staff.department",
        "staff.department.departmentTranslations",
        "hospital"
    })
    Page<StaffAvailability> findAllByOrderByDateDesc(Pageable pageable);
}
