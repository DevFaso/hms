package com.example.hms.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class PortalPropertiesTest {

    @Test
    void settersRoundTrip() {
        PortalProperties props = new PortalProperties();
        props.setProfileCompletionUrlTemplate("https://hms.uat.bitnesttechs.com/onboarding/role-welcome?assignment=%s");
        props.setAssignerConfirmationUrlTemplate("https://hms.uat.bitnesttechs.com/super/assignments?confirm=%s");

        assertThat(props.getProfileCompletionUrlTemplate())
                .startsWith("https://hms.uat.bitnesttechs.com/")
                .doesNotContain("hms-uat.bitnesttechs.com");
        assertThat(props.getAssignerConfirmationUrlTemplate())
                .startsWith("https://hms.uat.bitnesttechs.com/")
                .doesNotContain("hms-uat.bitnesttechs.com");
    }

    @Test
    void logResolvedTemplatesDoesNotThrowOnNullValues() {
        // Defensive: if somebody removes the YAML defaults the @PostConstruct
        // log line must not blow up application startup.
        PortalProperties props = new PortalProperties();
        assertThatCode(props::logResolvedTemplates).doesNotThrowAnyException();
    }
}
