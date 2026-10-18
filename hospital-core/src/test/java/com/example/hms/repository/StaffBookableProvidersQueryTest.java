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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D169: the patient booking form could not offer a midwife in maternity
 * (the provider query knew only doctors and nurses) and offered departments
 * where nobody could be booked. Pinned against a real database.
 */
@TenantScopedDataJpaTest
class StaffBookableProvidersQueryTest {

    @Autowired private StaffRepository staffRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private Hospital hospital;
    private Department maternity;
    private Department empty;
    private Department pharmacyOnly;
    private Staff midwife;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospital = hospitalRepository.save(Hospital.builder().name("Hopital M").code("BKM" + nextId()).build());
        Role midwifeRole = role("ROLE_MIDWIFE");
        Role pharmacistRole = role("ROLE_PHARMACIST");

        UserRoleHospitalAssignment owner = assignment(user("own"), midwifeRole);
        maternity = department("Maternite", owner);
        empty = department("Vide", owner);
        pharmacyOnly = department("Pharmacie", owner);

        midwife = staff(maternity, midwifeRole);
        staff(pharmacyOnly, pharmacistRole);
        HospitalContextHolder.clear();
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("a midwife is a bookable provider in maternity")
    void midwifeIsBookable() {
        assertThat(staffRepository.findActiveProvidersByHospitalAndDepartment(hospital.getId(), maternity.getId()))
            .extracting(Staff::getId).containsExactly(midwife.getId());
    }

    @Test
    @DisplayName("only departments with a bookable provider are listed")
    void departmentsWithoutProviderAreNotListed() {
        assertThat(staffRepository.findDepartmentIdsWithActiveProviders(hospital.getId()))
            .containsExactly(maternity.getId())
            .doesNotContain(empty.getId(), pharmacyOnly.getId());
    }

    private Role role(String code) {
        return roleRepository.findByCode(code).orElseGet(() -> roleRepository.save(Role.builder()
            .name(code + " " + nextId()).code(code).description("booking").build()));
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

    private Department department(String name, UserRoleHospitalAssignment owner) {
        return departmentRepository.save(Department.builder()
            .hospital(hospital)
            .assignment(owner)
            .name(name)
            .code("D" + nextId())
            .build());
    }

    private Staff staff(Department department, Role role) {
        User user = userRepository.save(user("st"));
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

    private User user(String prefix) {
        String suffix = nextId();
        return userRepository.save(User.builder()
            .username(prefix + suffix)
            .passwordHash("hashed-password")
            .email(prefix + suffix + "@booking.test")
            .firstName(prefix)
            .lastName("User" + suffix)
            .phoneNumber("+22673" + suffix)
            .isActive(true)
            .build());
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
