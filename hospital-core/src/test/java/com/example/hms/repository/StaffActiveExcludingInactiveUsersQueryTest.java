package com.example.hms.repository;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D221: the dashboard's "Personnel actif" count and the staff list counted
 * an active staff row whose account was never activated. Pinned against a
 * real database.
 */
@TenantScopedDataJpaTest
class StaffActiveExcludingInactiveUsersQueryTest {

    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private Hospital hospital;
    private Staff activated;
    private Staff notActivated;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospital = hospitalRepository.save(Hospital.builder().name("Hopital A").code("ACT" + nextId()).build());
        Role nurseRole = roleRepository.findByCode("ROLE_NURSE").orElseGet(() -> roleRepository.save(Role.builder()
            .name("ROLE_NURSE " + nextId()).code("ROLE_NURSE").description("count").build()));
        UserRoleHospitalAssignment owner = assignment(user("own", true), nurseRole);
        Department ward = departmentRepository.save(Department.builder()
            .hospital(hospital).assignment(owner).name("Salle").code("D" + nextId()).build());
        activated = staff(ward, nurseRole, true);
        notActivated = staff(ward, nurseRole, false);
        HospitalContextHolder.clear();
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("an active staff row whose account is not activated is not active staff")
    void notActivatedAccountIsNotCounted() {
        Page<Staff> page = staffRepository.findByHospitalIdAndActiveTrueExcludingDeletedUsers(
            hospital.getId(), PageRequest.of(0, 20));
        assertThat(page.getTotalElements()).isEqualTo(1);
        assertThat(page.getContent()).extracting(Staff::getId)
            .containsExactly(activated.getId())
            .doesNotContain(notActivated.getId());
    }

    private UserRoleHospitalAssignment assignment(User user, Role role) {
        return assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
    }

    private Staff staff(Department department, Role role, boolean accountActive) {
        User user = user("st", accountActive);
        return staffRepository.save(Staff.builder()
            .user(user)
            .hospital(hospital)
            .department(department)
            .assignment(assignment(user, role))
            .jobTitle(JobTitle.NURSE)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("Staff " + user.getLastName())
            .active(true)
            .build());
    }

    private User user(String prefix, boolean active) {
        String suffix = nextId();
        return userRepository.save(User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@count.test")
            .firstName(prefix)
            .lastName("User" + suffix)
            .phoneNumber("+22674" + suffix)
            .isActive(active)
            .build());
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
