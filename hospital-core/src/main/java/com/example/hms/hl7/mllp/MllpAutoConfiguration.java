package com.example.hms.hl7.mllp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the MLLP listener only when {@code app.hl7.mllp.enabled=true}.
 *
 * <p>The default is off everywhere — including {@code dev}, {@code test},
 * {@code local-h2}, and {@code prod}. Operations enable the flag per
 * environment via env var ({@code APP_HL7_MLLP_ENABLED=true}) once the
 * downstream firewall + analyzer routing is in place.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(MllpProperties.class)
@ConditionalOnProperty(prefix = "app.hl7.mllp", name = "enabled", havingValue = "true")
public class MllpAutoConfiguration {

    @Bean(destroyMethod = "stop")
    public MllpTcpServer mllpTcpServer(MllpProperties properties, Hl7MessageDispatcher dispatcher) {
        return new MllpTcpServer(properties, dispatcher);
    }
}
