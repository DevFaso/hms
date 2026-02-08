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
}
