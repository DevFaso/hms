package com.example.hms.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.twilio.http.TwilioRestClient;
import org.junit.jupiter.api.Test;

class TwilioConfigTest {

    private final TwilioConfig config = new TwilioConfig();

    @Test
    void twilioRestClientBuildsWhenCredentialsPresent() {
        TwilioProperties properties = new TwilioProperties();
        properties.setEnabled(true);
        properties.setAccountSid("ACtest00000000000000000000fake0sid");
        properties.setAuthToken("test-auth-token-not-real");

        TwilioRestClient client = config.twilioRestClient(properties);

        assertThat(client).isNotNull();
        assertThat(client.getAccountSid()).isEqualTo(properties.getAccountSid());
    }

    @Test
    void twilioRestClientFailsWhenCredentialsMissing() {
        TwilioProperties properties = new TwilioProperties();
        properties.setEnabled(true);
        properties.setAuthToken("test-auth-token-not-real");

        assertThatThrownBy(() -> config.twilioRestClient(properties))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("accountSid/authToken are missing");
    }
}
