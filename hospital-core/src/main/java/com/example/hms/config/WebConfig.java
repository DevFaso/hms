package com.example.hms.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Paths;

/**
 * Web configuration for static file serving and other web-related settings
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    /**
     * Static serving is limited to profile images — the ONE upload class
     * that must stay reachable through a bare {@code <img src>} (web +
     * both mobile apps render avatars without a bearer token). Every
     * other class under the upload root (patient documents, chat
     * attachments, chart/referral attachments) is PHI and is served only
     * through authenticated, ownership-checked endpoints; mapping the
     * whole tree here is what made the permitAll /uploads/** hole a
     * download-anything hole.
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

        String profileImagesPath = Paths.get(uploadDir, "profile-images").toAbsolutePath().toString();

        registry.addResourceHandler("/uploads/profile-images/**")
                .addResourceLocations("file:" + profileImagesPath + "/")
                .setCachePeriod(3600); // Cache for 1 hour
    }
}