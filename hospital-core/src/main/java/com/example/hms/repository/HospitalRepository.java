package com.example.hms.repository;

import com.example.hms.model.Hospital;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HospitalRepository extends JpaRepository<Hospital, UUID> {
    @Query("SELECT h FROM Hospital h WHERE LOWER(h.name) = LOWER(:identifier) OR LOWER(h.code) = LOWER(:identifier) OR LOWER(h.email) = LOWER(:identifier)")
    java.util.Optional<Hospital> findByNameOrCodeOrEmail(@Param("identifier") String identifier);

    boolean existsByNameIgnoreCaseAndZipCode(String name, String zipCode);

    @Query("SELECT h FROM Hospital h WHERE " +
            "(:name IS NULL OR LOWER(h.name) LIKE LOWER(CONCAT('%', :name, '%'))) AND " +
            "(:city IS NULL OR LOWER(h.city) LIKE LOWER(CONCAT('%', :city, '%'))) AND " +
            "(:state IS NULL OR LOWER(h.state) LIKE LOWER(CONCAT('%', :state, '%'))) AND " +
            "(:active IS NULL OR h.active = :active)")
    Page<Hospital> searchHospitals(@Param("name") String name,
                                   @Param("city") String city,
                                   @Param("state") String state,
                                   @Param("active") Boolean active,
                                   Pageable pageable);

    Optional<Hospital> findByCodeIgnoreCase(String code);

    Optional<Hospital> findByNameIgnoreCase(String hospitalName);

    Optional<Hospital> findByName(String name);

    /* Dashboard count */
    long countByActiveTrue();

    /* Organization-related queries */
    List<Hospital> findByOrganizationIsNull();

    List<Hospital> findByOrganizationIdOrderByNameAsc(UUID organizationId);

    @Query("""
      SELECT DISTINCT h FROM Hospital h
      LEFT JOIN FETCH h.departments d
      LEFT JOIN FETCH d.headOfDepartment hod
      LEFT JOIN FETCH hod.user u
      WHERE (:activeOnly IS NULL OR h.active = :activeOnly)
    AND (
      :hospitalQuery IS NULL OR :hospitalQuery = '' OR
      LOWER(CAST(h.name AS string)) LIKE LOWER(CONCAT('%', CAST(:hospitalQuery AS string), '%')) OR
      LOWER(CAST(h.code AS string)) LIKE LOWER(CONCAT('%', CAST(:hospitalQuery AS string), '%')) OR
      LOWER(COALESCE(CAST(h.city AS string), '')) LIKE LOWER(CONCAT('%', CAST(:hospitalQuery AS string), '%'))
    )
    """)
    List<Hospital> findAllWithDepartments(@Param("hospitalQuery") String hospitalQuery,
                            @Param("activeOnly") Boolean activeOnly);

    @Query("""
      SELECT h FROM Hospital h
      WHERE (:organizationId IS NULL OR h.organization.id = :organizationId)
        AND (:unassignedOnly IS NULL OR :unassignedOnly = false OR h.organization IS NULL)
        AND (
            :city IS NULL
            OR LOWER(COALESCE(CAST(h.city AS string), '')) LIKE LOWER(CONCAT('%', CAST(:city AS string), '%'))
        )
        AND (
            :state IS NULL
            OR LOWER(COALESCE(CAST(h.state AS string), '')) LIKE LOWER(CONCAT('%', CAST(:state AS string), '%'))
        )
      ORDER BY LOWER(CAST(h.name AS string))
    """)
    List<Hospital> findAllForFilters(@Param("organizationId") UUID organizationId,
                                     @Param("unassignedOnly") Boolean unassignedOnly,
                                     @Param("city") String city,
                                     @Param("state") String state);
}

