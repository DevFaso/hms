package com.example.hms.bootstrap;

import com.example.hms.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Fails fast at startup if JWT secret is weak or missing.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JwtSecretValidator implements ApplicationRunner {

    private final JwtProperties jwtProperties;

    @Override
    public void run(ApplicationArguments args) {
        String secret = jwtProperties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT secret is missing (env JWT_SECRET not set)");
        }
        if (secret.length() < 32) {
            throw new IllegalStateException("JWT secret too short (min 32 chars)");
        }
        log.info("JWT secret validated (length={})", secret.length());
    }
}
