package com.example.hms.repository;

import com.example.hms.model.PasswordHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface PasswordHistoryRepository extends JpaRepository<PasswordHistory, UUID> {

    /**
     * Returns the most recent password history entries for a user, ordered newest first.
     */
    List<PasswordHistory> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
