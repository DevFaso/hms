package com.example.hms.repository;

import com.example.hms.enums.ProblemChangeType;
import com.example.hms.model.PatientProblemHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientProblemHistoryRepository extends JpaRepository<PatientProblemHistory, UUID> {

    /**
     * QA D1186 class (round 22): the problem's latest change of the given kinds
     * (the status-changing ones: a later note edit does not undo a removal).
     */
    Optional<PatientProblemHistory> findFirstByProblem_IdAndChangeTypeInOrderByChangedAtDesc(
        UUID problemId, Collection<ProblemChangeType> changeTypes);
}
