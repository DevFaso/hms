package com.example.hms.repository;

import com.example.hms.enums.InteractionSeverity;
import com.example.hms.model.medication.DrugInteraction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DrugInteractionRepository extends JpaRepository<DrugInteraction, UUID> {

    /**
     * Find interaction between two drugs (bidirectional search).
     * Checks both (drug1, drug2) and (drug2, drug1) combinations.
     */
    @Query("SELECT di FROM DrugInteraction di WHERE di.active = true AND " +
           "((di.drug1Code = :code1 AND di.drug2Code = :code2) OR " +
           " (di.drug1Code = :code2 AND di.drug2Code = :code1))")
    Optional<DrugInteraction> findInteractionBetween(
        @Param("code1") String drugCode1,
        @Param("code2") String drugCode2
    );

    /**
     * Find all interactions involving a specific drug.
     */
    @Query("SELECT di FROM DrugInteraction di WHERE di.active = true AND " +
           "(di.drug1Code = :drugCode OR di.drug2Code = :drugCode)")
    List<DrugInteraction> findInteractionsForDrug(@Param("drugCode") String drugCode);

    /**
     * Find all interactions for multiple drugs (for concurrent medication checking).
     */
    @Query("SELECT di FROM DrugInteraction di WHERE di.active = true AND " +
           "((di.drug1Code IN :drugCodes AND di.drug2Code IN :drugCodes))")
    List<DrugInteraction> findInteractionsAmongDrugs(@Param("drugCodes") List<String> drugCodes);

    /**
     * Find all contraindicated interactions.
     */
    List<DrugInteraction> findBySeverityAndActiveOrderByDrug1NameAsc(
        InteractionSeverity severity,
        boolean active
    );

    /**
     * Find interactions by severity level.
     */
    List<DrugInteraction> findBySeverityInAndActiveOrderBySeverityAsc(
        List<InteractionSeverity> severities,
        boolean active
    );

    /**
     * Find interactions from a specific source database.
     */
    List<DrugInteraction> findBySourceDatabaseAndActiveOrderBySeverityAsc(
        String sourceDatabase,
        boolean active
    );

    /**
     * Count active interactions in the database.
     */
    long countByActive(boolean active);

    /**
     * Find interactions by external reference ID.
     */
    Optional<DrugInteraction> findByExternalReferenceId(String externalReferenceId);
}
