package com.example.hms.model.referral;

import com.example.hms.enums.ObgynReferralCareContext;
import com.example.hms.enums.ObgynReferralStatus;
import com.example.hms.enums.ObgynReferralUrgency;
import com.example.hms.enums.ObgynTransferType;
import com.example.hms.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Lifecycle state-machine guards for {@link ObgynReferral}.
 * Each transition method must reject illegal source statuses with IllegalStateException.
 *
 * <p>The OB-GYN flow is intentionally simpler than {@code GeneralReferral}:
 * SUBMITTED → ACKNOWLEDGED → IN_PROGRESS → COMPLETED, with CANCELLED reachable
 * from any non-terminal status. There is no DRAFT, SCHEDULED, REJECTED, or
 * EXPIRED state.
 */
@SuppressWarnings("java:S100")
class ObgynReferralStateMachineTest {

    private static ObgynReferral newReferralIn(ObgynReferralStatus status) {
        ObgynReferral r = ObgynReferral.builder()
            .status(status)
            .urgency(ObgynReferralUrgency.ROUTINE)
            .careContext(ObgynReferralCareContext.ANTENATAL)
            .transferType(ObgynTransferType.CONSULTATION)
            .referralReason("test")
            .build();
        r.setId(UUID.randomUUID());
        return r;
    }

    @Nested
    @DisplayName("acknowledge() — SUBMITTED only")
    class AcknowledgeGuard {
        @Test
        void acknowledge_fromSubmitted_succeeds() {
            ObgynReferral r = newReferralIn(ObgynReferralStatus.SUBMITTED);
            User obgyn = new User();
            obgyn.setId(UUID.randomUUID());

            r.acknowledge("plan summary", obgyn);

            assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.ACKNOWLEDGED);
            assertThat(r.getAcknowledgementTimestamp()).isNotNull();
            assertThat(r.getPlanSummary()).isEqualTo("plan summary");
            assertThat(r.getObgyn()).isSameAs(obgyn);
        }

        @Test
        void acknowledge_fromOtherStatuses_throws() {
            User anyObgyn = new User();
            for (ObgynReferralStatus s : ObgynReferralStatus.values()) {
                if (s == ObgynReferralStatus.SUBMITTED) continue;
                ObgynReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.acknowledge("p", anyObgyn))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("acknowledge");
            }
        }
    }

    @Nested
    @DisplayName("start() — ACKNOWLEDGED only")
    class StartGuard {
        @Test
        void start_fromAcknowledged_succeeds() {
            ObgynReferral r = newReferralIn(ObgynReferralStatus.ACKNOWLEDGED);

            r.start();

            assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.IN_PROGRESS);
        }

        @Test
        void start_fromOtherStatuses_throws() {
            for (ObgynReferralStatus s : ObgynReferralStatus.values()) {
                if (s == ObgynReferralStatus.ACKNOWLEDGED) continue;
                ObgynReferral r = newReferralIn(s);
                assertThatThrownBy(r::start)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("start");
            }
        }
    }

    @Nested
    @DisplayName("complete() — ACKNOWLEDGED or IN_PROGRESS")
    class CompleteGuard {
        @Test
        void complete_fromAcknowledged_succeeds() {
            ObgynReferral r = newReferralIn(ObgynReferralStatus.ACKNOWLEDGED);

            r.complete("done", true);

            assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.COMPLETED);
            assertThat(r.getCompletionTimestamp()).isNotNull();
            assertThat(r.getCareTeamUpdatedAt()).isNotNull();
        }

        @Test
        void complete_fromInProgress_succeeds() {
            ObgynReferral r = newReferralIn(ObgynReferralStatus.IN_PROGRESS);

            r.complete("done", false);

            assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.COMPLETED);
            assertThat(r.getCompletionTimestamp()).isNotNull();
            // updateCareTeam=false leaves careTeamUpdatedAt untouched
            assertThat(r.getCareTeamUpdatedAt()).isNull();
        }

        @Test
        void complete_fromOtherStatuses_throws() {
            for (ObgynReferralStatus s : ObgynReferralStatus.values()) {
                if (s == ObgynReferralStatus.ACKNOWLEDGED || s == ObgynReferralStatus.IN_PROGRESS) {
                    continue;
                }
                ObgynReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.complete("x", false))
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
            for (ObgynReferralStatus s : new ObgynReferralStatus[]{
                ObgynReferralStatus.SUBMITTED,
                ObgynReferralStatus.ACKNOWLEDGED,
                ObgynReferralStatus.IN_PROGRESS
            }) {
                ObgynReferral r = newReferralIn(s);
                r.cancel("reason");
                assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.CANCELLED);
                assertThat(r.getCancellationReason()).isEqualTo("reason");
                assertThat(r.getCancelledTimestamp()).isNotNull();
            }
        }

        @Test
        void cancel_fromTerminalStatus_throws() {
            for (ObgynReferralStatus s : new ObgynReferralStatus[]{
                ObgynReferralStatus.COMPLETED, ObgynReferralStatus.CANCELLED
            }) {
                ObgynReferral r = newReferralIn(s);
                assertThatThrownBy(() -> r.cancel("x"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("terminal status");
            }
        }
    }

    @Test
    @DisplayName("Full happy path: SUBMITTED → ACKNOWLEDGED → IN_PROGRESS → COMPLETED")
    void fullHappyPath() {
        ObgynReferral r = newReferralIn(ObgynReferralStatus.SUBMITTED);
        User obgyn = new User();
        obgyn.setId(UUID.randomUUID());

        r.acknowledge("plan", obgyn);
        assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.ACKNOWLEDGED);

        r.start();
        assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.IN_PROGRESS);

        r.complete("seen", false);
        assertThat(r.getStatus()).isEqualTo(ObgynReferralStatus.COMPLETED);
        assertThat(r.getPlanSummary()).isEqualTo("seen");
    }
}
