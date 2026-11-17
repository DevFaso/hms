package com.example.hms.repository;

import com.example.hms.enums.WardType;
import com.example.hms.model.Department;
import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.Ward;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D601: the department delete refuses on {@code existsByClinicalReference}
 * instead of a foreign key that several tables lack. Pinned against a real
 * database so the JPQL (19 EXISTS branches) is parsed and answers both ways.
 */
@TenantScopedDataJpaTest
class DepartmentClinicalReferenceQueryTest {

    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private WardRepository wardRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;

    private Hospital hospital;
    private UserRoleHospitalAssignment owner;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        hospital = hospitalRepository.save(Hospital.builder().name("Hopital R").code("DCR01").build());
        Role role = roleRepository.findByCode("ROLE_HOSPITAL_ADMIN").orElseGet(() -> roleRepository.save(
            Role.builder().name("Admin DCR").code("ROLE_HOSPITAL_ADMIN").description("dcr").build()));
        User user = userRepository.save(User.builder().username("dcr01").passwordHash("hashed-password")
            .email("dcr01@ref.test").firstName("D").lastName("CR").phoneNumber("+2267301").isActive(true).build());
        owner = assignmentRepository.save(UserRoleHospitalAssignment.builder().assignmentCode("ASSIGN-DCR01")
            .user(user).hospital(hospital).role(role).startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now()).active(true).build());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    private Department department(String code) {
        return departmentRepository.save(Department.builder()
            .hospital(hospital).assignment(owner).name("Dept " + code).code(code).build());
    }

    @Test
    @DisplayName("a department nothing points at is not referenced")
    void unreferencedDepartment() {
        Department bare = department("DCRA");
        assertThat(departmentRepository.existsByClinicalReference(bare.getId())).isFalse();
    }

    @Test
    @DisplayName("a department a ward points at is referenced")
    void wardReferencesDepartment() {
        Department used = department("DCRB");
        wardRepository.saveAndFlush(Ward.builder().hospital(hospital).department(used)
            .name("Ward B").code("WB").wardType(WardType.GENERAL).active(true).build());
        assertThat(departmentRepository.existsByClinicalReference(used.getId())).isTrue();
    }
}
