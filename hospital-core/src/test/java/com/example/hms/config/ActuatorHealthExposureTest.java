package com.example.hms.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/** QA D199: anonymous /actuator/health shows the status only, never the components. */
class ActuatorHealthExposureTest {

    @Test
    @DisplayName("health details and components are for an authorised super-admin only")
    void healthDetailsAreNotAnonymous() throws IOException {
        Properties props = PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));

        assertThat(props.getProperty("management.endpoint.health.show-details")).isEqualTo("when_authorized");
        assertThat(props.getProperty("management.endpoint.health.show-components")).isEqualTo("when_authorized");
        assertThat(props.getProperty("management.endpoint.health.roles")).isEqualTo("SUPER_ADMIN");
    }
}
