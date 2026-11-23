package com.example.hms.service.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.hms.enums.StaffLeaveStatus;
import com.example.hms.enums.StaffShiftStatus;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.OnCallSchedule;
import com.example.hms.model.Staff;
import com.example.hms.model.StaffLeaveRequest;
import com.example.hms.model.StaffShift;
import com.example.hms.model.User;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.OnCallScheduleRepository;
import com.example.hms.repository.StaffLeaveRequestRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.StaffShiftRepository;
import com.example.hms.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * QA r70 (C5): who each step of the critical-result ladder tells, read at the result's own hospital: the cover is
 * the on-call roster, else the doctors on duty, else every doctor; the nurse in charge and the médecin-chef have
 * their stand-ins; an administrator is on one list only.
 */
class CriticalResultRecipientsTest {

    /** A Sunday, 02:30: yesterday's night shift is still running. */
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 22, 2, 30);

    private final StaffRepository staff = mock(StaffRepository.class);
    private final OnCallScheduleRepository onCall = mock(OnCallScheduleRepository.class);
    private final StaffShiftRepository shifts = mock(StaffShiftRepository.class);
    private final StaffLeaveRequestRepository leaves = mock(StaffLeaveRequestRepository.class);
    private final DepartmentRepository departments = mock(DepartmentRepository.class);
    private final UserRepository users = mock(UserRepository.class);
    private final CriticalResultRecipients recipients = new CriticalResultRecipients(staff, onCall, shifts, leaves,
        departments, users, Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private final UUID hospitalId = UUID.randomUUID();
    private Hospital hospital;

    @BeforeEach
    void setUp() {
        hospital = Hospital.builder().name("Clinique").code("CLI").build();
        hospital.setId(hospitalId);
        when(staff.findActiveUsernamesByHospitalAndRole(any(), any())).thenReturn(List.of());
        when(staff.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_DOCTOR"))
            .thenReturn(List.of("dr.prescriber", "dr.oncall", "dr.onduty", "dr.off"));
        when(staff.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_SURGEON")).thenReturn(List.of("dr.surgeon"));
        when(staff.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_NURSE"))
            .thenReturn(List.of("inf.night", "inf.day"));
        when(staff.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_LAB_DIRECTOR")).thenReturn(List.of("bio.kabore"));
        when(staff.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_HOSPITAL_ADMIN")).thenReturn(List.of("admin.one"));
        when(onCall.findByHospitalAndWindow(eq(hospitalId), any(), any())).thenReturn(List.of());
        when(shifts.findByHospital_IdAndShiftDate(eq(hospitalId), any())).thenReturn(List.of());
        when(departments.findByHospitalId(hospitalId)).thenReturn(List.of());
    }

    private static Staff member(String username) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername(username);
        user.setActive(true);
        Staff staffMember = Staff.builder().build();
        staffMember.setId(UUID.randomUUID());
        staffMember.setUser(user);
        staffMember.setActive(true);
        return staffMember;
    }

    private static OnCallSchedule onCallShift(String username) {
        OnCallSchedule shift = new OnCallSchedule();
        shift.setStaff(member(username));
        return shift;
    }

    private static StaffShift shift(String username, LocalTime from, LocalTime to, StaffShiftStatus status) {
        StaffShift shift = new StaffShift();
        shift.setStaff(member(username));
        shift.setStartTime(from);
        shift.setEndTime(to);
        shift.setStatus(status);
        return shift;
    }

    @Test
    @DisplayName("the cover: the on-call roster first, never the prescriber when another doctor exists")
    void theCoverIsTheOnCallRosterFirst() {
        when(onCall.findByHospitalAndWindow(eq(hospitalId), any(), any()))
            .thenReturn(List.of(onCallShift("DR.ONCALL"), onCallShift("dr.prescriber"), onCallShift("lab.tech")));

        // Case-insensitive, doctors only, and the prescriber who did not answer is not his own cover.
        assertThat(recipients.cover(hospitalId, "dr.prescriber")).containsExactly("dr.oncall");
    }

    @Test
    @DisplayName("the cover: with nobody on call, the doctors on duty now (a night shift that began yesterday counts)")
    void thenTheDoctorsOnDuty() {
        LocalDate today = NOW.toLocalDate();
        when(shifts.findByHospital_IdAndShiftDate(hospitalId, today)).thenReturn(List.of(
            shift("dr.off", LocalTime.of(8, 0), LocalTime.of(16, 0), StaffShiftStatus.SCHEDULED),
            shift("dr.surgeon", LocalTime.of(0, 0), LocalTime.of(6, 0), StaffShiftStatus.CANCELLED)));
        when(shifts.findByHospital_IdAndShiftDate(hospitalId, today.minusDays(1))).thenReturn(List.of(
            shift("dr.onduty", LocalTime.of(20, 0), LocalTime.of(6, 0), StaffShiftStatus.SCHEDULED),
            shift("inf.night", LocalTime.of(20, 0), LocalTime.of(6, 0), StaffShiftStatus.SCHEDULED)));

        assertThat(recipients.cover(hospitalId, "dr.prescriber")).containsExactly("dr.onduty");
        // The nurse in charge is the nurse on duty now, in a bed or not.
        assertThat(recipients.nursesInCharge(hospitalId, true)).containsExactly("inf.night");
        assertThat(recipients.nursesInCharge(hospitalId, false)).containsExactly("inf.night");
    }

    @Test
    @DisplayName("the cover: with no roster at all, every other doctor of the hospital; the prescriber alone covers himself")
    void thenEveryDoctor() {
        assertThat(recipients.cover(hospitalId, "dr.prescriber"))
            .containsExactlyInAnyOrder("dr.oncall", "dr.onduty", "dr.off", "dr.surgeon");
        assertThat(recipients.cover(hospitalId, null)).contains("dr.prescriber");

        when(staff.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_DOCTOR")).thenReturn(List.of("dr.prescriber"));
        when(staff.findActiveUsernamesByHospitalAndRole(hospitalId, "ROLE_SURGEON")).thenReturn(List.of());
        assertThat(recipients.cover(hospitalId, "dr.prescriber")).containsExactly("dr.prescriber");
    }

    @Test
    @DisplayName("with no roster, the nurses of the hospital for a patient in a bed or in the emergency unit, nobody for an outpatient")
    void theNurseInChargeWithNoRoster() {
        assertThat(recipients.nursesInCharge(hospitalId, true)).containsExactlyInAnyOrder("inf.night", "inf.day");
        assertThat(recipients.nursesInCharge(hospitalId, false)).isEmpty();
    }

    @Test
    @DisplayName("the médecin-chef: the doctor the hospital named, else its heads of department who are doctors, else every doctor")
    void theMedicalLead() {
        // Nobody named, no head of department: every doctor.
        assertThat(recipients.medicalLead(hospital)).contains("dr.prescriber", "dr.off", "dr.surgeon");

        Department medicine = new Department();
        medicine.setHeadOfDepartment(member("dr.off"));
        Department laboratory = new Department();
        laboratory.setHeadOfDepartment(member("bio.kabore"));
        when(departments.findByHospitalId(hospitalId)).thenReturn(List.of(medicine, laboratory, new Department()));
        // The head of the laboratory is not a doctor: she is told as the biologist, not as médecin-chef.
        assertThat(recipients.medicalLead(hospital)).containsExactly("dr.off");

        User named = new User();
        named.setId(UUID.randomUUID());
        named.setUsername("dr.surgeon");
        named.setActive(true);
        hospital.setCriticalMedicalLeadUserId(named.getId());
        when(users.findById(named.getId())).thenReturn(Optional.of(named));
        assertThat(recipients.medicalLead(hospital)).containsExactly("dr.surgeon");

        // A named person who is no longer a doctor here tells nobody: the heads of department are told.
        named.setUsername("dr.gone");
        assertThat(recipients.medicalLead(hospital)).containsExactly("dr.off");
        assertThat(recipients.medicalLead(null)).isEmpty();
    }

    @Test
    @DisplayName("the biologist and the administrators are read by their own role, at this hospital")
    void theBiologistAndTheAdministrators() {
        assertThat(recipients.biologists(hospitalId)).containsExactly("bio.kabore");
        assertThat(recipients.administrators(hospitalId)).containsExactly("admin.one");
        assertThat(recipients.biologists(null)).isEmpty();
        assertThat(recipients.administrators(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("a prescriber is absent when an APPROVED leave covers today, and only then")
    void thePrescriberOnApprovedLeave() {
        Staff prescriber = member("dr.prescriber");
        when(leaves.findLeavesOverlappingDate(prescriber.getId(), NOW.toLocalDate(), List.of(StaffLeaveStatus.APPROVED)))
            .thenReturn(List.of(new StaffLeaveRequest()));

        assertThat(recipients.prescriberAbsent(prescriber)).isTrue();
        assertThat(recipients.prescriberAbsent(member("dr.onduty"))).isFalse();
        assertThat(recipients.prescriberAbsent(null)).isFalse();
    }
}
