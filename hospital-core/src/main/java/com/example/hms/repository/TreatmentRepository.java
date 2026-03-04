package com.example.hms.repository;

import com.example.hms.model.Treatment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TreatmentRepository extends JpaRepository<Treatment, UUID> {

    // 🔍 Find by hospital ID
    List<Treatment> findByHospital_Id(UUID hospitalId);

    // 🔍 Search by name
    List<Treatment> findByNameContainingIgnoreCase(String name);

    // 🔍 Existing method: load assignment (not enough for creatorName fix)
    @Query("SELECT t FROM Treatment t LEFT JOIN FETCH t.assignment WHERE t.id = :id")
    Optional<Treatment> findWithAssignmentById(@Param("id") UUID id);

    // ✅ NEW: fully loads assignment + user for creator name
    @Query("SELECT t FROM Treatment t " +
            "JOIN FETCH t.assignment a " +
            "JOIN FETCH a.user " +
            "WHERE t.id = :id")
    Optional<Treatment> findWithAssignmentAndUserById(@Param("id") UUID id);

    // ✅ NEW: loads all treatments with creator user
    @Query("SELECT t FROM Treatment t " +
            "JOIN FETCH t.assignment a " +
            "JOIN FETCH a.user")
    List<Treatment> findAllWithAssignmentAndUser();

    // ✅ NEW: loads all treatments with creator user, scoped to hospital
    @Query("SELECT t FROM Treatment t " +
            "JOIN FETCH t.assignment a " +
            "JOIN FETCH a.user " +
            "WHERE t.hospital.id = :hospitalId")
    List<Treatment> findAllWithAssignmentAndUserByHospitalId(@Param("hospitalId") UUID hospitalId);
}
