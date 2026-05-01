package com.example.hms.model;

import com.example.hms.enums.ReferralSpecialty;
import com.example.hms.enums.ReferralStatus;
import com.example.hms.enums.ReferralType;
import com.example.hms.enums.ReferralUrgency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle state-machine guards for {@link GeneralReferral}.
 * Each transition method must reject illegal source statuses with IllegalStateException.
 */
class GeneralReferralStateMachineTest {

    private static GeneralReferral newReferralIn(ReferralStatus status) {
        GeneralReferral r = new GeneralReferral();
        r.setId(UUID.randomUUID());
        r.setStatus(status);
        r.setUrgency(ReferralUrgency.PRIORITY);
        r.setTargetSpecialty(ReferralSpecialty.CARDIOLOGY);
        r.setReferralType(ReferralType.CONSULTATION);
        r.setReferralReason("test");
        return r;
    }

    @Nested
    @DisplayName("submit() — DRAFT only")
    class SubmitGuard {
        @Test
        void submit_fromDraft_succeeds() {
            GeneralReferral r = newReferralIn(ReferralStatus.DRAFT);
            r.submit();
            assertThat(r.getStatus()).isEqualTo(ReferralStatus.SUBMITTED);
            assertThat(r.getSubmittedAt()).isNotNull();
            assertThat(r.getSlaDueAt()).isNotNull();
        }

        @Test
        void submit_fromAnyOtherStatus_throws() {
            for (ReferralStatus s : ReferralStatus.values()) {
                if (s == ReferralStatus.DRAFT) continue;
                GeneralReferral r = newReferralIn(s);
                assertThatThrownBy(r::submit)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("submit")
                    .hasMessageContaining(s.name());
            }
        }
    }

    @Nested
    @DisplayName("acknowledge() — SUBMITTED only")
    class AcknowledgeGuard {
        @Test
        void acknowledge_fromSubmitted_succeeds() {
            GeneralReferral r = newReferralIn(ReferralStatus.SUBMITTED);
            Staff doc = new Staff();
            doc.setId(UUID.randomUUID());
            r.acknowledge("ok", doc);
            assertThat(r.getStatus()).isEqualTo(ReferralStatus.ACKNOWLEDGED);
            assertThat(r.getAcknowledgedAt()).isNotNull();
            assertThat(r.getAcknowledgementNotes()).isEqualTo("ok");
            assertThat(r.getReceivingProvider()).isSameAs(doc);
        }

