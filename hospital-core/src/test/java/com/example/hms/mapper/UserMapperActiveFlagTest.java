package com.example.hms.mapper;

import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D910: GET and PUT /users/{id} answer the ACCOUNT's status. An account an
 * administrator switched off kept reading {@code active: true} because one of
 * its assignments was still on, while the list, the database and sign-in all
 * said inactive.
 */
class UserMapperActiveFlagTest {

    private final UserMapper mapper = new UserMapper();

    private static User user(boolean active) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("qa.veilleur");
        user.setActive(active);
        return user;
    }

    private static UserRoleHospitalAssignment assignment(boolean active) {
        UserRoleHospitalAssignment row = new UserRoleHospitalAssignment();
        row.setId(UUID.randomUUID());
        row.setActive(active);
        return row;
    }

    @Test
    @DisplayName("a switched-off account reads inactive even with an assignment still on")
    void switchedOffAccountWithActiveAssignmentIsInactive() {
        assertThat(mapper.toResponseDTO(user(false), Set.of(assignment(true))).isActive()).isFalse();
    }

    @Test
    @DisplayName("an active account reads active even when none of its assignments is on")
    void activeAccountWithoutActiveAssignmentIsActive() {
        assertThat(mapper.toResponseDTO(user(true), Set.of(assignment(false))).isActive()).isTrue();
    }
}
