package com.example.hms.service.recordaccess;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.AuditStatus;
import com.example.hms.exception.BusinessException;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.service.AuditEventLogService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("QA D4269: a refused action on a minor's own account is audited, then answered")
class MinorPortalRightsGuardTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 11, 27);

    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final MinorPortalRightsGuard guard = new MinorPortalRightsGuard(audit,
        Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC));

    private static Patient born(LocalDate dateOfBirth) {
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        patient.setDateOfBirth(dateOfBirth);
        User account = new User();
        account.setId(UUID.randomUUID());
        patient.setUser(account);
        return patient;
    }

    @Test
    void aNineYearOldsGrantIsRefusedWith403AndAuditedWithoutContent() {
        Patient child = born(LocalDate.of(2017, 9, 2));

        assertThatThrownBy(() -> guard.require(child, MinorPortalRights.Action.GRANT_FAMILY_ACCESS))
            .isInstanceOf(ClientSafeAccessDeniedException.class);

        ArgumentCaptor<AuditEventRequestDTO> row = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit).logEvent(row.capture());
        assertThat(row.getValue().getEventType()).isEqualTo(AuditEventType.MINOR_PORTAL_ACTION_REFUSED);
        assertThat(row.getValue().getStatus()).isEqualTo(AuditStatus.FAILURE);
        assertThat(row.getValue().getPatientId()).isEqualTo(child.getId());
        assertThat(row.getValue().getUserId()).isEqualTo(child.getUser().getId());
        assertThat(row.getValue().getEventDescription()).isEqualTo(MinorPortalRightsGuard.REFUSAL_DESCRIPTION);
        assertThat(String.valueOf(row.getValue().getDetails())).contains("GRANT_FAMILY_ACCESS", "UNDER_13");
    }

    @Test
    void aSixteenYearOldClosesSharingAloneAndAnAdultDoesAnything() {
        Patient sixteen = born(TODAY.minusYears(16));
        assertThatCode(() -> guard.require(sixteen, MinorPortalRights.Action.CLOSE_SHARING)).doesNotThrowAnyException();
        assertThatCode(() -> guard.require(sixteen, MinorPortalRights.Action.WITHDRAW_CONTACT_CONSENT))
            .doesNotThrowAnyException();
        Patient adult = born(TODAY.minusYears(18));
        for (MinorPortalRights.Action action : MinorPortalRights.Action.values()) {
            assertThatCode(() -> guard.require(adult, action)).doesNotThrowAnyException();
        }
        verifyNoInteractions(audit);
    }

    @Test
    void aSixteenYearOldReopeningSharingOrGrantingIsRefused() {
        Patient sixteen = born(TODAY.minusYears(16));
        assertThatThrownBy(() -> guard.require(sixteen, MinorPortalRights.Action.REOPEN_SHARING))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> guard.require(sixteen, MinorPortalRights.Action.GRANT_FAMILY_ACCESS))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> guard.require(sixteen, MinorPortalRights.Action.GIVE_CONTACT_CONSENT))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }

    @Test
    void noAccountIsActivatedUnder13AndOneIsFrom13() {
        Patient twelve = born(TODAY.minusYears(13).plusDays(1));
        assertThatThrownBy(() -> guard.requireActivatable(twelve))
            .isInstanceOf(BusinessException.class)
            .extracting("messageKey").isEqualTo("portal.minor.noAccountUnder13");
        Patient thirteen = born(TODAY.minusYears(13));
        assertThatCode(() -> guard.requireActivatable(thirteen)).doesNotThrowAnyException();
    }

    /** QA D4269 review: a refused activation names who acted (the signed-in caller), never the patient by default. */
    @Test
    void aRefusedActivationNamesTheSignedInCallerOrNoOne() {
        Patient child = born(TODAY.minusYears(9));
        org.mockito.ArgumentCaptor<AuditEventRequestDTO> row = org.mockito.ArgumentCaptor.forClass(AuditEventRequestDTO.class);

        // The anonymous e-mail link: no session, no actor.
        com.example.hms.security.context.HospitalContextHolder.clear();
        assertThatThrownBy(() -> guard.requireActivatable(child)).isInstanceOf(BusinessException.class);
        verify(audit).logEvent(row.capture());
        assertThat(row.getValue().getUserId()).isNull();

        // The desk or an administrator: the caller.
        UUID deskUser = UUID.randomUUID();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder().principalUserId(deskUser).build());
        try {
            assertThatThrownBy(() -> guard.requireActivatable(child)).isInstanceOf(BusinessException.class);
            verify(audit, org.mockito.Mockito.times(2)).logEvent(row.capture());
            assertThat(row.getValue().getUserId()).isEqualTo(deskUser);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }

        // Asked without a refusal (a restore): answered, nothing audited.
        assertThat(guard.mayHaveOwnAccount(child)).isFalse();
        assertThat(guard.mayHaveOwnAccount(born(TODAY.minusYears(13)))).isTrue();
        verify(audit, org.mockito.Mockito.times(2)).logEvent(any());
    }

    @Test
    void aFailedAuditNeverLetsTheActionThrough() {
        when(audit.logEvent(any())).thenThrow(new IllegalStateException("sink down"));
        Patient child = born(LocalDate.of(2017, 9, 2));
        assertThatThrownBy(() -> guard.require(child, MinorPortalRights.Action.CLOSE_SHARING))
            .isInstanceOf(ClientSafeAccessDeniedException.class);
    }
}
