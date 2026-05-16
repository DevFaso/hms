package com.example.hms.config.datasource;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReplicaDataSourcePropertiesTest {

    @Test
    @DisplayName("Defaults match the documented baseline — disabled, sane Hikari sizing")
    void defaultsAreSafe() {
        ReplicaDataSourceProperties p = new ReplicaDataSourceProperties();
        assertThat(p.isEnabled()).isFalse();
        assertThat(p.getDriverClassName()).isEqualTo("org.postgresql.Driver");
        assertThat(p.getHikari().getMaximumPoolSize()).isEqualTo(10);
        assertThat(p.getHikari().getMinimumIdle()).isEqualTo(2);
        assertThat(p.getHikari().getConnectionTimeoutMs()).isEqualTo(5_000L);
        assertThat(p.getHikari().getMaxLifetimeMs()).isEqualTo(1_800_000L);
        assertThat(p.getHikari().getIdleTimeoutMs()).isEqualTo(600_000L);
        assertThat(p.getHikari().getLeakDetectionThresholdMs()).isZero();
        assertThat(p.getHikari().getPoolName()).isEqualTo("hms-replica-pool");
    }

    @Test
    @DisplayName("validateForActivation is a no-op when disabled")
    void disabledSkipsValidation() {
        ReplicaDataSourceProperties p = new ReplicaDataSourceProperties();
        // url + username + password all null is fine when disabled
        p.validateForActivation();
    }

    @Test
    @DisplayName("Activation fails fast when URL is blank")
    void blankUrlFailsFast() {
        ReplicaDataSourceProperties p = new ReplicaDataSourceProperties();
        p.setEnabled(true);
        p.setUsername("u");
        p.setPassword("p");
        assertThatThrownBy(p::validateForActivation)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.datasource.replica.url");
    }

    @Test
    @DisplayName("Activation fails fast when username is blank")
    void blankUsernameFailsFast() {
        ReplicaDataSourceProperties p = new ReplicaDataSourceProperties();
        p.setEnabled(true);
        p.setUrl("jdbc:postgresql://replica:5432/db");
        p.setPassword("p");
        assertThatThrownBy(p::validateForActivation)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.datasource.replica.username");
    }

    @Test
    @DisplayName("Activation fails fast when password is null (empty string is allowed)")
    void nullPasswordFailsFast() {
        ReplicaDataSourceProperties p = new ReplicaDataSourceProperties();
        p.setEnabled(true);
        p.setUrl("jdbc:postgresql://replica:5432/db");
        p.setUsername("u");
        p.setPassword(null);
        assertThatThrownBy(p::validateForActivation)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("app.datasource.replica.password");
    }

    @Test
    @DisplayName("Empty-string password is accepted (trust auth)")
    void emptyPasswordAllowedForTrustAuth() {
        ReplicaDataSourceProperties p = new ReplicaDataSourceProperties();
        p.setEnabled(true);
        p.setUrl("jdbc:postgresql://replica:5432/db");
        p.setUsername("u");
        p.setPassword("");
        // Must not throw
        p.validateForActivation();
    }
}
