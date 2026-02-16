package com.example.hms.repository;

import com.example.hms.model.Role;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RoleRepository extends JpaRepository<Role, UUID> {

    Optional<Role> findByCode(String code);

    Optional<Role> findByName(String name);

    Optional<Role> findByNameIgnoreCase(String roleName);

    @Query("SELECT DISTINCT r FROM Role r " +
        "LEFT JOIN FETCH r.permissions p " +
        "LEFT JOIN FETCH p.assignment a " +
        "LEFT JOIN FETCH a.role ar " +
        "LEFT JOIN FETCH a.hospital h")
    List<Role> findAllWithPermissions();

    @Query("SELECT r FROM Role r " +
        "LEFT JOIN FETCH r.permissions p " +
        "LEFT JOIN FETCH p.assignment a " +
        "LEFT JOIN FETCH a.role ar " +
        "LEFT JOIN FETCH a.hospital h " +
        "WHERE r.id = :id")
    Optional<Role> findByIdWithPermissions(@Param("id") UUID id);
}

