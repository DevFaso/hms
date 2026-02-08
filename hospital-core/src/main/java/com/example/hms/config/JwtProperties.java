package com.example.hms.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    /** Secret for signing tokens (injected from env). */
    @NotBlank
    private String secret;

    /** Access token validity in ms (default 24h). */
    @Min(60000)
    private long accessTokenExpirationMs = 86_400_000L; // 24h default

    /** Refresh token validity in ms (default 48h). */
    @Min(60000)
    private long refreshTokenExpirationMs = 172_800_000L; // 48h default
}
