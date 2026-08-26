package com.example.hms.repository;

import com.example.hms.model.StaffCredentialRenewal;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Credential renewal history (Tier 2 item 40).
 *
 * <p>Plain {@code JpaRepository} rather than the tenant-aware base: the rows
 * are always reached through a staff member whose hospital the caller has
 * already been checked against, and the expiry sweep runs on a scheduler with
 * no request context to scope by.
 */
@Repository
public interface StaffCredentialRenewalRepository extends JpaRepository<StaffCredentialRenewal, UUID> {

    /** One staff member's history, newest first — the only way it is read. */
    @EntityGraph(attributePaths = {"recordedBy"})
    @Query("""
        SELECT r FROM StaffCredentialRenewal r
        WHERE r.staff.id = :staffId
        ORDER BY r.recordedAt DESC
    """)
    List<StaffCredentialRenewal> findByStaffIdOrderByRecordedAtDesc(@Param("staffId") UUID staffId);
}
