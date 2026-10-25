package com.example.hms.controller;

import com.example.hms.config.SecurityConstants;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D790: LAB_TECH_A, PHARMACIST_A, NURSE_A and RECEPTIONIST_A each paged
 * {@code GET /api/users?size=200} (27 accounts of hospital A: name, role,
 * active/deleted flags), because {@code getAllUsers} had no
 * {@code @PreAuthorize}. The directory and its search are the
 * administrators'; the chat picker has its own minimal endpoint, gated in
 * the service ({@code UserAccountAccess.requireColleagueDirectoryAccess}).
 */
class UserDirectoryAuthorizationTest {

    private static final List<String> TESTED_STAFF =
        List.of("ROLE_LAB_TECHNICIAN", "ROLE_PHARMACIST", "ROLE_NURSE", "ROLE_RECEPTIONIST", "ROLE_DOCTOR");

    @Test
    @DisplayName("GET /users and GET /users/search admit the administrators only")
    void directoryEndpointsAreAdminOnly() throws NoSuchMethodException {
        for (Method m : List.of(
                UserController.class.getMethod("getAllUsers", int.class, int.class, boolean.class, boolean.class),
                UserController.class.getMethod("searchUsers", String.class, String.class, String.class,
                    int.class, int.class, boolean.class, boolean.class))) {
            PreAuthorize gate = m.getAnnotation(PreAuthorize.class);
            assertThat(gate).as("%s must carry @PreAuthorize", m.getName()).isNotNull();
            assertThat(gate.value()).isEqualTo("hasAnyAuthority(" + SecurityConstants.USER_DIRECTORY_AUTHORITIES + ")");
        }
        List<String> admitted = Arrays.asList(SecurityConstants.authorities(SecurityConstants.USER_DIRECTORY_AUTHORITIES));
        assertThat(admitted).containsExactlyInAnyOrder("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_ADMIN");
        assertThat(admitted).doesNotContainAnyElementsOf(TESTED_STAFF);
    }

    @Test
    @DisplayName("the annotation value reads the shared constant, so the two cannot drift")
    void annotationReadsTheConstant() throws NoSuchMethodException {
        PreAuthorize gate = UserController.class
            .getMethod("getAllUsers", int.class, int.class, boolean.class, boolean.class)
            .getAnnotation(PreAuthorize.class);
        // Constant folding: the compiled annotation carries the constant's text.
        assertThat(gate.value()).isEqualTo("hasAnyAuthority(" + SecurityConstants.USER_DIRECTORY_AUTHORITIES + ")");
    }

    @Test
    @DisplayName("the chat picker endpoint exists for staff and is not one of the directory's")
    void chatRecipientsEndpoint() throws NoSuchMethodException {
        Method m = UserController.class.getMethod("getChatRecipients", int.class);
        assertThat(m.getAnnotation(org.springframework.web.bind.annotation.GetMapping.class).value())
            .containsExactly("/chat-recipients");
    }
}
