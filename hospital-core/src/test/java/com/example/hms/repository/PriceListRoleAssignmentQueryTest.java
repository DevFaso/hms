package com.example.hms.repository;

import com.example.hms.model.Hospital;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Billing-money plan PR 2 (owner decision D-1): who maintains and who reads a hospital's price list, proved on real
 * {@code user_role_hospital_assignment} rows through {@link RoleValidator#canMaintainPriceList} and
 * {@link RoleValidator#canReadPriceList}. The role must be held on an ACTIVE assignment AT that hospital: the
 * platform super-admin's own assignment, the same role at another hospital, an inactive row and the cashier never
 * maintain it.
 */
@TenantScopedDataJpaTest
@DisplayName("Price list: the maintainer and reader roles on real assignments")
class PriceListRoleAssignmentQueryTest {

    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;

    private final AtomicInteger sequence = new AtomicInteger();
    private RoleValidator roleValidator;
    private Hospital hospitalA;
    private Hospital hospitalB;

    @BeforeEach
    void seed() {
        HospitalContextHolder.setContext(HospitalContext.builder().superAdmin(true).build());
        roleValidator = new RoleValidator(assignmentRepository, mock(ActingScopeResolver.class));
        hospitalA = hospitalRepository.save(Hospital.builder().name("Test A").code("PLA" + nextId()).build());
        hospitalB = hospitalRepository.save(Hospital.builder().name("Test B").code("PLB" + nextId()).build());
    }

    @AfterEach
    void clearContext() {
        HospitalContextHolder.clear();
    }

    @Test
    void theHospitalAdminAndTheAccountantAtTheHospitalMaintainIt() {
        UUID admin = holder("ROLE_HOSPITAL_ADMIN", hospitalA, true);
        UUID accountant = holder("ROLE_ACCOUNTANT", hospitalA, true);
        assertThat(roleValidator.canMaintainPriceList(admin, hospitalA.getId())).isTrue();
        assertThat(roleValidator.canMaintainPriceList(accountant, hospitalA.getId())).isTrue();
        assertThat(roleValidator.canReadPriceList(admin, hospitalA.getId())).isTrue();
        assertThat(roleValidator.canReadPriceList(accountant, hospitalA.getId())).isTrue();
    }

    @Test
    void theSuperAdminWithNoRoleAtTheHospitalNeitherMaintainsNorReadsThroughThisCheck() {
        // A super-admin assignment is global: it names no hospital (the model refuses one that does).
        UUID superAdmin = holder("ROLE_SUPER_ADMIN", null, true);
        assertThat(roleValidator.canMaintainPriceList(superAdmin, hospitalA.getId())).isFalse();
        assertThat(roleValidator.canReadPriceList(superAdmin, hospitalA.getId())).isFalse();
    }

    @Test
    void anAccountantOfAnotherHospitalDoesNotMaintainIt() {
        UUID accountantAtB = holder("ROLE_ACCOUNTANT", hospitalB, true);
        assertThat(roleValidator.canMaintainPriceList(accountantAtB, hospitalA.getId())).isFalse();
        assertThat(roleValidator.canReadPriceList(accountantAtB, hospitalA.getId())).isFalse();
        assertThat(roleValidator.canMaintainPriceList(accountantAtB, hospitalB.getId())).isTrue();
    }

    @Test
    void anInactiveAccountantAssignmentDoesNotCount() {
        UUID former = holder("ROLE_ACCOUNTANT", hospitalA, false);
        assertThat(roleValidator.canMaintainPriceList(former, hospitalA.getId())).isFalse();
    }

    @Test
    void theCashierReceptionAndClaimsReviewerReadButNeverMaintain() {
        for (String role : new String[] {"ROLE_BILLING_SPECIALIST", "ROLE_RECEPTIONIST", "ROLE_CLAIMS_REVIEWER"}) {
            UUID user = holder(role, hospitalA, true);
            assertThat(roleValidator.canMaintainPriceList(user, hospitalA.getId())).as(role).isFalse();
            assertThat(roleValidator.canReadPriceList(user, hospitalA.getId())).as(role).isTrue();
        }
    }

    @Test
    void aDoctorNeitherMaintainsNorReads() {
        UUID doctor = holder("ROLE_DOCTOR", hospitalA, true);
        assertThat(roleValidator.canMaintainPriceList(doctor, hospitalA.getId())).isFalse();
        assertThat(roleValidator.canReadPriceList(doctor, hospitalA.getId())).isFalse();
    }

    private UUID holder(String roleCode, Hospital hospital, boolean active) {
        Role role = roleRepository.findByCode(roleCode).orElseGet(() -> roleRepository.save(Role.builder()
            .name(roleCode + " " + nextId()).code(roleCode).description("price list test").build()));
        String suffix = nextId();
        User user = userRepository.save(User.builder()
            .username("pl" + suffix)
            .passwordHash("hashed-password")
            .email("pl" + suffix + "@price-list.test")
            .firstName("Test")
            .lastName("User" + suffix)
            .phoneNumber("+22673" + suffix)
            .isActive(true)
            .build());
        assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("PL-" + suffix)
            .user(user)
            .hospital(hospital)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(active)
            .build());
        return user.getId();
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
