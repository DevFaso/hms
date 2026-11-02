package com.example.hms.repository;

import com.example.hms.model.PatientVitalSign;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PatientVitalSignRepository extends JpaRepository<PatientVitalSign, UUID> {

    Optional<PatientVitalSign> findFirstByPatient_IdOrderByRecordedAtDesc(UUID patientId);

    Optional<PatientVitalSign> findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc(UUID patientId, UUID hospitalId);

    /**
     * QA D1181 class (round 22): the patient's observation sets here, latest
     * first - rows carrying at least one of the readings the vitals round
     * checks (temperature, pulse, respiratory rate, blood pressure, SpO2,
     * consciousness). A weight, a height, a glucose or a pain score alone is
     * a measurement, not a round: it must not restart the round's clock.
     */
    @Query("""
        SELECT v FROM PatientVitalSign v
        WHERE v.patient.id = :patientId AND v.hospital.id = :hospitalId
          AND (v.temperatureCelsius IS NOT NULL OR v.heartRateBpm IS NOT NULL
               OR v.respiratoryRateBpm IS NOT NULL OR v.systolicBpMmHg IS NOT NULL
               OR v.diastolicBpMmHg IS NOT NULL OR v.spo2Percent IS NOT NULL
               OR v.consciousnessLevel IS NOT NULL)
        ORDER BY v.recordedAt DESC
        """)
    List<PatientVitalSign> findObservationSets(@Param("patientId") UUID patientId,
                                               @Param("hospitalId") UUID hospitalId,
                                               Pageable page);

    /** The latest observation set ({@link #findObservationSets}): what the vitals round's clock runs from. */
    default Optional<PatientVitalSign> findLatestObservationSet(UUID patientId, UUID hospitalId) {
        return findObservationSets(patientId, hospitalId, org.springframework.data.domain.PageRequest.of(0, 1))
            .stream().findFirst();
    }

    /** Round 18 (QA D935): the latest row that records a weight, for the paediatric dose check. */
    Optional<PatientVitalSign> findFirstByPatient_IdAndHospital_IdAndWeightKgIsNotNullOrderByRecordedAtDesc(
        UUID patientId, UUID hospitalId);

    /** Round 18 (QA D935): as above, without a hospital scope (the CDS hooks' unscoped calls). */
    Optional<PatientVitalSign> findFirstByPatient_IdAndWeightKgIsNotNullOrderByRecordedAtDesc(UUID patientId);

    List<PatientVitalSign> findByPatient_IdOrderByRecordedAtDesc(UUID patientId, Pageable pageable);

    List<PatientVitalSign> findByPatient_IdAndHospital_IdOrderByRecordedAtDesc(UUID patientId, UUID hospitalId, Pageable pageable);

    /** E9 #60 — vitals across the readable hospitals ({@code RecordAccessPolicy.readableHospitalIds}). */
    List<PatientVitalSign> findByPatient_IdAndHospital_IdInOrderByRecordedAtDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, Pageable pageable);

    /**
     * Page-returning variant used by the FHIR {@code $everything}
     * operation so {@code Page.hasNext()} can drive the
     * {@code Bundle.link[next]} continuation. The list-returning
     * sibling above stays in place for callers that don't need
     * overflow detection.
     */
    Page<PatientVitalSign> findPageByPatient_IdAndHospital_IdOrderByRecordedAtDesc(
        UUID patientId, UUID hospitalId, Pageable pageable);

    /** E9 #60b — the paged readable-set sibling, for FHIR {@code $everything}. */
    Page<PatientVitalSign> findPageByPatient_IdAndHospital_IdInOrderByRecordedAtDesc(
        UUID patientId, java.util.Collection<UUID> hospitalIds, Pageable pageable);

        @Query("""
                SELECT v FROM PatientVitalSign v
                WHERE v.patient.id = :patientId
                    AND (:hospitalId IS NULL OR v.hospital.id = :hospitalId)
                    AND v.recordedAt >= COALESCE(:from, v.recordedAt)
                    AND v.recordedAt <= COALESCE(:to, v.recordedAt)
                ORDER BY v.recordedAt DESC
        """)
    List<PatientVitalSign> findWithinRange(@Param("patientId") UUID patientId,
                                           @Param("hospitalId") UUID hospitalId,
                                           @Param("from") LocalDateTime from,
                                           @Param("to") LocalDateTime to,
                                           Pageable pageable);

    /**
     * Every row carrying at least one anthropometric value, oldest first —
     * the growth-chart series. Vitals rows are sparse measurement bundles,
     * so most rows (BP-only, temp-only, …) carry none and are skipped here
     * rather than plotted as gaps.
     */
    @Query("""
            SELECT v FROM PatientVitalSign v
            WHERE v.patient.id = :patientId
                AND (:hospitalId IS NULL OR v.hospital.id = :hospitalId)
                AND (v.weightKg IS NOT NULL
                     OR v.heightCm IS NOT NULL
                     OR v.headCircumferenceCm IS NOT NULL)
            ORDER BY v.recordedAt ASC
    """)
    List<PatientVitalSign> findGrowthSeries(@Param("patientId") UUID patientId,
                                            @Param("hospitalId") UUID hospitalId);
}
