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

    /**
     * QA D902 (#968 review): the role rows with these codes, locked
     * ({@code SELECT ... FOR UPDATE}) until the transaction ends. Every change
     * that would take a live super-admin away takes this one lock before it
     * counts the others, so two such changes run one after the other and the
     * second counts with what the first committed. Locking the assignment rows
     * alone was not enough: deactivating an ACCOUNT changes only its users
     * row, which that lock never covered, so two super-admins switching each
     * other's account off at the same moment both passed. Ordered by id, so
     * every caller takes the locks in the same order.
     */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r WHERE UPPER(r.code) IN (:codes) ORDER BY r.id")
    List<Role> lockRolesWithCodes(@Param("codes") java.util.Set<String> codes);
}

