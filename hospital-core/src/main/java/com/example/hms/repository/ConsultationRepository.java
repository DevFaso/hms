package com.example.hms.repository;

import com.example.hms.enums.ConsultationStatus;
import com.example.hms.model.Consultation;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface ConsultationRepository extends JpaRepository<Consultation, UUID> {

    /*
     * Common attribute graph used by the read endpoints. Eagerly loads every
     * @ManyToOne association the response mapper dereferences (patient,
     * hospital, requesting/consultant staff, encounter) so a single SQL
     * round-trip materialises everything via LEFT OUTER JOIN. Without this,
     * the mapper triggers per-row lazy proxy initialisation; any dangling FK
     * (e.g. a Patient hard-deleted while a Consultation still references it)
     * raises EntityNotFoundException → 500 "A referenced record could not be found".
     *
     * NOTE: `patient.hospitalRegistrations` is *deliberately not* in this graph.
     * It is a nested @OneToMany collection; including it caused list endpoints
     * to return [] (rows silently filtered) — likely a Hibernate 6 quirk
     * combining nested collection fetch with derived-method ORDER BY.
     * The MRN computed in `ConsultationServiceImpl.toResponseDTO` falls back
     * to a lazy load (already wrapped in try/catch for dangling-FK safety).
     */
    String LIST_GRAPH_PATIENT = "patient";
    String LIST_GRAPH_HOSPITAL = "hospital";
    String LIST_GRAPH_REQUESTER = "requestingProvider";
    String LIST_GRAPH_CONSULTANT = "consultant";
    String LIST_GRAPH_ENCOUNTER = "encounter";

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findByPatient_IdOrderByRequestedAtDesc(UUID patientId);

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findByHospital_IdAndStatusOrderByRequestedAtDesc(UUID hospitalId, ConsultationStatus status);

    /**
     * Hospital-scoped list with NO status filter — used by the
     * {@code GET /api/consultations} hospital-scoped path when the caller
     * passes no {@code status} param. Previously the code fell through to
     * {@link #findByHospitalAndStatuses} with the 4 "active" statuses
     * hard-coded, which silently hid {@code COMPLETED}/{@code CANCELLED}/
     * {@code DECLINED} rows even though the dashboard tile counts them.
     * The mismatch surfaced as "Dashboard says 3 Consultations, list shows 0"
     * for super-admins viewing a hospital with only completed work and for
     * any hospital-admin whose tenant has no active consultations.
     */
    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findByHospital_IdOrderByRequestedAtDesc(UUID hospitalId);

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findByRequestingProvider_IdOrderByRequestedAtDesc(UUID providerId);

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findByConsultant_IdAndStatusOrderByRequestedAtDesc(UUID consultantId, ConsultationStatus status);

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findByConsultant_IdOrderByRequestedAtDesc(UUID consultantId);

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findByStatusOrderByRequestedAtDesc(ConsultationStatus status);

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    List<Consultation> findAllByOrderByRequestedAtDesc();

    @EntityGraph(attributePaths = {LIST_GRAPH_PATIENT, LIST_GRAPH_HOSPITAL, LIST_GRAPH_REQUESTER, LIST_GRAPH_CONSULTANT, LIST_GRAPH_ENCOUNTER})
    @Query("SELECT c FROM Consultation c WHERE c.hospital.id = :hospitalId " +
           "AND c.status IN :statuses ORDER BY c.urgency DESC, c.requestedAt ASC")
    List<Consultation> findByHospitalAndStatuses(
        @Param("hospitalId") UUID hospitalId,
        @Param("statuses") List<ConsultationStatus> statuses
    );

    @Query("SELECT c FROM Consultation c WHERE c.slaDueBy < :now AND c.status NOT IN :completedStatuses")
    List<Consultation> findOverdueConsultations(
        @Param("now") LocalDateTime now,
        @Param("completedStatuses") List<ConsultationStatus> completedStatuses
    );

    /**
     * Hospital-scoped tile count for the super-admin dashboard. Used
     * when the chip is pinned to a specific hospital so the dashboard
     * tile agrees with the {@code /api/consultations} list page.
     * For "All hospitals" view the orchestrator falls back to the
     * unscoped {@link #count()}.
     */
    long countByHospital_Id(UUID hospitalId);
}
