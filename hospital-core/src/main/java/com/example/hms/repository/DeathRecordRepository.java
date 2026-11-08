package com.example.hms.repository;

import com.example.hms.model.DeathRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeathRecordRepository extends JpaRepository<DeathRecord, UUID> {

    /**
     * A person dies once, backed by the partial unique index uq_death_record_patient_live. A row entered in
     * error is no death (QA D1488), so neither of these two sees it.
     */
    @Query("SELECT d FROM DeathRecord d WHERE d.patient.id = :patientId AND d.enteredInErrorAt IS NULL")
    Optional<DeathRecord> findByPatient_Id(@Param("patientId") UUID patientId);

    @Query("SELECT COUNT(d) > 0 FROM DeathRecord d WHERE d.patient.id = :patientId AND d.enteredInErrorAt IS NULL")
    boolean existsByPatient_Id(@Param("patientId") UUID patientId);

    /** Locked, and to be the first read of this record in the transaction: disputes and outcomes take turns on it. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d FROM DeathRecord d WHERE d.patient.id = :patientId AND d.enteredInErrorAt IS NULL")
    Optional<DeathRecord> lockLiveByPatientId(@Param("patientId") UUID patientId);

    /** Locked, ahead of the record's first read: see {@link #lockLiveByPatientId}. */
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT d.id FROM DeathRecord d WHERE d.id = :id")
    Optional<UUID> lockById(@Param("id") UUID id);

    /** QA D1488: the rows marked entered in error in a period, listed apart from the register's counts. */
    @Query("SELECT d FROM DeathRecord d WHERE d.hospital.id = :hospitalId AND d.enteredInErrorAt IS NOT NULL "
        + "AND d.diedAt >= :from AND d.diedAt < :to ORDER BY d.enteredInErrorAt DESC")
    List<DeathRecord> findEnteredInError(@Param("hospitalId") UUID hospitalId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /** QA D1516: this patient's deaths at a hospital marked entered in error, most recent first, for the chart. */
    @Query("SELECT d FROM DeathRecord d WHERE d.patient.id = :patientId AND d.hospital.id = :hospitalId "
        + "AND d.enteredInErrorAt IS NOT NULL ORDER BY d.enteredInErrorAt DESC")
    List<DeathRecord> findEnteredInErrorForPatient(@Param("patientId") UUID patientId,
                                                   @Param("hospitalId") UUID hospitalId);

    /** The mortality register for a period. */
    @Query("SELECT d FROM DeathRecord d WHERE d.hospital.id = :hospitalId AND d.enteredInErrorAt IS NULL "
        + "AND d.diedAt >= :from AND d.diedAt < :to ORDER BY d.diedAt DESC")
    List<DeathRecord> findRegister(@Param("hospitalId") UUID hospitalId,
                                   @Param("from") LocalDateTime from,
                                   @Param("to") LocalDateTime to);

    /**
     * Maternal deaths in a period, WHO definition.
     *
     * <p>LATE_MATERNAL is excluded in the query rather than filtered in Java,
     * because this count feeds the DHIS2 maternal mortality indicator and a
     * late maternal death is reported separately. Including it here would
     * overstate the facility's ratio.
     */
    @Query("SELECT d FROM DeathRecord d WHERE d.hospital.id = :hospitalId AND d.enteredInErrorAt IS NULL "
        + "AND d.maternalDeath = true "
        + "AND d.maternalDeathTiming <> com.example.hms.enums.MaternalDeathTiming.LATE_MATERNAL "
        + "AND d.diedAt >= :from AND d.diedAt < :to ORDER BY d.diedAt DESC")
    List<DeathRecord> findMaternalDeaths(@Param("hospitalId") UUID hospitalId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    /**
     * The WHO perinatal numerator (CR-07), the query's copy of {@link DeathRecord#isInWhoPerinatalNumerator()}. A late
     * neonatal death is past the period and a known gestation under 22 weeks is before it. Unknown gestation stays in.
     */
    @Query("SELECT d FROM DeathRecord d WHERE d.hospital.id = :hospitalId AND d.enteredInErrorAt IS NULL "
        + "AND d.perinatalDeath = true "
        + "AND d.perinatalType IN (com.example.hms.enums.PerinatalDeathType.STILLBIRTH, "
        + "com.example.hms.enums.PerinatalDeathType.EARLY_NEONATAL) "
        + "AND (d.gestationalAgeWeeks IS NULL OR d.gestationalAgeWeeks >= "
        + com.example.hms.enums.PerinatalDeathType.WHO_PERINATAL_MIN_GESTATION_WEEKS + ") "
        + "AND d.diedAt >= :from AND d.diedAt < :to ORDER BY d.diedAt DESC")
    List<DeathRecord> findPerinatalDeaths(@Param("hospitalId") UUID hospitalId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}
