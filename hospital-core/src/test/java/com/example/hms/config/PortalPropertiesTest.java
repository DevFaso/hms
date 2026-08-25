package com.example.hms.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PortalPropertiesTest {

    @Test
    void settersRoundTrip() {
        PortalProperties props = new PortalProperties();
        props.setProfileCompletionUrlTemplate("https://e-keneya.com/onboarding/role-welcome?assignment=%s");
        props.setAssignerConfirmationUrlTemplate("https://e-keneya.com/super/assignments?confirm=%s");

        assertThat(props.getProfileCompletionUrlTemplate())
                .startsWith("https://e-keneya.com/")
                .doesNotContain("bitnesttechs.com");
        assertThat(props.getAssignerConfirmationUrlTemplate())
                .startsWith("https://e-keneya.com/")
                .doesNotContain("bitnesttechs.com");
    }

    @Test
    void logResolvedTemplatesDoesNotThrowOnNullValues() {
        // Defensive: if somebody removes the YAML defaults the @PostConstruct
        // log line must not blow up application startup.
        PortalProperties props = new PortalProperties();
        assertThatCode(props::logResolvedTemplates).doesNotThrowAnyException();
    }
}
