package com.example.hms.service.transfusion;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.MfaStepUpRefusedException;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("QA D4141: the second bedside checker confirms with their own factor")
class BedsideVerifierConfirmationTest {

    private final MfaService mfaService = mock(MfaService.class);
    private final PasswordEncoder passwordEncoder = mock(PasswordEncoder.class);
    private final LoginAttemptService loginAttemptService = mock(LoginAttemptService.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final BedsideVerifierConfirmation confirmation =
        new BedsideVerifierConfirmation(mfaService, passwordEncoder, loginAttemptService, audit);

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID performer = UUID.randomUUID();
    private User verifier;

    @BeforeEach
    void setUp() {
        verifier = new User();
        verifier.setId(UUID.randomUUID());
        verifier.setPasswordHash("{bcrypt}hash");
        // The login counter runs the comparison it is handed, as the real one does for an unlocked account.
        when(loginAttemptService.checkColleaguePassword(eq(verifier.getId()), any())).thenAnswer(call ->
            ((BooleanSupplier) call.getArgument(1)).getAsBoolean()
                ? LoginAttemptService.PasswordCheck.ACCEPTED : LoginAttemptService.PasswordCheck.REJECTED);
    }

    private AuditEventRequestDTO audited() {
        ArgumentCaptor<AuditEventRequestDTO> event = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(event.capture());
        return event.getValue();
    }

    @Test
    void theirOwnPasswordConfirms_andTheOutcomeIsAuditedAgainstThem_withoutTheSecret() {
        when(passwordEncoder.matches("Their-Password-1", "{bcrypt}hash")).thenReturn(true);

        assertThat(confirmation.confirm(verifier, performer, hospitalId, "Their-Password-1")).isEqualTo("PASSWORD");

        AuditEventRequestDTO event = audited();
        assertThat(event.getUserId()).isEqualTo(verifier.getId());
        assertThat(event.getStatus()).isEqualTo(AuditStatus.SUCCESS);
        assertThat(event.getEventType()).isEqualTo(AuditEventType.MFA_VERIFIED);
        assertThat(event.getDetails()).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("performedBy", performer.toString());
        assertThat(event.toString()).doesNotContain("Their-Password-1");
    }

    @Test
    void aWrongPasswordIsRefusedOnTheSecretField_countedInTheColleagueBucket_andAudited() {
        when(passwordEncoder.matches(anyString(), anyString())).thenReturn(false);

        assertThatThrownBy(() -> confirmation.confirm(verifier, performer, hospitalId, "a guess"))
            .isInstanceOf(FieldBusinessException.class)
            .hasMessage("transfusion.verifier.invalid")
            .satisfies(refused -> assertThat(((FieldBusinessException) refused).getField()).isEqualTo("verifierSecret"));

        verify(loginAttemptService).checkColleaguePassword(eq(verifier.getId()), any());
        AuditEventRequestDTO event = audited();
        assertThat(event.getStatus()).isEqualTo(AuditStatus.FAILURE);
        assertThat(event.getEventType()).isEqualTo(AuditEventType.MFA_FAILURE);
        assertThat(event.toString()).doesNotContain("a guess");
    }

    @Test
    void aLockedAccountIsRefusedBeforeAnyComparison() {
        when(loginAttemptService.checkColleaguePassword(eq(verifier.getId()), any()))
            .thenReturn(LoginAttemptService.PasswordCheck.LOCKED);

        assertThatThrownBy(() -> confirmation.confirm(verifier, performer, hospitalId, "Their-Password-1"))
            .isInstanceOf(FieldBusinessException.class).hasMessage("transfusion.verifier.locked");
        verify(passwordEncoder, never()).matches(anyString(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void nothingTypedIsNotAConfirmation(String blank) {
        assertThatThrownBy(() -> confirmation.confirm(verifier, performer, hospitalId, blank))
            .isInstanceOf(FieldBusinessException.class).hasMessage("transfusion.verifier.required");
        assertThatThrownBy(() -> confirmation.confirm(verifier, performer, hospitalId, null))
            .isInstanceOf(FieldBusinessException.class).hasMessage("transfusion.verifier.required");
        verify(loginAttemptService, never()).checkColleaguePassword(any(), any());
    }

    @Test
    void withAnAuthenticatorTheirCodeIsAsked_neverTheirPassword() {
        when(mfaService.isMfaEnabled(verifier.getId())).thenReturn(true);

        assertThat(confirmation.methodFor(verifier)).isEqualTo("TOTP");
        assertThat(confirmation.confirm(verifier, performer, hospitalId, " 123456 ")).isEqualTo("TOTP");

        verify(mfaService).requireStepUp(eq(verifier.getId()), eq("123456"), anyString(), eq(true));
        verify(loginAttemptService, never()).checkColleaguePassword(any(), any());
    }

    @Test
    void aRefusedCodeIsRefused() {
        when(mfaService.isMfaEnabled(verifier.getId())).thenReturn(true);
        doThrow(new MfaStepUpRefusedException(MfaStepUpRefusedException.Reason.REUSED))
            .when(mfaService).requireStepUp(any(), anyString(), anyString(), anyBoolean());

        assertThatThrownBy(() -> confirmation.confirm(verifier, performer, hospitalId, "123456"))
            .isInstanceOf(FieldBusinessException.class).hasMessage("transfusion.verifier.reused");
    }

    @Test
    void anAccountWithNoFactorHereCannotConfirm() {
        verifier.setPasswordHash(null);

        assertThat(confirmation.methodFor(verifier)).isEqualTo("UNAVAILABLE");
        assertThatThrownBy(() -> confirmation.confirm(verifier, performer, hospitalId, "anything"))
            .isInstanceOf(FieldBusinessException.class).hasMessage("transfusion.verifier.unavailable");
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_DOCTOR", "role_nurse"})
    void theBedsideTeamIsTheNurseTheMidwifeAndTheDoctor(String code) {
        assertThat(BedsideVerifierConfirmation.holdsBedsideRole(staffWith(code))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ROLE_RECEPTIONIST", "ROLE_BILLING_SPECIALIST", "ROLE_ACCOUNTANT", "ROLE_LAB_SCIENTIST",
        "ROLE_LAB_TECHNICIAN", "ROLE_PHARMACIST", "ROLE_HOSPITAL_ADMIN", "ROLE_PATIENT", "ROLE_IMAGING_TECHNICIAN"})
    void nobodyElseIs(String code) {
        assertThat(BedsideVerifierConfirmation.holdsBedsideRole(staffWith(code))).isFalse();
    }

    @Test
    void aStaffRecordWithNoRoleIsNotOfTheBedsideTeam() {
        assertThat(BedsideVerifierConfirmation.holdsBedsideRole(null)).isFalse();
        assertThat(BedsideVerifierConfirmation.holdsBedsideRole(new Staff())).isFalse();
    }

    private static Staff staffWith(String code) {
        Role role = new Role();
        role.setCode(code);
        UserRoleHospitalAssignment assignment = new UserRoleHospitalAssignment();
        assignment.setRole(role);
        Staff staff = new Staff();
        staff.setAssignment(assignment);
        com.example.hms.model.User account = new com.example.hms.model.User();
        account.setId(java.util.UUID.randomUUID());
        staff.setUser(account);
        return staff;
    }

    /** Review of QA D4141: a retired colleague is no second check, whatever their role says. */
    @Test
    void anInactiveAccountRecordOrAssignmentIsNotOfTheBedsideTeam() {
        Staff disabledAccount = staffWith("ROLE_NURSE");
        disabledAccount.getUser().setActive(false);
        Staff deletedAccount = staffWith("ROLE_NURSE");
        deletedAccount.getUser().setDeleted(true);
        Staff retiredRecord = staffWith("ROLE_NURSE");
        retiredRecord.setActive(false);
        Staff endedAssignment = staffWith("ROLE_NURSE");
        endedAssignment.getAssignment().setActive(false);
        Staff noAccount = staffWith("ROLE_NURSE");
        noAccount.setUser(null);

        assertThat(List.of(disabledAccount, deletedAccount, retiredRecord, endedAssignment, noAccount))
            .noneMatch(BedsideVerifierConfirmation::holdsBedsideRole);
    }
}
