package com.example.hms.repository;

import com.example.hms.enums.AdmissionType;
import com.example.hms.model.AdmissionOrderSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for AdmissionOrderSet entity
 */
@Repository
public interface AdmissionOrderSetRepository extends JpaRepository<AdmissionOrderSet, UUID> {

    /**
     * Find active order sets by hospital
     */
    List<AdmissionOrderSet> findByHospitalIdAndActiveOrderByNameAsc(UUID hospitalId, Boolean active);

    /**
     * Find order sets by admission type
     */
    List<AdmissionOrderSet> findByHospitalIdAndAdmissionTypeAndActiveOrderByNameAsc(
        UUID hospitalId, 
        AdmissionType admissionType, 
        Boolean active
    );

    /**
     * Find order sets by department
     */
    List<AdmissionOrderSet> findByDepartmentIdAndActiveOrderByNameAsc(UUID departmentId, Boolean active);

    /**
     * Find order sets by name (search)
     */
    @Query("SELECT aos FROM AdmissionOrderSet aos WHERE aos.hospital.id = :hospitalId " +
           "AND LOWER(aos.name) LIKE LOWER(CONCAT('%', :searchTerm, '%')) " +
           "AND aos.active = true ORDER BY aos.name ASC")
    List<AdmissionOrderSet> searchByName(@Param("hospitalId") UUID hospitalId, @Param("searchTerm") String searchTerm);

    /**
     * Count active order sets by hospital
     */
    Long countByHospitalIdAndActive(UUID hospitalId, Boolean active);

    /**
     * Find latest version of order set by name
     */
    @Query("SELECT aos FROM AdmissionOrderSet aos WHERE aos.hospital.id = :hospitalId " +
           "AND aos.name = :name ORDER BY aos.version DESC LIMIT 1")
    AdmissionOrderSet findLatestVersionByName(@Param("hospitalId") UUID hospitalId, @Param("name") String name);

    /**
     * Find all versions of an order set by name
     */
    @Query("SELECT aos FROM AdmissionOrderSet aos WHERE aos.hospital.id = :hospitalId " +
           "AND aos.name = :name ORDER BY aos.version DESC")
    List<AdmissionOrderSet> findAllVersionsByName(@Param("hospitalId") UUID hospitalId, @Param("name") String name);

    /**
     * Find the currently-active row for a given (hospital, name).
     * The picker uses this when an admin authors an order set with the
     * same name as a deactivated predecessor — only one row per (hospital,
     * name) should be active at a time, enforced by the service.
     */
    @Query("SELECT aos FROM AdmissionOrderSet aos WHERE aos.hospital.id = :hospitalId " +
           "AND aos.name = :name AND aos.active = true")
    java.util.Optional<AdmissionOrderSet> findActiveByHospitalAndName(
        @Param("hospitalId") UUID hospitalId, @Param("name") String name);

    /**
     * Walk the version chain anchored at the given order set, climbing
     * through {@code parentOrderSet} (newest → oldest). The query returns
     * the head row plus every ancestor reachable through the FK so the
     * UI can show "Version 3 by Alice (current) — Version 2 by Bob —
     * Version 1 by Carol".
     */
    @Query(value = """
        WITH RECURSIVE chain(id, version, parent_order_set_id, name, active,
                             created_at, created_by_staff_id) AS (
            SELECT id, version, parent_order_set_id, name, active,
                   created_at, created_by_staff_id
                FROM admission_order_sets
                WHERE id = :anchorId
            UNION ALL
            SELECT a.id, a.version, a.parent_order_set_id, a.name, a.active,
                   a.created_at, a.created_by_staff_id
                FROM admission_order_sets a
                JOIN chain c ON c.parent_order_set_id = a.id
        )
        SELECT aos.* FROM admission_order_sets aos
            JOIN chain ON chain.id = aos.id
            ORDER BY aos.version DESC
        """, nativeQuery = true)
    List<AdmissionOrderSet> findVersionChain(@Param("anchorId") UUID anchorId);
}
