package com.example.hms.repository;

import com.example.hms.enums.ProblemChangeType;
import com.example.hms.model.PatientProblemHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface PatientProblemHistoryRepository extends JpaRepository<PatientProblemHistory, UUID> {

    /** QA D1186 class (round 22): whether the problem was ever removed (a DELETED history row). */
    boolean existsByProblem_IdAndChangeType(UUID problemId, ProblemChangeType changeType);
}
