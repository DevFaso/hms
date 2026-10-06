package com.example.hms.service.integration.message;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class IntegrationMessageRetentionPolicyTest {

    @Test
    void theDefaultsAreInForce() {
        IntegrationMessageRetentionPolicy policy = new IntegrationMessageRetentionPolicy(true, 180, 365);

        assertThat(policy.configurationProblem()).isNull();
        assertThat(policy.isActive()).isTrue();
    }

    @Test
    void disabledIsNotActiveEvenWithValidWindows() {
        IntegrationMessageRetentionPolicy policy = new IntegrationMessageRetentionPolicy(false, 180, 365);

        assertThat(policy.configurationProblem()).isNull();
        assertThat(policy.isActive()).isFalse();
    }

    @Test
    void aWindowBelowOneDayIsAProblemAndNotActive() {
        IntegrationMessageRetentionPolicy policy = new IntegrationMessageRetentionPolicy(true, 0, 365);

        assertThat(policy.configurationProblem()).contains("payload-days");
        assertThat(policy.isActive()).isFalse();
    }

    @Test
    void aCeilingShorterThanTheWindowIsAProblemAndNotActive() {
        IntegrationMessageRetentionPolicy policy = new IntegrationMessageRetentionPolicy(true, 180, 179);

        assertThat(policy.configurationProblem()).contains("unresolved-max-days");
        assertThat(policy.isActive()).isFalse();
    }

    @Test
    void aCeilingEqualToTheWindowIsAccepted() {
        assertThat(new IntegrationMessageRetentionPolicy(true, 180, 180).isActive()).isTrue();
    }
}
