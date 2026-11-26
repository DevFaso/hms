package com.example.hms.service.vitals;

import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3862 (review of #1339): who an adult's HIGH NEWS2 alert reaches. Its reminders went to every doctor and nurse
 * of the hospital: alert fatigue, and patient alerts in the hands of staff who have nothing to do with the patient.
 * The circle is the patient's care team, read from the open stay at the alert's hospital:
 * <ul>
 *   <li>the responsible doctor: the stay's attending physician, else its admitting provider;</li>
 *   <li>the covering doctor: the doctors on call now (nobody when the rota is empty: no hospital-wide stand-in);</li>
 *   <li>the ward team: the doctors of the stay's department, and its nurses on shift now (every nurse of the
 *       department when the shift roster names none).</li>
 * </ul>
 * The hospital-wide doctors and nurses stand in only when that circle holds nobody (no open stay, as at triage; a
 * stay with no department; a department with no doctor or nurse). Provisional rule NEWS2_ESCALATION.
 */
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.NEWS2_ESCALATION)
public class News2CareTeam {

    /** A doctor, whichever of the three doctor roles the hospital assigned. */
    static final List<String> DOCTOR_ROLES = List.of("ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON");
    static final String NURSE_ROLE = "ROLE_NURSE";

    private final AdmissionRepository admissionRepository;
    private final StaffRepository staffRepository;
    private final StaffShiftRepository staffShiftRepository;
    private final OnCallScheduleRepository onCallScheduleRepository;
    private final Clock clock;

    /**
     * The nurses of the patient's ward: the stay's department's nurses on shift now, else every nurse of that
     * department. Empty when the patient has no open stay with a department here, or that department has no nurse.
     */
    public Set<String> wardNurses(UUID hospitalId, UUID patientId) {
        UUID departmentId = openStay(patientId, hospitalId).map(News2CareTeam::departmentIdOf).orElse(null);
        return departmentNurses(hospitalId, departmentId);
    }

    /** Who each reminder reaches: the responsible doctor, the on-call doctors, the ward team; see the class comment. */
    public Set<String> reminderCircle(UUID hospitalId, UUID patientId) {
        Optional<Admission> stay = openStay(patientId, hospitalId);
        Set<String> circle = new LinkedHashSet<>();
        stay.map(News2CareTeam::responsibleUsername).ifPresent(circle::add);
        circle.addAll(onCallDoctors(hospitalId));
        UUID departmentId = stay.map(News2CareTeam::departmentIdOf).orElse(null);
        Set<String> team = new LinkedHashSet<>();
        if (departmentId != null) {
            for (String role : DOCTOR_ROLES) {
                team.addAll(staffRepository.findActiveUsernamesByHospitalDepartmentAndRole(hospitalId, departmentId, role));
            }
            team.addAll(departmentNurses(hospitalId, departmentId));
        }
        if (circle.isEmpty() && team.isEmpty()) {
            // Nobody stands for this patient: the hospital's doctors and nurses, so the alert never goes unheard.
            for (String role : DOCTOR_ROLES) {
                team.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role));
            }
            team.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, NURSE_ROLE));
        }
        circle.addAll(team);
        return circle;
    }

    private Set<String> departmentNurses(UUID hospitalId, UUID departmentId) {
        if (hospitalId == null || departmentId == null) {
            return Set.of();
        }
        List<String> nurses = staffRepository.findActiveUsernamesByHospitalDepartmentAndRole(hospitalId, departmentId,
            NURSE_ROLE);
        Set<String> onShift = keep(nurses, onShiftInDepartment(departmentId));
        return onShift.isEmpty() ? new LinkedHashSet<>(nurses) : onShift;
    }

    private Optional<Admission> openStay(UUID patientId, UUID hospitalId) {
        if (patientId == null || hospitalId == null) {
            return Optional.empty();
        }
        return admissionRepository.findByPatient_IdAndHospital_IdAndStatusIn(patientId, hospitalId, AdmissionStatus.OPEN)
            .stream()
            .max(Comparator.comparing(Admission::getAdmissionDateTime, Comparator.nullsFirst(Comparator.naturalOrder())));
    }

    private static UUID departmentIdOf(Admission stay) {
        return stay.getDepartment() == null ? null : stay.getDepartment().getId();
    }

    private static String responsibleUsername(Admission stay) {
        Staff responsible = stay.getAttendingPhysician() != null ? stay.getAttendingPhysician() : stay.getAdmittingProvider();
        User user = responsible == null ? null : responsible.getUser();
        return user == null ? null : user.getUsername();
    }

    /** The doctors on call now at the hospital; nobody when the rota is empty. */
    private Set<String> onCallDoctors(UUID hospitalId) {
        Set<String> doctors = new LinkedHashSet<>();
        for (String role : DOCTOR_ROLES) {
            doctors.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> onCall = onCallScheduleRepository.findByHospitalAndWindow(hospitalId, now, now).stream()
            .map(OnCallSchedule::getStaff)
            .map(News2CareTeam::activeUsername)
            .filter(java.util.Objects::nonNull)
            .toList();
        return keep(doctors, onCall);
    }

    /** The staff whose shift in the department covers this minute: today's, and yesterday's running past midnight. */
    private List<String> onShiftInDepartment(UUID departmentId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        return staffShiftRepository.findByDepartment_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(
                departmentId, today.minusDays(1), today).stream()
            .filter(shift -> running(shift, today, time))
            .map(StaffShift::getStaff)
            .map(News2CareTeam::activeUsername)
            .filter(java.util.Objects::nonNull)
            .toList();
    }

    private static boolean running(StaffShift shift, LocalDate today, LocalTime time) {
        if (shift.getStatus() == StaffShiftStatus.CANCELLED || shift.getStartTime() == null || shift.getEndTime() == null
                || shift.getShiftDate() == null) {
            return false;
        }
        boolean overnight = !shift.getEndTime().isAfter(shift.getStartTime());
        if (shift.getShiftDate().equals(today)) {
            return !time.isBefore(shift.getStartTime()) && (overnight || time.isBefore(shift.getEndTime()));
        }
        return shift.getShiftDate().equals(today.minusDays(1)) && overnight && time.isBefore(shift.getEndTime());
    }

    private static String activeUsername(Staff staff) {
        User user = staff == null ? null : staff.getUser();
        return staff != null && staff.isActive() && user != null && user.getUsername() != null
            && user.isActive() && !user.isDeleted() ? user.getUsername() : null;
    }

    /** The members of {@code pool} named in {@code names}, whatever the case, in the pool's order. */
    private static Set<String> keep(java.util.Collection<String> pool, List<String> names) {
        Set<String> lower = new LinkedHashSet<>();
        names.forEach(name -> lower.add(name.toLowerCase(Locale.ROOT)));
        Set<String> kept = new LinkedHashSet<>();
        for (String member : pool) {
            if (lower.contains(member.toLowerCase(Locale.ROOT))) {
                kept.add(member);
            }
        }
        return kept;
    }
}
