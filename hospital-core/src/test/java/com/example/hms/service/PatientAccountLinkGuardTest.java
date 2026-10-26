package com.example.hms.service;

import com.example.hms.exception.ConflictException;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * QA D819: the account a patient record logs in with is a patient-only
 * account, linked to no other record. The QA case: a receptionist posted a new
 * patient with her own staff account's userId, and that staff login became the
 * patient's portal login.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PatientAccountLinkGuardTest {

    @Mock private StaffRepository staffRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PatientRepository patientRepository;

    @InjectMocks
    private PatientAccountLinkGuard guard;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUserRoles(new HashSet<>());
    }

    @Test
    @DisplayName("the QA case: an account with a RECEPTIONIST assignment is refused (409 on userId)")
    void aReceptionistAccountIsRefused() {
        when(assignmentRepository.existsByUserIdAndRole_CodeNot(user.getId(), "ROLE_PATIENT")).thenReturn(true);

        assertThatThrownBy(() -> guard.requireLinkable(user, null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("userId:patient.account.staffAccount");
    }

    @Test
    @DisplayName("an account with a staff record and no assignment left is refused")
    void aStaffRecordIsRefused() {
        when(staffRepository.existsByUserId(user.getId())).thenReturn(true);

        assertThatThrownBy(() -> guard.requireLinkable(user, null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("userId:patient.account.staffAccount");
    }

    @Test
    @DisplayName("a super-admin (a global role, no hospital assignment) is refused")
    void aGlobalStaffRoleIsRefused() {
        user.getUserRoles().add(link(role("ROLE_SUPER_ADMIN")));

        assertThatThrownBy(() -> guard.requireLinkable(user, null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("userId:patient.account.staffAccount");
    }

    @Test
    @DisplayName("a patient-only account (PATIENT role, either code form) with no record yet is linkable")
    void aPatientOnlyAccountIsLinkable() {
        user.getUserRoles().add(link(role("ROLE_PATIENT")));
        User legacy = new User();
        legacy.setId(UUID.randomUUID());
        legacy.setUserRoles(Set.of(link(role("PATIENT"))));

        assertThatCode(() -> guard.requireLinkable(user, null)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireLinkable(legacy, null)).doesNotThrowAnyException();
        assertThat(guard.isStaffAccount(user)).isFalse();
    }

    @Test
    @DisplayName("an account already linked to a record is refused on a create")
    void anAlreadyLinkedAccountIsRefusedOnCreate() {
        when(patientRepository.existsByUserId(user.getId())).thenReturn(true);

        assertThatThrownBy(() -> guard.requireLinkable(user, null))
            .isInstanceOf(ConflictException.class)
            .hasMessage("userId:patient.account.alreadyLinked");
    }

    @Test
    @DisplayName("a relink is refused when the account is another record's login, allowed when it is no one's")
    void aRelinkLooksAtOtherRecordsOnly() {
        UUID patientId = UUID.randomUUID();
        when(patientRepository.existsByUser_IdAndIdNot(user.getId(), patientId)).thenReturn(false);
        assertThatCode(() -> guard.requireLinkable(user, patientId)).doesNotThrowAnyException();

        when(patientRepository.existsByUser_IdAndIdNot(user.getId(), patientId)).thenReturn(true);
        assertThatThrownBy(() -> guard.requireLinkable(user, patientId))
            .isInstanceOf(ConflictException.class)
            .hasMessage("userId:patient.account.alreadyLinked");
    }

    @Test
    @DisplayName("the staff check runs first: a staff account is never reported as merely linked")
    void staffWinsOverLinked() {
        when(staffRepository.existsByUserId(user.getId())).thenReturn(true);
        when(patientRepository.existsByUserId(user.getId())).thenReturn(true);

        assertThatThrownBy(() -> guard.requireLinkable(user, null))
            .hasMessage("userId:patient.account.staffAccount");
    }

    private static Role role(String code) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setCode(code);
        role.setName(code);
        return role;
    }

    private UserRole link(Role role) {
        UserRole link = new UserRole();
        link.setRole(role);
        return link;
    }
}
