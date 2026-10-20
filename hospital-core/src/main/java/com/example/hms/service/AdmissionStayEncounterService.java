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
    /** D426: a discharged stay gets its discharge-summary record, as a checkout does. */
    private final com.example.hms.repository.DischargeSummaryRepository dischargeSummaryRepository;
    private final com.example.hms.repository.PrescriptionRepository prescriptionRepository;

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
            recordStayDischargeSummary(admission, enc);
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
        LocalDateTime now = LocalDateTime.now(java.time.ZoneId.systemDefault());
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

    /**
     * QA D426 (D322 not fixed): an inpatient discharge never wrote a discharge
     * summary. Only the outpatient checkout did, and the patient-portal read
     * backfilled one lazily, so the stay's after-visit summary had no medicines
     * and no follow-up. The stay now records one at discharge: the medicines
     * ordered on the stay, the discharge instructions and the follow-ups the
     * discharging physician entered. An existing summary (written on the
     * discharge screen) is left as it is.
     */
    private void recordStayDischargeSummary(Admission admission, Encounter enc) {
        if (dischargeSummaryRepository == null || enc.getId() == null
            || dischargeSummaryRepository.existsByEncounter_Id(enc.getId())) {
            return;
        }
        Staff provider = admission.getDischargingProvider() != null
            ? admission.getDischargingProvider() : enc.getStaff();
        if (provider == null || enc.getAssignment() == null || enc.getPatient() == null || enc.getHospital() == null) {
            log.warn("Admission {} discharged without a discharge summary: no provider or assignment", admission.getId());
            return;
        }
        LocalDateTime when = admission.getActualDischargeDateTime() != null
            ? admission.getActualDischargeDateTime() : LocalDateTime.now(java.time.ZoneId.systemDefault());
        com.example.hms.model.discharge.DischargeSummary summary = new com.example.hms.model.discharge.DischargeSummary();
        summary.setEncounter(enc);
        summary.setPatient(enc.getPatient());
        summary.setHospital(enc.getHospital());
        summary.setDischargingProvider(provider);
        summary.setAssignment(enc.getAssignment());
        summary.setDischargeDate(when.toLocalDate());
        summary.setDischargeTime(when);
        summary.setDisposition(admission.getDischargeDisposition() != null
            ? admission.getDischargeDisposition() : com.example.hms.enums.DischargeDisposition.HOME);
        summary.setDischargeDiagnosis(stayDiagnosisText(admission, enc));
        summary.setFollowUpInstructions(enc.getFollowUpInstructions());
        if (!isBlank(admission.getDischargeSummary())) {
            summary.setHospitalCourse(admission.getDischargeSummary());
        }
        addStayMedications(summary, enc);
        addStayFollowUps(summary, admission);
        dischargeSummaryRepository.save(summary);
    }

    private String stayDiagnosisText(Admission admission, Encounter enc) {
        String raw = enc.getDischargeDiagnoses();
        String text = raw == null ? "" : raw.replaceAll("[\\[\\]\"]", "").trim();
        if (!text.isEmpty()) {
            return truncate(text, 5000);
        }
        if (!isBlank(admission.getPrimaryDiagnosisDescription())) {
            return truncate(admission.getPrimaryDiagnosisDescription().trim(), 5000);
        }
        return messageSource.getMessage("dischargeSummary.diagnosis.notSpecified", null,
            "Discharge diagnosis not specified at checkout.",
            org.springframework.context.i18n.LocaleContextHolder.getLocale());
    }

    private void addStayMedications(com.example.hms.model.discharge.DischargeSummary summary, Encounter enc) {
        var prescriptions = prescriptionRepository
            .findByEncounter_Id(enc.getId(), org.springframework.data.domain.PageRequest.of(0, 100))
            .getContent();
        for (com.example.hms.model.Prescription rx : prescriptions) {
            // As at checkout (QA D152): an unsigned or withdrawn order does not go home.
            if (rx.getStatus() == com.example.hms.enums.PrescriptionStatus.DRAFT
                || rx.getStatus() == com.example.hms.enums.PrescriptionStatus.PENDING_SIGNATURE
                || (rx.getStatus() != null && rx.getStatus().isWithdrawn())) {
                continue;
            }
            summary.addMedicationReconciliation(com.example.hms.model.discharge.MedicationReconciliationEntry.builder()
                .medicationName(rx.getMedicationName())
                .dosage(rx.getDosage())
                .route(rx.getRoute())
                .frequency(rx.getFrequency())
                .reconciliationAction(com.example.hms.enums.MedicationReconciliationAction.CONTINUED)
                .continueAtDischarge(true)
                .build());
        }
    }

    private static void addStayFollowUps(com.example.hms.model.discharge.DischargeSummary summary, Admission admission) {
        if (admission.getFollowUpAppointments() == null) {
            return;
        }
        for (java.util.Map<String, Object> f : admission.getFollowUpAppointments()) {
            com.example.hms.model.discharge.FollowUpAppointmentEntry entry = toFollowUp(f);
            if (entry != null) {
                summary.addFollowUpAppointment(entry);
            }
        }
    }

    /** One follow-up the physician entered, or null when the row says nothing usable. */
    private static com.example.hms.model.discharge.FollowUpAppointmentEntry toFollowUp(java.util.Map<String, Object> f) {
        if (f == null) {
            return null;
        }
        LocalDate date = parseDate(firstText(f, "appointmentDate", "date", "preferredDate"));
        String type = firstText(f, "appointmentType", "type");
        String purpose = firstText(f, "purpose", "reason", "notes");
        if (date == null && type == null && purpose == null) {
            return null;
        }
        return com.example.hms.model.discharge.FollowUpAppointmentEntry.builder()
            .appointmentType(type)
            .providerName(firstText(f, "providerName", "provider"))
            .specialty(firstText(f, "specialty"))
            .appointmentDate(date)
            .purpose(purpose)
            .build();
    }

    private static String firstText(java.util.Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !value.toString().isBlank()) {
                return value.toString().trim();
            }
        }
        return null;
    }

    private static LocalDate parseDate(String value) {
        if (value == null) {
            return null;
        }
        try {
            return LocalDate.parse(value.length() > 10 ? value.substring(0, 10) : value);
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
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
