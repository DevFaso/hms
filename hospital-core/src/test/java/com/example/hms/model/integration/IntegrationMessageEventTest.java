package com.example.hms.model.integration;

import com.example.hms.enums.integration.IntegrationMessageDirection;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MVP-c3 — Coverage for the {@code @PrePersist touch()} hook on
 * {@link IntegrationMessageEvent}. Mockito-based recorder tests
 * stub {@code repository.save(...)}, so the JPA lifecycle callback
 * never fires there; this test invokes it directly via reflection
 * to lock in the default-population semantics for partner-protocol
 * rows that arrive without explicit timestamps / attempt counts.
 */
class IntegrationMessageEventTest {

    @Test
    void touchPopulatesDefaultsWhenAllNullableFieldsAreUnset() throws Exception {
        IntegrationMessageEvent event = IntegrationMessageEvent.builder()
            .integrationId("partner.nhis")
            .direction(IntegrationMessageDirection.OUTBOUND)
            .status(IntegrationMessageStatus.SENT)
            .build();

        invokeTouch(event);

        assertThat(event.getReceivedAt()).isNotNull();
        assertThat(event.getLastAttemptedAt()).isNotNull();
        assertThat(event.getAttemptCount()).isEqualTo(1);
    }

    @Test
    void touchPreservesCallerProvidedValues() throws Exception {
        LocalDateTime fixedReceivedAt = LocalDateTime.of(2026, 5, 4, 12, 0);
        LocalDateTime fixedAttemptedAt = LocalDateTime.of(2026, 5, 4, 12, 5);
        IntegrationMessageEvent event = IntegrationMessageEvent.builder()
            .integrationId("partner.nhis")
            .direction(IntegrationMessageDirection.OUTBOUND)
            .status(IntegrationMessageStatus.FAILED)
            .receivedAt(fixedReceivedAt)
            .lastAttemptedAt(fixedAttemptedAt)
            .attemptCount(3)
            .build();

        invokeTouch(event);

        // touch() is defensive — callers (the recorder) already set
        // these on the new entity; the hook only fills defaults when
        // the field is null.
        assertThat(event.getReceivedAt()).isEqualTo(fixedReceivedAt);
        assertThat(event.getLastAttemptedAt()).isEqualTo(fixedAttemptedAt);
        assertThat(event.getAttemptCount()).isEqualTo(3);
    }

    @Test
    void gettersAndSettersRoundTripEveryField() {
        // Lombok-generated, but JaCoCo still counts the @AllArgsConstructor
        // chain. Exercising every setter once locks in the contract and
        // gives Sonar a clean line.
        IntegrationMessageEvent event = new IntegrationMessageEvent();
        java.util.UUID id = java.util.UUID.randomUUID();
        java.util.UUID orgId = java.util.UUID.randomUUID();
        LocalDateTime now = LocalDateTime.now();
        event.setId(id);
        event.setIntegrationId("partner.nhis");
        event.setOrganizationId(orgId);
        event.setDirection(IntegrationMessageDirection.INBOUND);
        event.setMessageType("FHIR/Bundle");
        event.setCorrelationId("trace-001");
        event.setPayload("{}");
        event.setStatus(IntegrationMessageStatus.RECEIVED);
        event.setErrorMessage(null);
        event.setAttemptCount(1);
        event.setReceivedAt(now);
        event.setLastAttemptedAt(now);

        assertThat(event.getId()).isEqualTo(id);
        assertThat(event.getOrganizationId()).isEqualTo(orgId);
        assertThat(event.getDirection()).isEqualTo(IntegrationMessageDirection.INBOUND);
        assertThat(event.getMessageType()).isEqualTo("FHIR/Bundle");
        assertThat(event.getCorrelationId()).isEqualTo("trace-001");
        assertThat(event.getPayload()).isEqualTo("{}");
        assertThat(event.getStatus()).isEqualTo(IntegrationMessageStatus.RECEIVED);
        assertThat(event.getAttemptCount()).isEqualTo(1);
        assertThat(event.getReceivedAt()).isEqualTo(now);
        assertThat(event.getLastAttemptedAt()).isEqualTo(now);
    }

    private static void invokeTouch(IntegrationMessageEvent event) throws Exception {
        Method m = IntegrationMessageEvent.class.getDeclaredMethod("touch");
        m.setAccessible(true);
        m.invoke(event);
        // ReflectionTestUtils import retained for symmetry with other
        // entity tests in the codebase even though we use a direct
        // method invocation here.
        ReflectionTestUtils.getField(event, "id");
    }
}