        @Test
        void acknowledge_fromOtherStatuses_throws() {
            Staff anyStaff = new Staff();
            for (ReferralStatus s : ReferralStatus.values()) {
                if (s == ReferralStatus.SUBMITTED) continue;
                GeneralReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.acknowledge("x", anyStaff))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("acknowledge");
            }
        }
    }

    @Nested
    @DisplayName("schedule() — ACKNOWLEDGED only")
    class ScheduleGuard {
        @Test
        void schedule_fromAcknowledged_succeeds() {
            GeneralReferral r = newReferralIn(ReferralStatus.ACKNOWLEDGED);
            LocalDateTime when = LocalDateTime.now().plusDays(3);
            r.schedule(when, "Clinic B");
            assertThat(r.getStatus()).isEqualTo(ReferralStatus.SCHEDULED);
            assertThat(r.getScheduledAppointmentAt()).isEqualTo(when);
            assertThat(r.getAppointmentLocation()).isEqualTo("Clinic B");
        }

        @Test
        void schedule_fromOtherStatuses_throws() {
            LocalDateTime when = LocalDateTime.now().plusDays(1);
            for (ReferralStatus s : ReferralStatus.values()) {
                if (s == ReferralStatus.ACKNOWLEDGED) continue;
                GeneralReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.schedule(when, "X"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("schedule");
            }
        }
    }

    @Nested
    @DisplayName("start() — ACKNOWLEDGED or SCHEDULED")
    class StartGuard {
        @Test
        void start_fromAcknowledged_succeeds() {
            GeneralReferral r = newReferralIn(ReferralStatus.ACKNOWLEDGED);
            r.start();
            assertThat(r.getStatus()).isEqualTo(ReferralStatus.IN_PROGRESS);
            assertThat(r.getStartedAt()).isNotNull();
        }

        @Test
        void start_fromScheduled_succeeds() {
            GeneralReferral r = newReferralIn(ReferralStatus.SCHEDULED);
            r.start();
            assertThat(r.getStatus()).isEqualTo(ReferralStatus.IN_PROGRESS);
            assertThat(r.getStartedAt()).isNotNull();
        }

        @Test
        void start_fromOtherStatuses_throws() {
            for (ReferralStatus s : ReferralStatus.values()) {
                if (s == ReferralStatus.ACKNOWLEDGED || s == ReferralStatus.SCHEDULED) continue;
                GeneralReferral r = newReferralIn(s);
                assertThatThrownBy(r::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("start");
            }
        }
    }

    @Nested
    @DisplayName("complete() — ACKNOWLEDGED, SCHEDULED, IN_PROGRESS")
    class CompleteGuard {
        @Test
        void complete_fromAnyMidLifecycleStatus_succeeds() {
            for (ReferralStatus s : new ReferralStatus[]{
                ReferralStatus.ACKNOWLEDGED, ReferralStatus.SCHEDULED, ReferralStatus.IN_PROGRESS
            }) {
                GeneralReferral r = newReferralIn(s);
                r.complete("done", "fu");
                assertThat(r.getStatus()).isEqualTo(ReferralStatus.COMPLETED);
                assertThat(r.getCompletedAt()).isNotNull();
                assertThat(r.getCompletionSummary()).isEqualTo("done");
                assertThat(r.getFollowUpRecommendations()).isEqualTo("fu");
            }
        }

        @Test
        void complete_fromOtherStatuses_throws() {
            for (ReferralStatus s : ReferralStatus.values()) {
                if (s == ReferralStatus.ACKNOWLEDGED
                    || s == ReferralStatus.SCHEDULED
                    || s == ReferralStatus.IN_PROGRESS) {
                    continue;
                }
                GeneralReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.complete("s", "f"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("complete");
            }
        }
    }

    @Nested
    @DisplayName("cancel() — any non-terminal")
    class CancelGuard {
        @Test
        void cancel_fromNonTerminal_succeeds() {
            for (ReferralStatus s : new ReferralStatus[]{
                ReferralStatus.DRAFT, ReferralStatus.SUBMITTED,
                ReferralStatus.ACKNOWLEDGED, ReferralStatus.SCHEDULED,
                ReferralStatus.IN_PROGRESS
            }) {
                GeneralReferral r = newReferralIn(s);
                r.cancel("reason");
                assertThat(r.getStatus()).isEqualTo(ReferralStatus.CANCELLED);
                assertThat(r.getCancellationReason()).isEqualTo("reason");
            }
        }

        @Test
        void cancel_fromTerminal_throws() {
            for (ReferralStatus s : new ReferralStatus[]{
                ReferralStatus.COMPLETED, ReferralStatus.CANCELLED,
                ReferralStatus.REJECTED, ReferralStatus.EXPIRED
            }) {
                GeneralReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.cancel("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal");
            }
        }
    }

    @Nested
    @DisplayName("reject() — SUBMITTED or ACKNOWLEDGED")
    class RejectGuard {
        @Test
        void reject_fromSubmitted_succeeds() {
            GeneralReferral r = newReferralIn(ReferralStatus.SUBMITTED);
            r.reject("not appropriate");
            assertThat(r.getStatus()).isEqualTo(ReferralStatus.REJECTED);
            assertThat(r.getCancellationReason()).isEqualTo("not appropriate");
        }

        @Test
        void reject_fromAcknowledged_succeeds() {
            GeneralReferral r = newReferralIn(ReferralStatus.ACKNOWLEDGED);
            r.reject("scope");
            assertThat(r.getStatus()).isEqualTo(ReferralStatus.REJECTED);
        }

        @Test
        void reject_fromOtherStatuses_throws() {
            for (ReferralStatus s : ReferralStatus.values()) {
                if (s == ReferralStatus.SUBMITTED || s == ReferralStatus.ACKNOWLEDGED) continue;
                GeneralReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.reject("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("reject");
            }
        }
    }

    @Test
    @DisplayName("Full happy path: DRAFT → SUBMITTED → ACKNOWLEDGED → SCHEDULED → IN_PROGRESS → COMPLETED")
    void fullHappyPath() {
        GeneralReferral r = newReferralIn(ReferralStatus.DRAFT);
        r.submit();
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.SUBMITTED);

        r.acknowledge("got it", new Staff());
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.ACKNOWLEDGED);

        r.schedule(LocalDateTime.now().plusDays(2), "Clinic 1");
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.SCHEDULED);

        r.start();
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.IN_PROGRESS);

        r.complete("seen", "follow up in 3 weeks");
        assertThat(r.getStatus()).isEqualTo(ReferralStatus.COMPLETED);
        assertThat(r.getCompletionSummary()).isEqualTo("seen");
    }
}
