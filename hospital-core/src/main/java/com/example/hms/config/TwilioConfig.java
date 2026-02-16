package com.example.hms.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.twilio.http.TwilioRestClient;

@Configuration
@EnableConfigurationProperties(TwilioProperties.class)
@ConditionalOnProperty(prefix = "twilio", name = "enabled", havingValue = "true")
public class TwilioConfig {

    @Bean
    public TwilioRestClient twilioRestClient(TwilioProperties props) {
        if (props.getAccountSid() == null || props.getAuthToken() == null) {
            throw new IllegalStateException("Twilio enabled but accountSid/authToken are missing");
        }
        return new TwilioRestClient
            .Builder(props.getAccountSid(), props.getAuthToken())
            .build();
    }
}
