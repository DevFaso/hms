package com.example.hms.repository;

import com.example.hms.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PermissionRepository extends JpaRepository<Permission, UUID>, JpaSpecificationExecutor<Permission> {

    Optional<Permission> findByName(String name);

    Optional<Permission> findByNameAndAssignment_Id(String name, UUID assignmentId);

    boolean existsByNameAndAssignment_Id(String name, UUID assignmentId);

    List<Permission> findByCodeIn(Collection<String> codes);

    List<Permission> findByAssignment_IdIn(Collection<UUID> assignmentIds);

    @Query("SELECT DISTINCT p FROM Permission p " +
           "LEFT JOIN FETCH p.assignment a " +
           "LEFT JOIN FETCH a.role r " +
           "LEFT JOIN FETCH a.hospital h")
    List<Permission> findAllWithAssignmentDetails();
}
