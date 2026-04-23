package com.example.hms;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

// Redis is only used as a shared token-blacklist via RedisTemplate, not as a
// Spring Data repository store. Excluding RedisRepositoriesAutoConfiguration
// prevents Spring Data Redis from scanning every JPA repository on startup
// (which produced hundreds of INFO warnings and slowed boot enough to risk
// Railway's healthcheck timeout).
@SpringBootApplication(exclude = { RedisRepositoriesAutoConfiguration.class })
@ConfigurationPropertiesScan
@EnableScheduling
public class HmsApplication {

    public static void main(String[] args) {
        SpringApplication.run(HmsApplication.class, args);
    }
}
