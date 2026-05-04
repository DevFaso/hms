package com.example.hms.repository.platform;

import com.example.hms.model.platform.AuditSavedSearch;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AuditSavedSearchRepository extends JpaRepository<AuditSavedSearch, UUID> {

    List<AuditSavedSearch> findByOwnerUsernameOrderByNameAsc(String ownerUsername);

    Optional<AuditSavedSearch> findByOwnerUsernameAndName(String ownerUsername, String name);

    /**
     * Listing query for "my searches" + "shared by other super admins".
     * Postgres treats {@code shared = true} rows from another owner as
     * visible; the operator's own searches are always included whether
     * shared or not.
     */
    @Query("""
        SELECT s FROM AuditSavedSearch s
         WHERE s.ownerUsername = :owner
            OR s.shared = true
         ORDER BY s.ownerUsername, s.name
        """)
    List<AuditSavedSearch> findOwnedAndShared(@Param("owner") String owner);
}
