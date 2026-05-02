package com.example.hms.service.impl;

import com.example.hms.enums.ReferralEventType;
import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import com.example.hms.model.GeneralReferral;
import com.example.hms.model.ReferralEvent;
import com.example.hms.repository.ReferralEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("ReferralEventRecorderImpl")
class ReferralEventRecorderImplTest {

    @Mock private ReferralEventRepository repository;

    @InjectMocks private ReferralEventRecorderImpl recorder;

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static GeneralReferral referralIn(ReferralStatus status) {
        GeneralReferral r = new GeneralReferral();
        r.setId(UUID.randomUUID());
        r.setStatus(status);
        r.setUrgency(ReferralUrgency.PRIORITY);
        r.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        r.setReferralType(ReferralType.CONSULTATION);
        r.setReferralReason("test");
        return r;
    }

    @Test
    void userEventCapturesAuthenticatedPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(
            new TestingAuthenticationToken("dr.amy@hms.test", null, "ROLE_DOCTOR"));
        GeneralReferral r = referralIn(ReferralStatus.SUBMITTED);

        recorder.recordUserEvent(r, ReferralEventType.ACKNOWLEDGE, ReferralStatus.SUBMITTED, "ok");

        ArgumentCaptor<ReferralEvent> captor = ArgumentCaptor.forClass(ReferralEvent.class);
        verify(repository).save(captor.capture());
        ReferralEvent saved = captor.getValue();
        assertThat(saved.getReferralId()).isEqualTo(r.getId());
        assertThat(saved.getEventType()).isEqualTo(ReferralEventType.ACKNOWLEDGE);
        assertThat(saved.getFromStatus()).isEqualTo(ReferralStatus.SUBMITTED);
        assertThat(saved.getToStatus()).isEqualTo(r.getStatus());
        assertThat(saved.getActorUsername()).isEqualTo("dr.amy@hms.test");
        assertThat(saved.getActorLabel()).isEqualTo("USER");
        assertThat(saved.getNote()).isEqualTo("ok");
    }

    @Test
    void userEventWithoutAuthLeavesUsernameNull() {
        // No SecurityContext set — should not crash; row gets actorLabel=USER, username=null.
        GeneralReferral r = referralIn(ReferralStatus.DRAFT);

        recorder.recordUserEvent(r, ReferralEventType.SUBMIT, ReferralStatus.DRAFT, null);

        ArgumentCaptor<ReferralEvent> captor = ArgumentCaptor.forClass(ReferralEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isNull();
        assertThat(captor.getValue().getActorLabel()).isEqualTo("USER");
    }

    @Test
    void systemEventBuildsPrefixedLabel() {
        GeneralReferral r = referralIn(ReferralStatus.SCHEDULED);

        recorder.recordSystemEvent(r, ReferralEventType.EXPIRE, ReferralStatus.SCHEDULED,
            "scheduler", "sla breach");

        ArgumentCaptor<ReferralEvent> captor = ArgumentCaptor.forClass(ReferralEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isNull();
        assertThat(captor.getValue().getActorLabel()).isEqualTo("SYSTEM:scheduler");
        assertThat(captor.getValue().getEventType()).isEqualTo(ReferralEventType.EXPIRE);
    }

    @Test
    void systemEventWithBlankSourceFallsBackToUnknown() {
        GeneralReferral r = referralIn(ReferralStatus.SCHEDULED);

        recorder.recordSystemEvent(r, ReferralEventType.EXPIRE, ReferralStatus.SCHEDULED, "", "x");

        ArgumentCaptor<ReferralEvent> captor = ArgumentCaptor.forClass(ReferralEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorLabel()).isEqualTo("SYSTEM:unknown");
    }

    @Test
    void anonymousAuthenticationLeavesUsernameNull() {
        // Spring's AnonymousAuthenticationToken returns isAuthenticated()==true and a
        // principal of "anonymousUser". Recording that as the actor would pollute the
        // audit trail with a meaningless name; the recorder must treat it as unauthenticated.
        SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.AnonymousAuthenticationToken(
                "anon-key", "anonymousUser",
                java.util.List.of(
                    new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_ANONYMOUS"))));
        GeneralReferral r = referralIn(ReferralStatus.SUBMITTED);

        recorder.recordUserEvent(r, ReferralEventType.SUBMIT, ReferralStatus.DRAFT, null);

        ArgumentCaptor<ReferralEvent> captor = ArgumentCaptor.forClass(ReferralEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getActorUsername()).isNull();
        assertThat(captor.getValue().getActorLabel()).isEqualTo("USER");
    }
}
