package com.example.hms.repository.empi;

import com.example.hms.model.empi.EmpiMergeEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface EmpiMergeEventRepository extends JpaRepository<EmpiMergeEvent, UUID> {

    Optional<EmpiMergeEvent> findTopBySecondaryIdentity_IdOrderByMergedAtDesc(UUID secondaryIdentityId);
}
