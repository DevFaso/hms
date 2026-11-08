package com.example.hms.architecture;

import com.example.hms.controller.BreakGlassController;
import com.example.hms.controller.CdsAcknowledgementController;
import com.example.hms.controller.MortalityController;
import com.example.hms.controller.OrderReviewHoldController;
import com.example.hms.controller.PatientStoryboardController;
import com.example.hms.security.RoleExpansion;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1524 (class: a side panel of the patient chart rendered for a role its endpoint refuses; the 403
 * then sent the whole chart to /error/403). The voided-death banner sat under the BPA panel's roles,
 * which hold the pharmacist; GET .../voided-closures does not. Every panel the chart renders above its
 * sections is listed here with the roles it renders for and the endpoint it reads on load, and each of
 * those roles must be one the endpoint admits. A panel's GET is a background request, so a later
 * drift costs the panel, never the page. A new panel fails here until it is listed.
 */
class ChartSidePanelRoleGuardTest {

    private static final Path APP = Path.of("../hospital-portal/src/app");
    private static final Path DETAIL_HTML = APP.resolve("patients/patient-detail.html");
    private static final Path DETAIL_TS = APP.resolve("patients/patient-detail.ts");
    private static final Path CHART_ACCESS = APP.resolve("patients/patient-chart/chart-access.ts");
    private static final Pattern QUOTED_ROLE = Pattern.compile("'(?:ROLE_)?([A-Z_]+)'");
    private static final Pattern PANEL = Pattern.compile("<(app-[a-z-]+)");
    private static final Set<String> DOCTOR_ROLES = Set.of("DOCTOR", "PHYSICIAN", "SURGEON");

    /** One side panel: the roles it renders for (portal) against the roles its load-time read admits (server). */
    private record Panel(String element, Set<String> shownTo, Set<String> admitted) {
    }

    private static List<Panel> panels() throws IOException {
        String breakGlass = read(APP.resolve("patients/break-glass-banner/break-glass-banner.component.ts"));
        Set<String> askers = rolesOf(between(breakGlass, "DECLARE_ROLES = new Set([", "]);"));
        // canAskForMySession: the declaring roles plus the front desk.
        assertThat(between(breakGlass, "canAskForMySession(): boolean {", "\n  }")).contains("'ROLE_RECEPTIONIST'");
        askers.add("RECEPTIONIST");
        return List.of(
            new Panel("app-voided-death-closures-banner", list("VOIDED_CLOSURES_VIEW_ROLES"),
                rolesOf(gate(MortalityController.class, "getVoidedClosures"))),
            new Panel("app-storyboard-banner", list("STORYBOARD_VIEW_ROLES"),
                rolesOf(gate(PatientStoryboardController.class, "getStoryboard"))),
            new Panel("app-order-review-panel", list("ORDER_REVIEW_VIEW_ROLES"),
                rolesOf(gate(OrderReviewHoldController.class, "forPatient"))),
            new Panel("app-bpa-panel", list("CDS_CLINICIAN_ROLES"),
                rolesOf(CdsAcknowledgementController.CLINICIAN_ROLES)),
            new Panel("app-break-glass-banner", askers,
                rolesOf(gate(BreakGlassController.class, "findMyLiveSession"))));
    }

