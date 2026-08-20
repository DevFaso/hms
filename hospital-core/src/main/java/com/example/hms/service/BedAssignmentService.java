package com.example.hms.service;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.BedStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.model.Admission;
import com.example.hms.model.Bed;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.BedRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Structured bed assignment for admissions (P0 #4).
 * <p>
 * Owns the {@code Admission.bed} ↔ {@code Bed.status} invariant: a bed is
 * OCCUPIED exactly while an admission points at it. Shared by the admission
 * lifecycle and both discharge paths — mirrors
 * {@link EncounterAutoCompletionService}, the house pattern for
 * "on discharge do X" side effects.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BedAssignmentService {

    /** Admission states that can hold a bed. */
    private static final Set<AdmissionStatus> BED_HOLDING_STATUSES = Set.of(
        AdmissionStatus.PENDING, AdmissionStatus.ACTIVE,
        AdmissionStatus.ON_LEAVE, AdmissionStatus.AWAITING_DISCHARGE);

    private final BedRepository bedRepository;
    private final AdmissionRepository admissionRepository;

    /**
     * Assign a bed to an admission: the bed must be active, AVAILABLE, and
     * belong to the admission's hospital. Any previously assigned bed is
     * released. The caller saves the admission; the bed rows are saved here.
     */
    @Transactional
    public void assignBed(Admission admission, UUID bedId) {
        if (!BED_HOLDING_STATUSES.contains(admission.getStatus())) {
            throw new BusinessException("Beds can only be assigned to active admissions.");
        }
        UUID hospitalId = admission.getHospital().getId();
        // ── Tenant isolation: a bed from another hospital reads as not-found. ──
        Bed bed = bedRepository.findByIdAndWard_Hospital_Id(bedId, hospitalId)
            .orElseThrow(() -> new ResourceNotFoundException("Bed not found: " + bedId));

        Bed current = admission.getBed();
        if (current != null && current.getId().equals(bed.getId())) {
            return; // already assigned — idempotent
        }
        if (!bed.isActive() || bed.getStatus() != BedStatus.AVAILABLE) {
            throw new BusinessException("Bed " + bedLabel(bed) + " is not available.");
        }

        if (current != null) {
            markAvailable(current);
        }
        bed.setStatus(BedStatus.OCCUPIED);
        bedRepository.save(bed);

        admission.setBed(bed);
        // Keep the legacy free-text field in the "room/bed" shape the
        // worklists parse (DoctorWorklistServiceImpl splits on '/').
        admission.setRoomBed(bedLabel(bed));
        log.info("Bed {} assigned to admission {}", bedLabel(bed), admission.getId());
    }

    /**
     * Detach the admission's bed and make it available again. The caller
     * saves the admission. Used for explicit unassignment.
     */
    @Transactional
    public void unassignBed(Admission admission) {
        Bed bed = admission.getBed();
        if (bed == null) {
            return;
        }
        markAvailable(bed);
        admission.setBed(null);
        admission.setRoomBed(null);
        log.info("Bed {} unassigned from admission {}", bedLabel(bed), admission.getId());
    }

    /**
     * On discharge/cancel: free the bed but keep {@code Admission.bed} and
     * {@code roomBed} as the historical record of where the patient lay.
     */
    @Transactional
    public void releaseBed(Admission admission) {
        Bed bed = admission.getBed();
        if (bed == null) {
            return;
        }
        markAvailable(bed);
        log.info("Bed {} released by admission {}", bedLabel(bed), admission.getId());
    }

    /**
     * Discharge-approval path variant: that flow works off the patient's
     * hospital registration and never touches the Admission row directly, so
     * resolve any bed-holding admissions for the patient and release them.
     */
    @Transactional
    public void releaseBedsForPatient(UUID patientId, UUID hospitalId) {
        List<Admission> admissions = admissionRepository
            .findByPatient_IdAndHospital_IdAndStatusIn(patientId, hospitalId, BED_HOLDING_STATUSES);
        for (Admission admission : admissions) {
            releaseBed(admission);
        }
    }

    private void markAvailable(Bed bed) {
        if (bed.getStatus() == BedStatus.OCCUPIED) {
            bed.setStatus(BedStatus.AVAILABLE);
            bedRepository.save(bed);
        }
    }

    /** "WARDCODE/BEDNUMBER" — the same shape the worklist room/bed parser expects. */
    public static String bedLabel(Bed bed) {
        String wardCode = bed.getWard() != null ? bed.getWard().getCode() : "";
        return wardCode + "/" + bed.getBedNumber();
    }
}
