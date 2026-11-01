package com.example.hms.repository;

import com.example.hms.model.PatientProblemHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientProblemHistoryRepository extends JpaRepository<PatientProblemHistory, UUID> {

    /** QA D1186 class (round 22): the problem's latest change (a removal is the last DELETED row). */
    Optional<PatientProblemHistory> findFirstByProblem_IdOrderByChangedAtDesc(UUID problemId);
}