    @Test
    void everyRoleAPanelRendersForIsOneItsEndpointAdmits() throws IOException {
        List<String> refused = new ArrayList<>();
        for (Panel panel : panels()) {
            assertThat(panel.admitted()).as(panel.element() + ": its endpoint's roles were read").isNotEmpty();
            panel.shownTo().stream().filter(role -> !admits(panel.admitted(), role))
                .forEach(role -> refused.add(panel.element() + " renders for " + role + ", which its endpoint refuses"));
        }
        assertThat(refused).as("a chart side panel shown to a role its read refuses (QA D1524)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void everyPanelAboveTheSectionsIsListed() throws IOException {
        String html = read(DETAIL_HTML);
        int start = html.indexOf("<app-order-review-panel");
        int end = html.indexOf("<nav class=\"patient-navigation\"");
        assertThat(start).isNotNegative();
        assertThat(end).isGreaterThan(start);
        Set<String> found = new TreeSet<>();
        Matcher matcher = PANEL.matcher(html.substring(start, end));
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        Set<String> listed = new TreeSet<>();
        panels().forEach(panel -> listed.add(panel.element()));
        // The dispute dialog reads nothing on load: it opens on the user's action, as a dialog.
        listed.add("app-death-dispute-dialog");
        assertThat(found).as("a new chart side panel: list it with its roles and its endpoint (QA D1524)"
            + SourceScan.MANDATE).containsExactlyInAnyOrderElementsOf(listed);
    }

    /** Each list is the one gate of its panel, and the panel's load-time GET never redirects the page. */
    @Test
    void eachPanelRendersFromItsListAndReadsInTheBackground() throws IOException {
        String html = read(DETAIL_HTML);
        assertThat(guardOf(html, "<app-voided-death-closures-banner")).contains("canSeeVoidedClosures()")
            .doesNotContain("canSeeBpaPanel()");
        assertThat(guardOf(html, "<app-storyboard-banner")).contains("canSeeStoryboard()");
        assertThat(guardOf(html, "<app-bpa-panel")).contains("canSeeBpaPanel()");
        String detail = read(DETAIL_TS);
        assertThat(between(detail, "canSeeVoidedClosures(): boolean {", "}"))
            .contains("hasAnyActiveRole(VOIDED_CLOSURES_VIEW_ROLES)");
        assertThat(between(detail, "canSeeStoryboard(): boolean {", "}"))
            .contains("hasAnyActiveRole(STORYBOARD_VIEW_ROLES)");
        assertThat(read(APP.resolve("patients/order-review-panel/order-review-panel.component.ts")))
            .contains("hasAnyActiveRole(ORDER_REVIEW_VIEW_ROLES)");

        assertThat(between(read(APP.resolve("services/mortality.service.ts")), "voidedClosures(", "\n  }"))
            .contains("backgroundRequestOptions()");
        assertThat(between(read(APP.resolve("services/storyboard.service.ts")), "getStoryboard(", "\n  }"))
            .contains("backgroundRequestOptions()");
        assertThat(between(read(APP.resolve("services/order-review.service.ts")), "forPatient(", "\n  }"))
            .contains("backgroundRequestOptions()");
    }

    /** A physician or a surgeon IS a doctor, and a super-admin holds what it inherits (RoleExpansion). */
    private static boolean admits(Set<String> admitted, String role) {
        return RoleExpansion.expand(List.of("ROLE_" + role)).stream()
            .anyMatch(held -> admitted.contains(held.replaceFirst("^ROLE_", "")));
    }

    /** The method's own @PreAuthorize, else its controller's. */
    private static String gate(Class<?> controller, String methodName) {
        Method method = Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> m.getName().equals(methodName)).findFirst()
            .orElseThrow(() -> new IllegalStateException(controller.getSimpleName() + "#" + methodName + " is gone"));
        PreAuthorize own = method.getAnnotation(PreAuthorize.class);
        PreAuthorize gate = own != null ? own : controller.getAnnotation(PreAuthorize.class);
        assertThat(gate).as(controller.getSimpleName() + "#" + methodName + " has a role gate").isNotNull();
        return gate.value();
    }

    /** A role list of chart-access.ts, with DOCTOR_ROLES spelled out. */
    private static Set<String> list(String name) throws IOException {
        String code = read(CHART_ACCESS);
        int at = code.indexOf("export const " + name);
        assertThat(at).as(name + " in chart-access.ts").isNotNegative();
        String body = code.substring(code.indexOf('[', at), code.indexOf("];", at));
        Set<String> roles = rolesOf(body);
        if (body.contains("...DOCTOR_ROLES")) {
            roles.addAll(DOCTOR_ROLES);
        }
        return roles;
    }

    /** The template from the last @if before the element up to it. */
    private static String guardOf(String html, String element) {
        int at = html.indexOf(element);
        assertThat(at).as(element + " in patient-detail.html").isNotNegative();
        int open = html.lastIndexOf("@if (", at);
        return html.substring(Math.max(open, 0), at);
    }

    private static String between(String text, String from, String to) {
        int start = text.indexOf(from);
        assertThat(start).as(from).isNotNegative();
        int end = text.indexOf(to, start + from.length());
        return text.substring(start, end < 0 ? text.length() : end);
    }

    private static Set<String> rolesOf(String expression) {
        Set<String> roles = new TreeSet<>();
        Matcher matcher = QUOTED_ROLE.matcher(expression);
        while (matcher.find()) {
            roles.add(matcher.group(1));
        }
        return roles;
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
