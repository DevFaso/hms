package com.example.hms.service.integration;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.PropertyResolver;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * Which SMS provider backs {@link IkoddiGateway}: {@code app.sms.provider},
 * {@code orange} (the default) or {@code ikoddi}.
 *
 * <p>Orange is the primary provider. The IKODDI implementation stays a bean in
 * both cases, because the Orange gateway still asks it to verify the codes
 * IKODDI issued before the switch (a short migration window). Only
 * {@code ikoddi} selects IKODDI; any other value, blank included, selects
 * Orange, and {@link OrangeSmsGatewayImpl} logs a value it does not recognise.
 * There is no automatic fallback from one provider to the other.
 */
public enum SmsProvider {
    ORANGE,
    IKODDI;

    public static final String PROPERTY = "app.sms.provider";

    /** The provider the environment selects. */
    public static SmsProvider resolve(PropertyResolver environment) {
        String value = environment.getProperty(PROPERTY, "orange");
        return "ikoddi".equals(value.trim().toLowerCase(Locale.ROOT)) ? IKODDI : ORANGE;
    }

    /**
     * Whether the selected provider has its credentials, read from the
     * environment at startup: Orange needs a client id and secret, IKODDI its
     * {@code app.ikoddi.enabled} flag. It decides whether the gateway-backed
     * {@code SmsService} replaces the logging mock.
     */
    public static boolean transportConfigured(PropertyResolver environment) {
        if (resolve(environment) == IKODDI) {
            return Boolean.parseBoolean(environment.getProperty("app.ikoddi.enabled", "false").trim());
        }
        return StringUtils.hasText(environment.getProperty("app.orange-sms.client-id"))
            && StringUtils.hasText(environment.getProperty("app.orange-sms.client-secret"));
    }

    /** Matches when Orange is the selected provider. */
    public static final class OrangeSelected implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return resolve(context.getEnvironment()) == ORANGE;
        }
    }

    /** Matches when the selected provider has its credentials. */
    public static final class TransportConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            return transportConfigured(context.getEnvironment());
        }
    }
}
