package com.example.hms.repository;

import com.example.hms.model.Organization;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Read queries for the tenant data export.
 *
 * <p>Separate from the operational repositories on purpose, for two reasons.
 *
 * <p><b>1. Tenant scope must come from the argument, not the context.</b>
 * {@code PatientRepository} filters on
 * {@code @tenantContext.effectiveHospitalIds()}, which resolves from the
 * security context — and the export runs from
 * {@code TenantPurgeExecutor}, a scheduled job with no principal on the
 * thread. Reusing those finders would make the export's scope depend on
 * something that is not set, which for an archive taken immediately before
 * deleting a tenant is the worst place in the product to get scope wrong.
 * Every query here takes the hospital ids explicitly.
 *
 * <p><b>2. Scalar projections, not entities.</b> The export must not
 * dereference lazy associations: it would N+1 across the whole tenant, and a
 * single dangling FK anywhere in years of history would abort the archive.
 * These select the columns the NDJSON needs and nothing else.
 *
 * <p>Every query orders by id so the archive is byte-reproducible across runs
 * — the same reason {@code writeHospitalsNdjson} sorts its hospitals.
 */
@Repository
public interface TenantExportRepository extends JpaRepository<Organization, UUID> {

    /**
     * Patients with any registration at one of the given hospitals, or whose
     * denormalised {@code hospitalId} points at one.
     *
     * <p>DISTINCT because a patient registered at two of the organisation's
     * hospitals must appear once, not twice.
     */
    @Query("SELECT DISTINCT p.id, p.firstName, p.lastName, p.dateOfBirth, p.gender, "
        + "       p.phoneNumberPrimary, p.email, p.city, p.country "
        + "FROM Patient p LEFT JOIN p.hospitalRegistrations r "
        + "WHERE p.hospitalId IN :hospitalIds OR r.hospital.id IN :hospitalIds "
        + "ORDER BY p.id")
    List<Object[]> exportPatients(@Param("hospitalIds") Collection<UUID> hospitalIds, Pageable pageable);

    @Query("SELECT s.id, s.user.firstName, s.user.lastName, s.user.email, "
        + "       s.jobTitle, s.specialization, s.hospital.id "
        + "FROM Staff s WHERE s.hospital.id IN :hospitalIds ORDER BY s.id")
    List<Object[]> exportStaff(@Param("hospitalIds") Collection<UUID> hospitalIds, Pageable pageable);

    @Query("SELECT e.id, e.patient.id, e.hospital.id, e.encounterType, e.encounterDate, "
        + "       e.status, e.chiefComplaint "
        + "FROM Encounter e WHERE e.hospital.id IN :hospitalIds ORDER BY e.id")
    List<Object[]> exportEncounters(@Param("hospitalIds") Collection<UUID> hospitalIds, Pageable pageable);

    @Query("SELECT a.id, a.patient.id, a.staff.id, a.hospital.id, a.appointmentDate, "
        + "       a.startTime, a.endTime, a.status, a.reason "
        + "FROM Appointment a WHERE a.hospital.id IN :hospitalIds ORDER BY a.id")
    List<Object[]> exportAppointments(@Param("hospitalIds") Collection<UUID> hospitalIds, Pageable pageable);

    /**
     * Audit events attributable to one of the organisation's hospitals.
     *
     * <p>Scoped through {@code assignment.hospital.id} rather than the
     * denormalised {@code hospitalName}: two hospitals can share a display
     * name, and a rename would split the history. Rows with no assignment
     * (SYSTEM actors — schedulers, MLLP, Kafka consumers) carry no per-tenant
     * attribution and are deliberately out of scope for a tenant's own export.
     *
     * <p>{@code patientId} comes from the column V141 added, so the archive
     * can say which events concerned which patient.
     */
    @Query("SELECT ev.id, ev.eventType, ev.eventTimestamp, ev.userName, ev.roleName, "
        + "       ev.entityType, ev.resourceId, ev.patientId, ev.status "
        + "FROM AuditEventLog ev WHERE ev.assignment.hospital.id IN :hospitalIds "
        + "ORDER BY ev.id")
    List<Object[]> exportAuditEvents(@Param("hospitalIds") Collection<UUID> hospitalIds, Pageable pageable);
}
