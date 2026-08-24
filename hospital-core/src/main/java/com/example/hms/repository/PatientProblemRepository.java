package com.example.hms.repository;

import com.example.hms.model.PatientProblem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface PatientProblemRepository extends JpaRepository<PatientProblem, UUID> {

    List<PatientProblem> findByPatient_Id(UUID patientId);

    List<PatientProblem> findByPatient_IdAndHospital_Id(UUID patientId, UUID hospitalId);

    /** One (code, display, count) row of the morbidity aggregation. */
    interface DiagnosisCount {
        String getCode();
        String getDisplay();
        long getTotal();
    }

    /**
     * Diagnoses recorded at a hospital in a window, ranked by count —
     * feeds the {@code TOP_DIAGNOSES} scheduled report. Windowed on
     * {@code createdAt} (when this hospital recorded the diagnosis),
     * NOT {@code onsetDate}, which is patient-reported, nullable, and
     * can predate the window by years. Status is deliberately ignored:
     * a problem resolved within the month was still treated that month.
     *
     * <p>Grouped by (code, display) rather than code alone so a code
     * recorded under two display spellings surfaces as two visible rows
     * instead of one row silently keeping an arbitrary label. The
     * display tie-break makes equal counts deterministic.
     */
    @Query("""
        select p.problemCode as code, p.problemDisplay as display, count(p) as total
        from PatientProblem p
        where p.hospital.id = :hospitalId
          and p.createdAt >= :fromInclusive
          and p.createdAt < :toExclusive
        group by p.problemCode, p.problemDisplay
        order by count(p) desc, p.problemDisplay asc
        """)
    List<DiagnosisCount> countDiagnosesRecordedInWindow(
        @Param("hospitalId") UUID hospitalId,
        @Param("fromInclusive") LocalDateTime fromInclusive,
        @Param("toExclusive") LocalDateTime toExclusive);

    /**
     * Same aggregation across EVERY hospital — the network-wide row of
     * the morbidity dashboard. Cross-tenant by construction, so the only
     * caller must already have established SUPER_ADMIN.
     */
    @Query("""
        select p.problemCode as code, p.problemDisplay as display, count(p) as total
        from PatientProblem p
        where p.createdAt >= :fromInclusive
          and p.createdAt < :toExclusive
        group by p.problemCode, p.problemDisplay
        order by count(p) desc, p.problemDisplay asc
        """)
    List<DiagnosisCount> countDiagnosesAcrossHospitals(
        @Param("fromInclusive") LocalDateTime fromInclusive,
        @Param("toExclusive") LocalDateTime toExclusive);

    /** One (hospital, code, display, count) row of the per-hospital split. */
    interface HospitalDiagnosisCount {
        UUID getHospitalId();
        String getHospitalName();
        String getCode();
        String getDisplay();
        long getTotal();
    }

    /**
     * The per-hospital breakdown — "malaria is highest at A, cholera at
     * B". One flat result ordered by hospital then count, which the
     * service groups; a per-hospital query would be N+1 against a table
     * that grows with every recorded problem.
     *
     * <p>Cross-tenant like {@link #countDiagnosesAcrossHospitals} — the
     * caller must have established SUPER_ADMIN before invoking it.
     */
    @Query("""
        select h.id as hospitalId, h.name as hospitalName,
               p.problemCode as code, p.problemDisplay as display, count(p) as total
        from PatientProblem p
        join p.hospital h
        where p.createdAt >= :fromInclusive
          and p.createdAt < :toExclusive
        group by h.id, h.name, p.problemCode, p.problemDisplay
        order by h.name asc, count(p) desc, p.problemDisplay asc
        """)
    List<HospitalDiagnosisCount> countDiagnosesByHospital(
        @Param("fromInclusive") LocalDateTime fromInclusive,
        @Param("toExclusive") LocalDateTime toExclusive);
}
