package com.example.hms.service.lab;

import com.example.hms.enums.StaffLeaveStatus;
import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffLeaveRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.service.clinicalrules.ClinicalRuleSetKey;
import com.example.hms.service.clinicalrules.ProvisionalRule;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Who each step of {@link CriticalResultEscalationLadder} tells (rule CRITICAL_RESULT_ESCALATION, « provisoire »).
 * Every list is read at the result's own hospital, and every name on a value-bearing list is a clinician's or the
 * laboratory's: {@link #administrators} is the one list of non-clinicians, and what they receive carries no value.
 *
 * <p>What the model does not hold, and the stand-in used:
 * <ul>
 *   <li>a cover named on each absence: a leave request names nobody; the cover is the on-call roster, else the
 *       doctors on duty now, else every doctor of the hospital;</li>
 *   <li>a nurse in charge of a patient: the nurses on duty now; with no roster, every nurse of the hospital for a
 *       patient in a bed or in the emergency unit, and nobody for an outpatient;</li>
 *   <li>a médecin-chef role: the doctor the hospital named for it, else its heads of department who are
 *       doctors, else every doctor.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@ProvisionalRule(ClinicalRuleSetKey.CRITICAL_RESULT_ESCALATION)
public class CriticalResultRecipients {

    /** A doctor, whichever of the three doctor roles the hospital assigned. */
    public static final List<String> DOCTOR_ROLES = List.of("ROLE_DOCTOR", "ROLE_PHYSICIAN", "ROLE_SURGEON");
    public static final String NURSE_ROLE = "ROLE_NURSE";
    /** The responsible biologist. */
    public static final String BIOLOGIST_ROLE = "ROLE_LAB_DIRECTOR";
    /** Told that an incident is open, never the value. */
    public static final String ADMINISTRATOR_ROLE = "ROLE_HOSPITAL_ADMIN";

    private final StaffRepository staffRepository;
    private final OnCallScheduleRepository onCallScheduleRepository;
    private final StaffShiftRepository staffShiftRepository;
    private final StaffLeaveRequestRepository leaveRepository;
    private final DepartmentRepository departmentRepository;
    private final UserRepository userRepository;
    private final Clock clock;

    /** The prescriber is on approved leave today: the first notice does not wait fifteen minutes for them. */
    public boolean prescriberAbsent(Staff prescriber) {
        if (prescriber == null || prescriber.getId() == null) {
            return false;
        }
        return !leaveRepository.findLeavesOverlappingDate(prescriber.getId(), LocalDate.now(clock),
            List.of(StaffLeaveStatus.APPROVED)).isEmpty();
    }

    /** The nurse in charge: see the class comment. */
    public Set<String> nursesInCharge(UUID hospitalId, boolean inBedOrEmergency) {
        Set<String> nurses = withRoles(hospitalId, List.of(NURSE_ROLE));
        Set<String> onDuty = keep(nurses, onDutyNow(hospitalId));
        if (!onDuty.isEmpty()) {
            return onDuty;
        }
        return inBedOrEmergency ? nurses : Set.of();
    }

    /** The covering doctor: on call now, else on duty now, else every doctor; never the prescriber when another exists. */
    public Set<String> cover(UUID hospitalId, String prescriberUsername) {
        Set<String> doctors = withRoles(hospitalId, DOCTOR_ROLES);
        Set<String> others = without(doctors, prescriberUsername);
        Set<String> onCall = keep(others, onCallNow(hospitalId));
        if (!onCall.isEmpty()) {
            return onCall;
        }
        Set<String> onDuty = keep(others, onDutyNow(hospitalId));
        if (!onDuty.isEmpty()) {
            return onDuty;
        }
        return others.isEmpty() ? doctors : others;
    }

    /** The médecin-chef: see the class comment. */
    public Set<String> medicalLead(Hospital hospital) {
        if (hospital == null || hospital.getId() == null) {
            return Set.of();
        }
        Set<String> doctors = withRoles(hospital.getId(), DOCTOR_ROLES);
        if (hospital.getCriticalMedicalLeadUserId() != null) {
            String named = userRepository.findById(hospital.getCriticalMedicalLeadUserId())
                .filter(user -> user.isActive() && !user.isDeleted())
                .map(User::getUsername).orElse(null);
            // Only while that person is still a doctor here: a name that left the hospital tells nobody.
            if (named != null && !keep(doctors, List.of(named)).isEmpty()) {
                return Set.of(named);
            }
        }
        List<String> heads = new ArrayList<>();
        for (Department department : departmentRepository.findByHospitalId(hospital.getId())) {
            Staff head = department.getHeadOfDepartment();
            if (head != null && head.getUser() != null && head.getUser().getUsername() != null) {
                heads.add(head.getUser().getUsername());
            }
        }
        Set<String> leads = keep(doctors, heads);
        return leads.isEmpty() ? doctors : leads;
    }

    public Set<String> biologists(UUID hospitalId) {
        return withRoles(hospitalId, List.of(BIOLOGIST_ROLE));
    }

    /** The hospital's administrators: an incident flag with no value, never a result. */
    public Set<String> administrators(UUID hospitalId) {
        return withRoles(hospitalId, List.of(ADMINISTRATOR_ROLE));
    }

    private Set<String> withRoles(UUID hospitalId, List<String> roles) {
        Set<String> usernames = new LinkedHashSet<>();
        if (hospitalId != null) {
            for (String role : roles) {
                usernames.addAll(staffRepository.findActiveUsernamesByHospitalAndRole(hospitalId, role));
            }
        }
        return usernames;
    }

    private List<String> onCallNow(UUID hospitalId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> usernames = new ArrayList<>();
        for (OnCallSchedule shift : onCallScheduleRepository.findByHospitalAndWindow(hospitalId, now, now)) {
            add(usernames, shift.getStaff());
        }
        return usernames;
    }

    /** The staff whose shift covers this minute: today's shifts, and yesterday's that run past midnight. */
    private List<String> onDutyNow(UUID hospitalId) {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        List<String> usernames = new ArrayList<>();
        for (StaffShift shift : staffShiftRepository.findByHospital_IdAndShiftDate(hospitalId, today)) {
            if (running(shift) && !time.isBefore(shift.getStartTime())
                    && (overnight(shift) || time.isBefore(shift.getEndTime()))) {
                add(usernames, shift.getStaff());
            }
        }
        for (StaffShift shift : staffShiftRepository.findByHospital_IdAndShiftDate(hospitalId, today.minusDays(1))) {
            if (running(shift) && overnight(shift) && time.isBefore(shift.getEndTime())) {
                add(usernames, shift.getStaff());
            }
        }
        return usernames;
    }

    private static boolean running(StaffShift shift) {
        return shift.getStatus() != StaffShiftStatus.CANCELLED && shift.getStartTime() != null && shift.getEndTime() != null;
    }

    private static boolean overnight(StaffShift shift) {
        return !shift.getEndTime().isAfter(shift.getStartTime());
    }

    private static void add(List<String> usernames, Staff staff) {
        User user = staff == null ? null : staff.getUser();
        if (staff != null && staff.isActive() && user != null && user.getUsername() != null
                && user.isActive() && !user.isDeleted()) {
            usernames.add(user.getUsername());
        }
    }

    /** The members of {@code pool} named in {@code names}, whatever the case, in the pool's order. */
    private static Set<String> keep(Set<String> pool, List<String> names) {
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

    private static Set<String> without(Set<String> pool, String name) {
        Set<String> rest = new LinkedHashSet<>(pool);
        if (name != null) {
            rest.removeIf(member -> member.equalsIgnoreCase(name));
        }
        return rest;
    }
}
