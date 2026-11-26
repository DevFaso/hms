package com.example.hms.service.vitals;

import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Department;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA D3862, review of #1339: a HIGH NEWS2's reminders reach the patient's care team (the responsible doctor, the
 * doctors on call, the ward's doctors and its nurses on shift), not every doctor and nurse of the hospital; the
 * hospital stands in only when that team holds nobody.
 */
class News2CareTeamTest {

    private static final UUID HOSPITAL = UUID.randomUUID();
    private static final UUID PATIENT = UUID.randomUUID();
    private static final UUID WARD = UUID.randomUUID();
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 25, 14, 0);

    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final StaffRepository staff = mock(StaffRepository.class);
    private final StaffShiftRepository shifts = mock(StaffShiftRepository.class);
    private final OnCallScheduleRepository onCall = mock(OnCallScheduleRepository.class);
    private News2CareTeam team;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        team = new News2CareTeam(admissions, staff, shifts, onCall, clock);
        // The hospital: four doctors, four nurses, a midwife; the ward: one doctor, two nurses.
        when(staff.findActiveUsernamesByHospitalAndRole(eq(HOSPITAL), anyString())).thenReturn(List.of());
        when(staff.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_DOCTOR"))
            .thenReturn(List.of("dr_attending", "dr_ward", "dr_oncall", "dr_elsewhere"));
        when(staff.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_NURSE"))
            .thenReturn(List.of("nurse_w1", "nurse_w2", "nurse_x", "nurse_y"));
        when(staff.findActiveUsernamesByHospitalAndRole(HOSPITAL, "ROLE_MIDWIFE")).thenReturn(List.of("mw_a"));
        when(staff.findActiveUsernamesByHospitalDepartmentAndRole(eq(HOSPITAL), eq(WARD), anyString())).thenReturn(List.of());
        when(staff.findActiveUsernamesByHospitalDepartmentAndRole(HOSPITAL, WARD, "ROLE_DOCTOR")).thenReturn(List.of("dr_ward"));
        when(staff.findActiveUsernamesByHospitalDepartmentAndRole(HOSPITAL, WARD, "ROLE_NURSE"))
            .thenReturn(List.of("nurse_w1", "nurse_w2"));
        when(onCall.findByHospitalAndWindow(eq(HOSPITAL), any(), any())).thenReturn(List.of(onCallShift("dr_oncall")));
        when(shifts.findByDepartment_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(eq(WARD), any(), any()))
            .thenReturn(List.of(shift("nurse_w1", NOW.toLocalDate(), LocalTime.of(8, 0), LocalTime.of(20, 0)),
                shift("nurse_w2", NOW.toLocalDate(), LocalTime.of(20, 0), LocalTime.of(8, 0))));
    }

    private static Staff person(String username) {
        User user = User.builder().username(username).isActive(true).build();
        return Staff.builder().active(true).user(user).build();
    }

    private static OnCallSchedule onCallShift(String username) {
        OnCallSchedule s = new OnCallSchedule();
        s.setStaff(person(username));
        return s;
    }

    private static StaffShift shift(String username, LocalDate day, LocalTime start, LocalTime end) {
        StaffShift s = new StaffShift();
        s.setStaff(person(username));
        s.setShiftDate(day);
        s.setStartTime(start);
        s.setEndTime(end);
        s.setStatus(StaffShiftStatus.SCHEDULED);
        return s;
    }

    private void admittedTo(UUID departmentId) {
        Admission stay = new Admission();
        stay.setAttendingPhysician(person("dr_attending"));
        stay.setAdmissionDateTime(NOW.minusDays(1));
        if (departmentId != null) {
            Department ward = new Department();
            ward.setId(departmentId);
            stay.setDepartment(ward);
        }
        when(admissions.findByPatient_IdAndHospital_IdAndStatusIn(eq(PATIENT), eq(HOSPITAL), any()))
            .thenReturn(List.of(stay));
    }

    @Test
    void remindersReachTheResponsibleOnCallAndWardTeamOnly() {
        admittedTo(WARD);

        assertThat(team.reminderCircle(HOSPITAL, PATIENT))
            .containsExactlyInAnyOrder("dr_attending", "dr_oncall", "dr_ward", "nurse_w1")
            .doesNotContain("dr_elsewhere", "nurse_x", "nurse_y", "nurse_w2", "mw_a");
        assertThat(team.wardNurses(HOSPITAL, PATIENT)).containsExactly("nurse_w1");
    }

    @Test
    void withNoNurseOnShiftEveryNurseOfTheWardStandsIn() {
        admittedTo(WARD);
        when(shifts.findByDepartment_IdAndShiftDateBetweenOrderByShiftDateAscStartTimeAsc(eq(WARD), any(), any()))
            .thenReturn(List.of());

        assertThat(team.wardNurses(HOSPITAL, PATIENT)).containsExactlyInAnyOrder("nurse_w1", "nurse_w2");
    }

    @Test
    void aStayWithNoDepartmentKeepsItsDoctorsAndNoHospitalWideStaff() {
        admittedTo(null);

        assertThat(team.reminderCircle(HOSPITAL, PATIENT)).containsExactlyInAnyOrder("dr_attending", "dr_oncall");
        assertThat(team.wardNurses(HOSPITAL, PATIENT)).isEmpty();
    }

    @Test
    void theHospitalStandsInOnlyWhenNobodyStandsForThePatient() {
        // Triage: no open stay; nobody on call.
        when(onCall.findByHospitalAndWindow(eq(HOSPITAL), any(), any())).thenReturn(List.of());

        assertThat(team.reminderCircle(HOSPITAL, PATIENT)).containsExactlyInAnyOrder(
            "dr_attending", "dr_ward", "dr_oncall", "dr_elsewhere", "nurse_w1", "nurse_w2", "nurse_x", "nurse_y");
    }
}
