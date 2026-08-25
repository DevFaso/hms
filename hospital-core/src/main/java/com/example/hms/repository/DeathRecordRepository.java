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

    /** A person dies once — backed by the unique index uq_death_record_patient. */
    Optional<DeathRecord> findByPatient_Id(UUID patientId);

    boolean existsByPatient_Id(UUID patientId);

    /** The mortality register for a period. */
    @Query("SELECT d FROM DeathRecord d WHERE d.hospital.id = :hospitalId "
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
    @Query("SELECT d FROM DeathRecord d WHERE d.hospital.id = :hospitalId "
        + "AND d.maternalDeath = true "
        + "AND d.maternalDeathTiming <> com.example.hms.enums.MaternalDeathTiming.LATE_MATERNAL "
        + "AND d.diedAt >= :from AND d.diedAt < :to ORDER BY d.diedAt DESC")
    List<DeathRecord> findMaternalDeaths(@Param("hospitalId") UUID hospitalId,
                                         @Param("from") LocalDateTime from,
                                         @Param("to") LocalDateTime to);

    @Query("SELECT d FROM DeathRecord d WHERE d.hospital.id = :hospitalId "
        + "AND d.perinatalDeath = true "
        + "AND d.diedAt >= :from AND d.diedAt < :to ORDER BY d.diedAt DESC")
    List<DeathRecord> findPerinatalDeaths(@Param("hospitalId") UUID hospitalId,
                                          @Param("from") LocalDateTime from,
                                          @Param("to") LocalDateTime to);
}
