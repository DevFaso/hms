package com.example.hms.repository;

import com.example.hms.model.UserRecoveryContact;
import com.example.hms.enums.RecoveryContactType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRecoveryContactRepository extends JpaRepository<UserRecoveryContact, UUID> {

    List<UserRecoveryContact> findByUserId(UUID userId);

    long countByUserId(UUID userId);

    long countByUserIdAndVerifiedTrue(UUID userId);

    boolean existsByUserIdAndPrimaryContactTrue(UUID userId);

    Optional<UserRecoveryContact> findByUserIdAndContactTypeAndContactValueIgnoreCase(UUID userId,
                                                                                      RecoveryContactType contactType,
                                                                                      String contactValue);
}
