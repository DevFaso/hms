package com.example.hms.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.example.hms.security.auth.TenantRoleAssignmentAccessor;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Prod logs feed error panels and alerts, so a condition the client causes and
 * the portal recovers from (an expired access token) must never be an ERROR,
 * and one that can mean probing (bad signature, malformed) is a WARN that
 * carries neither the token nor the exception message.
 */
@ExtendWith(MockitoExtension.class)
class AuthLogLevelGuardTest {

    private static final String SECRET = "log-level-guard-secret-minimum-256-bits-long-for-hmac!!";

    @Mock private HospitalUserDetailsService userDetailsService;
    @Mock private TenantRoleAssignmentAccessor tenantRoleAssignmentAccessor;

    private JwtTokenProvider provider;
    private Logger logger;
    private ListAppender<ILoggingEvent> appender;
    private Level previous;

    @BeforeEach
    void setUp() {
        provider = new JwtTokenProvider(userDetailsService, tenantRoleAssignmentAccessor);
        ReflectionTestUtils.setField(provider, "jwtSecret", SECRET);
        ReflectionTestUtils.setField(provider, "accessTokenExpirationMs", 900_000L);
        ReflectionTestUtils.setField(provider, "refreshTokenExpirationMs", 172_800_000L);
        ReflectionTestUtils.setField(provider, "rsaPrivateKeyPem", "");
        ReflectionTestUtils.setField(provider, "rsaPublicKeyPem", "");
        ReflectionTestUtils.setField(provider, "previousPublicKeyPem", "");
        provider.init();

        logger = (Logger) LoggerFactory.getLogger(JwtTokenProvider.class);
        previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(previous);
    }

    private String token(String secret, long expiresInMs) {
        return Jwts.builder()
            .subject("someone.private")
            .id(UUID.randomUUID().toString())
            .issuedAt(new Date(System.currentTimeMillis() - 10_000))
            .expiration(new Date(System.currentTimeMillis() + expiresInMs))
            .signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
            .compact();
    }

    private List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    @Test
    @DisplayName("an expired access token is rejected at DEBUG, never WARN or ERROR")
    void expiredTokenIsDebug() {
        String expired = token(SECRET, -5_000);

        assertThat(provider.validateToken(expired)).isFalse();

        assertThat(events()).isNotEmpty();
        assertThat(events()).allMatch(e -> e.getLevel() == Level.DEBUG);
    }

    @Test
    @DisplayName("a bad signature is rejected at WARN, with neither the token nor the subject in the log")
    void badSignatureIsWarn() {
        String forged = token("another-secret-minimum-256-bits-long-for-hmac-sha-256!!", 60_000);

        assertThat(provider.validateToken(forged)).isFalse();

        assertThat(events()).hasSize(1);
        ILoggingEvent event = events().get(0);
        assertThat(event.getLevel()).isEqualTo(Level.WARN);
        assertThat(event.getFormattedMessage()).doesNotContain(forged).doesNotContain("someone.private");
        assertThat(event.getThrowableProxy()).isNull();
    }

    @Test
    @DisplayName("a malformed token is rejected at WARN, not ERROR")
    void malformedTokenIsWarn() {
        assertThat(provider.validateToken("not.a.jwt")).isFalse();

        assertThat(events()).hasSize(1);
        assertThat(events().get(0).getLevel()).isEqualTo(Level.WARN);
    }
}
