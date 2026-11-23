package com.example.hms.architecture;

import com.example.hms.repository.StaffRepository;
import com.example.hms.security.RoleExpansion;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA r70 D3204: a surgeon's Chirurgie slots were never bookable, because « bookable » listed the doctor, nurse
 * and midwife role codes while a surgeon's staff row carries ROLE_SURGEON, a role {@link RoleExpansion} treats as
 * a doctor. The class: a role that IS a doctor everywhere else was not one for booking.
 *
 * <ol>
 *   <li>Every role {@link RoleExpansion} widens into ROLE_DOCTOR is bookable, and so are the three providers.</li>
 *   <li>Every {@code StaffRepository} query that filters on provider role codes (the booking form's department
 *       list, its provider list, and the onboarding checklist that reads the same query) uses the one list,
 *       {@link StaffRepository#BOOKABLE_PROVIDER_ROLES_JPQL}; no query names its own.</li>
 * </ol>
 */
class BookableProviderRolesGuardTest {

    private static Set<String> bookable() {
        String list = StaffRepository.BOOKABLE_PROVIDER_ROLES_JPQL;
        return Arrays.stream(list.replaceAll("[()' ]", "").split(","))
            .filter(code -> !code.isBlank())
            .collect(Collectors.toSet());
    }

    @Test
    void everyDoctorEquivalentRoleIsBookable() {
        // The roles RoleExpansion turns into ROLE_DOCTOR, read through its own rule.
        List<String> candidates = List.of(RoleExpansion.ROLE_PHYSICIAN, "ROLE_SURGEON");
        for (String role : candidates) {
            assertThat(RoleExpansion.expand(List.of(role))).as("%s is a doctor", role).contains("ROLE_DOCTOR");
        }
        assertThat(bookable())
            .containsAll(candidates)
            .contains("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE");
    }

    @Test
    void everyProviderRoleQueryUsesTheOneList() {
        List<Method> byRole = Arrays.stream(StaffRepository.class.getDeclaredMethods())
            .filter(method -> method.isAnnotationPresent(Query.class))
            // A literal provider list (a query taking its codes as a parameter, the lab staff list, is not one).
            .filter(method -> method.getAnnotation(Query.class).value().contains("'ROLE_DOCTOR'"))
            .toList();
        assertThat(byRole).extracting(Method::getName)
            .contains("findActiveProvidersByHospitalAndDepartment", "findDepartmentIdsWithActiveProviders");
        for (Method method : byRole) {
            assertThat(method.getAnnotation(Query.class).value())
                .as(method.getName() + " names the bookable roles through BOOKABLE_PROVIDER_ROLES_JPQL")
                .contains(StaffRepository.BOOKABLE_PROVIDER_ROLES_JPQL);
        }
    }
}
