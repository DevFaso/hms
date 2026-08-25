package com.example.hms.repository;

import com.example.hms.enums.IsolationPrecautionType;
import com.example.hms.model.IsolationPrecaution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Isolation precautions (Tier 2 item 32). Active means {@code endedAt IS NULL}. */
@Repository
public interface IsolationPrecautionRepository extends JpaRepository<IsolationPrecaution, UUID> {

    /**
     * Everything in force for one patient, newest first. The chart banner and
     * the transfer guard both read this.
     */
    @Query("SELECT p FROM IsolationPrecaution p "
        + "WHERE p.patient.id = :patientId AND p.endedAt IS NULL "
        + "ORDER BY p.startedAt DESC")
    List<IsolationPrecaution> findActiveForPatient(@Param("patientId") UUID patientId);

    /** The full history for one patient, in force or not — what contact tracing asks for. */
    @Query("SELECT p FROM IsolationPrecaution p "
        + "WHERE p.patient.id = :patientId ORDER BY p.startedAt DESC")
    List<IsolationPrecaution> findAllForPatient(@Param("patientId") UUID patientId);

    /**
     * The one active row of a given type for a patient, if any. Backs the
     * duplicate guard that the partial unique index enforces at the database.
     */
    @Query("SELECT p FROM IsolationPrecaution p "
        + "WHERE p.patient.id = :patientId AND p.precautionType = :type AND p.endedAt IS NULL")
    Optional<IsolationPrecaution> findActiveOfType(@Param("patientId") UUID patientId,
                                                   @Param("type") IsolationPrecautionType type);

    /**
     * Every active precaution in a hospital, in one query.
     *
     * <p>The bed board renders a whole ward at a time and needs the flag on
     * each occupant. Loading them per patient would be one query per bed —
     * the N+1 that makes a board unusable on a 60-bed ward.
     */
    @Query("SELECT p FROM IsolationPrecaution p "
        + "WHERE p.hospital.id = :hospitalId AND p.endedAt IS NULL")
    List<IsolationPrecaution> findActiveForHospital(@Param("hospitalId") UUID hospitalId);
}
