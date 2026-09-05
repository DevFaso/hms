package com.example.hms.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME_NAME = "BearerAuth";

    /** X-API-Key scheme for the /partner surface (Tier 2 item 45). */
    public static final String API_KEY_SCHEME_NAME = "ApiKeyAuth";

    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                .info(apiInfo())
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME_NAME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME_NAME, jwtSecurityScheme())
                        .addSecuritySchemes(API_KEY_SCHEME_NAME, apiKeySecurityScheme()));
    }

    private SecurityScheme apiKeySecurityScheme() {
        return new SecurityScheme()
                .name(com.example.hms.security.ApiKeyAuthenticationFilter.API_KEY_HEADER)
                .type(SecurityScheme.Type.APIKEY)
                .in(SecurityScheme.In.HEADER)
                .description("Issued API key for third-party clients (Tier 2 item 45). "
                        + "Authenticates the /partner surface only; staff JWTs cannot reach it.");
    }

    private Info apiInfo() {
        return new Info()
                .title("Hospital Management System API")
                .version("v1.0.0")
                .description("Comprehensive API documentation for managing hospital resources, users, authentication, and patient records.")
                .contact(new Contact()
                        .name("DevFaso Team")
                        .url("https://www.devfaso.bf")
                        .email("support@devfaso.bf"))
                .license(new License().name("Apache 2.0").url("http://springdoc.org"));
    }

    private SecurityScheme jwtSecurityScheme() {
        return new SecurityScheme()
                .name(SECURITY_SCHEME_NAME)
                .type(SecurityScheme.Type.HTTP)
                .scheme("bearer")
                .bearerFormat("JWT")
                .description("Enter your JWT token like: `Bearer eyJhbGciOiJIUzUxMiJ9.eyJzdWIiOiJ0Y2hpY28iLCJyb2xlcyI6IlJPTEVfVVNFUixST0xFX0FETUlOIiwiaWF0IjoxNzQ4MTAzNzU0LCJleHAiOjE3NDgxOTAxNTR9.66n8OvttTrkjHTI6RQ8BNk9rvh4g1LWMrGv0IVXkkuMN4ZW7cBFKMuM7CBznNrkkQw8GzoY2HZQmxLdhK_2ubQ`");
    }
}

