package com.example.hms.service;

import com.example.hms.config.PortalProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssignmentLinkServiceTest {

    private PortalProperties portalProperties;
    private AssignmentLinkService service;

    @BeforeEach
    void setUp() {
        portalProperties = new PortalProperties();
        service = new AssignmentLinkService(portalProperties);
    }

    // ── buildProfileCompletionUrl ────────────────────────────────────────────

    @Nested
    @DisplayName("buildProfileCompletionUrl")
    class BuildProfileCompletionUrl {

        @Test
        @DisplayName("uses default template with %s placeholder")
        void defaultTemplate() {
            // Default: "http://localhost:4200/onboarding/role-welcome?assignment=%s"
            String result = service.buildProfileCompletionUrl("ABC123");
            assertThat(result).isEqualTo("http://localhost:4200/onboarding/role-welcome?assignment=ABC123");
        }

        @Test
        @DisplayName("URL-encodes special characters in assignment code")
        void encodesSpecialCharacters() {
            String result = service.buildProfileCompletionUrl("code with spaces&more");
            assertThat(result).contains("code+with+spaces%26more");
        }

        @Test
        @DisplayName("returns null when template is null")
        void returnsNullWhenTemplateNull() {
            portalProperties.setProfileCompletionUrlTemplate(null);
            String result = service.buildProfileCompletionUrl("ABC123");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when template is blank")
        void returnsNullWhenTemplateBlank() {
            portalProperties.setProfileCompletionUrlTemplate("   ");
            String result = service.buildProfileCompletionUrl("ABC123");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when assignment code is null")
        void returnsNullWhenCodeNull() {
            String result = service.buildProfileCompletionUrl(null);
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when assignment code is blank")
        void returnsNullWhenCodeBlank() {
            String result = service.buildProfileCompletionUrl("  ");
            assertThat(result).isNull();
        }
    }

    // ── buildAssignerConfirmationUrl ─────────────────────────────────────────

    @Nested
    @DisplayName("buildAssignerConfirmationUrl")
    class BuildAssignerConfirmationUrl {

        @Test
        @DisplayName("uses default template with %s placeholder")
        void defaultTemplate() {
            // Default: "http://localhost:4200/super/assignments?confirm=%s"
            String result = service.buildAssignerConfirmationUrl("XYZ789");
            assertThat(result).isEqualTo("http://localhost:4200/super/assignments?confirm=XYZ789");
        }

        @Test
        @DisplayName("URL-encodes special characters")
        void encodesSpecialCharacters() {
            String result = service.buildAssignerConfirmationUrl("a=b&c=d");
            assertThat(result).contains("a%3Db%26c%3Dd");
        }

        @Test
        @DisplayName("returns null when template is null")
        void returnsNullWhenTemplateNull() {
            portalProperties.setAssignerConfirmationUrlTemplate(null);
            String result = service.buildAssignerConfirmationUrl("ABC");
            assertThat(result).isNull();
        }

        @Test
        @DisplayName("returns null when code is blank")
        void returnsNullWhenCodeBlank() {
            String result = service.buildAssignerConfirmationUrl("");
            assertThat(result).isNull();
        }
    }

    // ── formatUrl branch coverage ────────────────────────────────────────────

    @Nested
    @DisplayName("formatUrl - all template patterns")
    class FormatUrlBranches {

        @Test
        @DisplayName("handles {code} placeholder in template")
        void handlesCodePlaceholder() {
            portalProperties.setProfileCompletionUrlTemplate("https://app.example.com/complete/{code}");
            String result = service.buildProfileCompletionUrl("MY_CODE");
            assertThat(result).isEqualTo("https://app.example.com/complete/MY_CODE");
        }

        @Test
        @DisplayName("appends to template ending with /")
        void appendsToTrailingSlash() {
            portalProperties.setProfileCompletionUrlTemplate("https://app.example.com/complete/");
            String result = service.buildProfileCompletionUrl("MY_CODE");
            assertThat(result).isEqualTo("https://app.example.com/complete/MY_CODE");
        }

        @Test
        @DisplayName("appends with / when template has no placeholder and no trailing slash")
        void appendsWithSlash() {
            portalProperties.setProfileCompletionUrlTemplate("https://app.example.com/complete");
            String result = service.buildProfileCompletionUrl("MY_CODE");
            assertThat(result).isEqualTo("https://app.example.com/complete/MY_CODE");
        }

        @Test
        @DisplayName("uses %s placeholder when template contains both %s and {code}")
        void prefersPercentSPlaceholder() {
            // %s check comes first in the code
            portalProperties.setProfileCompletionUrlTemplate("https://example.com/%s/{code}");
            String result = service.buildProfileCompletionUrl("VAL");
            assertThat(result).isEqualTo("https://example.com/VAL/{code}");
        }

        @Test
        @DisplayName("handles {code} when template does not contain %s")
        void handlesCodeOnly() {
            portalProperties.setAssignerConfirmationUrlTemplate("https://admin.example.com/confirm?token={code}");
            String result = service.buildAssignerConfirmationUrl("TOKEN123");
            assertThat(result).isEqualTo("https://admin.example.com/confirm?token=TOKEN123");
        }

        @Test
        @DisplayName("returns null when template is empty string")
        void returnsNullWhenEmpty() {
            portalProperties.setProfileCompletionUrlTemplate("");
            String result = service.buildProfileCompletionUrl("CODE");
            assertThat(result).isNull();
        }
    }
}
