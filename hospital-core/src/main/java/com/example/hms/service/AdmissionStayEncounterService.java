package com.example.hms.service;

import com.example.hms.enums.EncounterStatus;
import com.example.hms.enums.EncounterType;
import com.example.hms.exception.BusinessException;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Staff;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import lombok.RequiredArgsConstructor;
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
@Service
@RequiredArgsConstructor
public class AdmissionStayEncounterService {

    private final EncounterRepository encounterRepository;
    private final StaffRepository staffRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;

    /**
     * The attending physician runs the stay; the admitting provider stands in
     * when none is named. Both were checked as physicians at this hospital.
     */
    @Transactional
    public Encounter openStayEncounter(Admission admission) {
        Hospital hospital = admission.getHospital();
        Staff lead = admission.getAttendingPhysician() != null
            ? admission.getAttendingPhysician() : admission.getAdmittingProvider();
        UUID userId = lead != null && lead.getUser() != null ? lead.getUser().getId() : null;
        if (hospital == null || userId == null) {
            throw new BusinessException("admission.encounter.provider.missing");
        }
        // The encounter's staff row and assignment must belong to the
        // admission's hospital (Encounter#validate): a clinician working at
        // several hospitals has one staff row per hospital.
        Staff staff = lead.getHospital() != null && hospital.getId().equals(lead.getHospital().getId())
            ? lead
            : staffRepository.findByUserIdAndHospitalId(userId, hospital.getId())
                .orElseThrow(() -> new BusinessException("admission.encounter.provider.missing"));
        UserRoleHospitalAssignment assignment = assignmentRepository
            .findActiveStaffAssignment(userId, hospital.getId())
            .orElseThrow(() -> new BusinessException("admission.encounter.provider.missing"));

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

    /**
     * Carries the discharge onto the stay's encounter before it is closed, so
     * the after-visit summary built from it (DischargeSummaryServiceImpl's
     * backfill) has the stay's follow-up instructions and diagnosis instead of
     * an empty page (QA D151). Only blank fields are filled.
     */
    @Transactional
    public void recordDischarge(Admission admission) {
        if (admission.getId() == null) {
            return;
        }
        encounterRepository.findFirstByAdmissionIdOrderByEncounterDateDesc(admission.getId()).ifPresent(enc -> {
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
