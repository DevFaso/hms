package com.example.hms.repository;

import com.example.hms.enums.PanelAssignmentStatus;
import com.example.hms.enums.PanelRole;
import com.example.hms.model.PanelAssignment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PanelAssignmentRepository extends JpaRepository<PanelAssignment, UUID> {

    /** The one ACTIVE owner of this role, if any — V149's partial unique index guarantees at most one. */
    Optional<PanelAssignment> findByPatient_IdAndHospital_IdAndPanelRoleAndStatus(
            UUID patientId, UUID hospitalId, PanelRole panelRole, PanelAssignmentStatus status);

    /** Everything for the chart card: current owners and reassignment history, newest first. */
    @EntityGraph(attributePaths = {"providerStaff", "assignedBy"})
    List<PanelAssignment> findByPatient_IdAndHospital_IdOrderByAssignedOnDescCreatedAtDesc(
            UUID patientId, UUID hospitalId);

    /** One provider's live panel — the worklist page. Patient fetched for the row rendering. */
    @EntityGraph(attributePaths = {"patient", "providerStaff"})
    Page<PanelAssignment> findByProviderStaff_IdAndHospital_IdAndStatusOrderByAssignedOnDesc(
            UUID providerStaffId, UUID hospitalId, PanelAssignmentStatus status, Pageable pageable);

    /** Role-filtered worklist: the overview drills into one (provider, role) pair. */
    @EntityGraph(attributePaths = {"patient", "providerStaff"})
    Page<PanelAssignment> findByProviderStaff_IdAndHospital_IdAndPanelRoleAndStatusOrderByAssignedOnDesc(
            UUID providerStaffId, UUID hospitalId, PanelRole panelRole,
            PanelAssignmentStatus status, Pageable pageable);

    /**
     * Admin overview: every provider with at least one ACTIVE assignment and
     * their live panel size, biggest panels first.
     */
    @Query("""
        select a.providerStaff.id, a.providerStaff.name, a.panelRole, count(a)
        from PanelAssignment a
        where a.hospital.id = :hospitalId and a.status = com.example.hms.enums.PanelAssignmentStatus.ACTIVE
        group by a.providerStaff.id, a.providerStaff.name, a.panelRole
        order by count(a) desc
    """)
    List<Object[]> activePanelSizes(@Param("hospitalId") UUID hospitalId);
}
