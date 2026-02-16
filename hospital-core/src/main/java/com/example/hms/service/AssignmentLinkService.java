package com.example.hms.service;

import com.example.hms.config.PortalProperties;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@Service
public class AssignmentLinkService {

    private final PortalProperties portalProperties;

    public AssignmentLinkService(PortalProperties portalProperties) {
        this.portalProperties = portalProperties;
    }

    public String buildProfileCompletionUrl(String assignmentCode) {
        return formatUrl(portalProperties.getProfileCompletionUrlTemplate(), assignmentCode);
    }

    public String buildAssignerConfirmationUrl(String assignmentCode) {
        return formatUrl(portalProperties.getAssignerConfirmationUrlTemplate(), assignmentCode);
    }

    private String formatUrl(String template, String assignmentCode) {
        if (template == null || template.isBlank() || assignmentCode == null || assignmentCode.isBlank()) {
            return null;
        }
        String encoded = URLEncoder.encode(assignmentCode, StandardCharsets.UTF_8);
        if (template.contains("%s")) {
            return template.formatted(encoded);
        }
        if (template.contains("{code}")) {
            return template.replace("{code}", encoded);
        }
        if (template.endsWith("/")) {
            return template + encoded;
        }
        return template + "/" + encoded;
    }
}
