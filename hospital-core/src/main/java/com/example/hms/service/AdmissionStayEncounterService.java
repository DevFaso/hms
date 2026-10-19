package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Opens the INPATIENT encounter of a hospital stay (QA D98, D135).
 *
 * <p>An admission used to be a row with no encounter, so the stay had nowhere
 * to hang progress notes, the discharge summary or the after-visit summary,
 * and the admission response carried {@code currentEncounterId: null}. The
 * #881 round fixed the encounter screen around it but never created the
 * encounter itself. Every admission now opens one, tagged with the
 * admission's id (V193), which its discharge later closes (D134).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdmissionStayEncounterService {

    private final EncounterRepository encounterRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final com.example.hms.repository.NurseHandoffRepository nurseHandoffRepository;
    private final com.example.hms.repository.NursingTaskRepository nursingTaskRepository;
    private final org.springframework.context.MessageSource messageSource;

    /**
     * The attending physician runs the stay; the admitting provider stands in
     * when none is named, or when the attending has no staff row or active
     * role at this hospital. An admission that neither can lead is still
     * admitted, as it was before stays had encounters, just without one: the
     * encounter must never be the reason a patient cannot be admitted.
     *
     * @return the stay encounter, or {@code null} when no lead resolves
     */
    @Transactional
    public Encounter openStayEncounter(Admission admission) {
        Hospital hospital = admission.getHospital();
        if (hospital == null || hospital.getId() == null) {
            log.warn("Admission {} opened without a stay encounter: no hospital", admission.getId());
            return null;
        }
        Lead lead = resolveLead(admission.getAttendingPhysician(), hospital);
        if (lead == null) {
            lead = resolveLead(admission.getAdmittingProvider(), hospital);
        }
        if (lead == null) {
            log.warn("Admission {} opened without a stay encounter: no lead with an active role at hospital {}",
                admission.getId(), hospital.getId());
            return null;
        }
        Staff staff = lead.staff();
        UserRoleHospitalAssignment assignment = lead.assignment();

        LocalDateTime start = admission.getAdmissionDateTime() != null
            ? admission.getAdmissionDateTime() : LocalDateTime.now();
        Encounter encounter = Encounter.builder()
            .patient(admission.getPatient())
            .staff(staff)
            .hospital(hospital)
            .department(departmentOf(admission))
            .assignment(assignment)
            .encounterType(EncounterType.INPATIENT)
            .encounterDate(start)
            .arrivalTimestamp(start)
            .chiefComplaint(admission.getChiefComplaint())
            .status(EncounterStatus.IN_PROGRESS)
            .admissionId(admission.getId())
            .build();
        encounter.setCode(newCode());
        return encounterRepository.save(encounter);
    }

    private record Lead(Staff staff, UserRoleHospitalAssignment assignment) {
    }

    /**
     * The encounter's staff row and assignment must belong to the admission's
     * hospital (Encounter#validate): a clinician working at several hospitals
     * has one staff row per hospital.
     */
    private Lead resolveLead(Staff candidate, Hospital hospital) {
        UUID userId = candidate != null && candidate.getUser() != null ? candidate.getUser().getId() : null;
        if (userId == null) {
            return null;
        }
        Staff staff = candidate.getHospital() != null && hospital.getId().equals(candidate.getHospital().getId())
            ? candidate
            : staffRepository.findByUserIdAndHospitalId(userId, hospital.getId()).orElse(null);
        if (staff == null) {
            return null;
        }
        return assignmentRepository.findActiveStaffAssignment(userId, hospital.getId())
            .map(assignment -> new Lead(staff, assignment))
            .orElse(null);
    }

    /**
     * Carries the discharge onto the stay's encounter before it is closed, so
     * the after-visit summary built from it (DischargeSummaryServiceImpl's
     * backfill) has the stay's follow-up instructions and diagnosis instead of
     * an empty page (QA D151). Only blank fields are filled.
     */
    @Transactional
    public void recordDischarge(Admission admission) {
        applyDischarge(admission, null);
    }

    /**
     * QA D218: as {@link #recordDischarge(Admission)}, with the diagnosis the
     * discharging physician stated. A stated diagnosis wins over the admission's
     * working diagnosis, which only fills a blank.
     */
    @Transactional
    public void recordDischarge(Admission admission, String dischargeDiagnosis) {
        applyDischarge(admission, dischargeDiagnosis);
    }

    private void applyDischarge(Admission admission, String dischargeDiagnosis) {
        if (admission.getId() == null) {
            return;
        }
        encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()).ifPresent(enc -> {
            if (!isBlank(dischargeDiagnosis)) {
                enc.setDischargeDiagnoses(toJsonArray(truncate(dischargeDiagnosis, 2000)));
            }
            if (isBlank(enc.getFollowUpInstructions()) && !isBlank(admission.getDischargeInstructions())) {
                enc.setFollowUpInstructions(admission.getDischargeInstructions());
            }
            if (isBlank(enc.getNotes()) && !isBlank(admission.getDischargeSummary())) {
                enc.setNotes(truncate(admission.getDischargeSummary(), 2048));
            }
            if (isBlank(enc.getDischargeDiagnoses()) && !isBlank(admission.getPrimaryDiagnosisDescription())) {
                enc.setDischargeDiagnoses(toJsonArray(admission.getPrimaryDiagnosisDescription()));
            }
            encounterRepository.save(enc);
        });
        closePendingHandoffs(admission);
        closePendingNursingTasks(admission);
    }

    /**
     * QA D294: the same for the nurse's ad-hoc tasks — a PENDING task for a
     * patient who has gone home stayed on the worklist, and turned overdue.
     */
    private void closePendingNursingTasks(Admission admission) {
        if (admission.getPatient() == null || admission.getHospital() == null) {
            return;
        }
        var pending = nursingTaskRepository.findByPatient_IdAndHospital_IdAndStatus(
            admission.getPatient().getId(), admission.getHospital().getId(), "PENDING");
        if (pending.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String closedBy = messageSource.getMessage("nurse.handoff.closedByDischarge", null,
            "Closed at discharge", org.springframework.context.i18n.LocaleContextHolder.getLocale());
        for (var task : pending) {
            task.setStatus("COMPLETED");
            task.setCompletedAt(now);
            task.setCompletedByName(closedBy);
        }
        nursingTaskRepository.saveAll(pending);
    }

    /**
     * QA D234: once the patient has left, a pending handoff for them has no one
     * to hand over to; it stayed PENDING on the nurse's list for ever. Every
     * discharge path (direct, and the approval flow) comes through here.
     */
    private void closePendingHandoffs(Admission admission) {
        if (admission.getPatient() == null || admission.getHospital() == null) {
            return;
        }
        var pending = nurseHandoffRepository.findByPatient_IdAndHospital_IdAndStatus(
            admission.getPatient().getId(), admission.getHospital().getId(), "PENDING");
        if (pending.isEmpty()) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        String closedBy = messageSource.getMessage("nurse.handoff.closedByDischarge", null,
            "Closed at discharge", org.springframework.context.i18n.LocaleContextHolder.getLocale());
        for (var handoff : pending) {
            handoff.setStatus("COMPLETED");
            handoff.setCompletedAt(now);
            handoff.setCompletedByName(closedBy);
        }
        nurseHandoffRepository.saveAll(pending);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** The encounter keeps its discharge diagnoses as a JSON array of strings. */
    private static String toJsonArray(String diagnosis) {
        String escaped = diagnosis.trim().replace("\\", "\\\\").replace("\"", "\\\"");
        return "[\"" + escaped + "\"]";
    }

    private static com.example.hms.model.Department departmentOf(Admission admission) {
        com.example.hms.model.Department department = admission.getDepartment();
        if (department == null || department.getHospital() == null || admission.getHospital() == null
            || !department.getHospital().getId().equals(admission.getHospital().getId())) {
            return null;
        }
        return department;
    }

    private static String newCode() {
        String date = LocalDate.now().toString().replace("-", "");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return "ENC-" + date + "-" + suffix;
    }
}
