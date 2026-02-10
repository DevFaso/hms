package com.example.hms.repository;

import com.example.hms.model.Staff;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface StaffRepository extends JpaRepository<Staff, UUID> {
    @Query("SELECT s FROM Staff s WHERE s.user.username = :identifier OR s.licenseNumber = :identifier OR s.assignment.role.code = :identifier")
    java.util.Optional<Staff> findByUsernameOrLicenseOrRoleCode(@Param("identifier") String identifier);
    // Lookup staff by user email
    @Query("SELECT s FROM Staff s WHERE LOWER(s.user.email) = LOWER(:email)")
    List<Staff> findByUserEmail(@Param("email") String email);

    // Lookup staff by user phone number
    @Query("SELECT s FROM Staff s WHERE s.user.phoneNumber = :phone")
    List<Staff> findByUserPhoneNumber(@Param("phone") String phone);


    List<Staff> findByUserId(UUID userId);

    @Query("select s.licenseNumber from Staff s where s.user.id = :userId order by s.createdAt asc")
    Optional<String> findAnyLicenseByUserId(@Param("userId") UUID userId);

    Optional<Staff> findByIdAndActiveTrue(UUID id);
    boolean existsByIdAndActiveTrue(UUID id);

    boolean existsByIdAndHospital_IdAndActiveTrue(UUID id, UUID hospitalId);

    boolean existsByLicenseNumberAndUserId(String licenseNumber, UUID userId);
    boolean existsByLicenseNumber(String licenseNumber);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    Page<Staff> findByHospital_Id(UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    Page<Staff> findByHospital_IdAndActiveTrue(UUID hospitalId, Pageable pageable);

    @EntityGraph(attributePaths = {"user","department","assignment","assignment.role","hospital"})
    List<Staff> findByHospital_IdIn(Collection<UUID> hospitalIds);

    Optional<Staff> findByUserIdAndHospitalId(UUID userId, UUID hospitalId);

    Optional<Staff> findFirstByUserIdOrderByCreatedAtAsc(UUID userId);

    @Query("select s.user.id from Staff s where lower(s.licenseNumber) = lower(:license)")
    Optional<UUID> findUserIdByLicense(@Param("license") String license);

    @Query("""
        SELECT s FROM Staff s
        JOIN FETCH s.user u
        JOIN FETCH s.assignment a
        JOIN FETCH a.role r
        WHERE s.hospital.id = :hospitalId
          AND s.active = true
          AND (:roleCode IS NULL OR UPPER(r.code) = UPPER(:roleCode))
          AND (
                LOWER(COALESCE(s.name, '')) = LOWER(:identifier)
             OR LOWER(CONCAT(COALESCE(u.firstName, ''), ' ', COALESCE(u.lastName, ''))) = LOWER(:identifier)
             OR LOWER(u.username) = LOWER(:identifier)
             OR LOWER(COALESCE(u.email, '')) = LOWER(:identifier)
          )
    """)
    List<Staff> findActiveByHospitalAndRoleAndIdentifier(@Param("hospitalId") UUID hospitalId,
                                                         @Param("roleCode") String roleCode,
                                                         @Param("identifier") String identifier);

}

