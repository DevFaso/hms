package com.example.hms.architecture;

import com.example.hms.controller.SafeguardingReviewController;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * r53, provisional rule (user decision 2026-11-14): the safeguarding details are visible only to the treating
 * clinical team and the administrators of that hospital; never to reception, billing, the patient portal, a proxy or
 * family member, the native apps, a notification or SMS to relatives, an export, or another hospital.
 *
 * <p>Rather than listing every patient-facing, proxy, reception, export and cross-hospital endpoint (a list that
 * would miss the next one), this guard proves there is exactly one way out: the review is held by one repository,
 * read by one service, returned by one controller, and that controller admits only the treating team and the
 * hospital administrator. Every other endpoint, whoever calls it, cannot return a review because it cannot reach
 * one. The cross-hospital refusal inside that controller is SafeguardingReviewServiceTest's; the role refusal through
 * real method security is SafeguardingReviewAccessTest's.
 */
@DisplayName("r53: a safeguarding review leaves the server through one controller, for the treating team only")
class SafeguardingAccessGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path PORTAL = Path.of("../hospital-portal/src/app");
    private static final List<Path> NATIVE_APPS = List.of(Path.of("../patient-android-app"), Path.of("../patient-ios-app"));

    private static final String MODEL = "model/safeguarding/SafeguardingReview.java";
    private static final String REPOSITORY = "repository/SafeguardingReviewRepository.java";
    private static final String WORKER = "service/safeguarding/SafeguardingReviewWorker.java";
    private static final String SCREEN = "service/safeguarding/MinorLabourSafeguarding.java";
    private static final String SERVICE = "service/safeguarding/SafeguardingReviewService.java";
    private static final String CONTROLLER = "controller/SafeguardingReviewController.java";
    private static final String DTO = "payload/dto/safeguarding/SafeguardingReviewDTO.java";

    /** Reference -> the only files that may make it. No allowlist grows here without a review of who reads it. */
    private static final Map<Pattern, Set<String>> ONLY_IN = Map.of(
        // The rows: written by the worker, read and closed by the service.
        Pattern.compile("\\bSafeguardingReviewRepository\\b"), Set.of(REPOSITORY, WORKER, SERVICE),
        // What a reader receives: built by the service, returned by the controller.
        Pattern.compile("\\bSafeguardingReviewDTO\\b"), Set.of(DTO, SERVICE, CONTROLLER),
        Pattern.compile("\\bSafeguardingReviewService\\b"), Set.of(SERVICE, CONTROLLER),
        // The entity itself (the admission paths may name its source constants, nothing else).
        Pattern.compile("\\bSafeguardingReview\\b(?!\\.SOURCE_)"), Set.of(MODEL, REPOSITORY, WORKER, SCREEN, SERVICE));

    /** The treating clinical team and the hospital administrator. */
    private static final Set<String> TREATING_TEAM_AND_ADMIN =
        Set.of("DOCTOR", "PHYSICIAN", "SURGEON", "MIDWIFE", "NURSE", "HOSPITAL_ADMIN");

    private static final Pattern QUOTED_ROLE = Pattern.compile("'(?:ROLE_)?([A-Z_]+)'");

    @Test
    void theReviewIsReachableOnlyThroughItsOwnServiceAndController() throws IOException {
        Map<String, Set<String>> stray = new TreeMap<>();
        Map<String, Set<String>> seen = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                ONLY_IN.forEach((reference, owners) -> {
                    if (reference.matcher(code).find()) {
                        seen.computeIfAbsent(reference.pattern(), k -> new TreeSet<>()).add(rel);
                        if (!owners.contains(rel)) {
                            stray.computeIfAbsent(reference.pattern(), k -> new TreeSet<>()).add(rel);
                        }
                    }
                });
            }
        }
        assertThat(seen.get("\\bSafeguardingReviewDTO\\b")).as("the scan found the controller").contains(CONTROLLER);
        assertThat(stray)
            .as("a class outside the safeguarding module reaches a review: every other endpoint (patient portal, proxy,"
                + " reception, billing, export, FHIR, mobile, another hospital) must stay unable to return one (r53)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void theControllerAdmitsOnlyTheTreatingTeamAndTheHospitalAdministrator() {
        List<String> wider = new ArrayList<>();
        PreAuthorize classGate = SafeguardingReviewController.class.getAnnotation(PreAuthorize.class);
        assertThat(classGate).as("the class-level gate is load-bearing on a new root").isNotNull();
        checkGate("class", classGate.value(), wider);
        int mappings = 0;
        for (Method method : SafeguardingReviewController.class.getDeclaredMethods()) {
            if (!hasMapping(method)) {
                continue;
            }
            mappings++;
            PreAuthorize gate = method.getAnnotation(PreAuthorize.class);
            assertThat(gate).as(method.getName() + " has its own role gate").isNotNull();
            checkGate(method.getName(), gate.value(), wider);
        }
        assertThat(mappings).as("the controller's mappings were read").isEqualTo(3);
        assertThat(wider).as("a safeguarding endpoint admits a role outside the treating team and the hospital"
            + " administrator (r53)" + SourceScan.MANDATE).isEmpty();
        assertThat(SafeguardingReviewController.class.getAnnotation(RequestMapping.class).value())
            .containsExactly("/safeguarding");
    }

    /** No portal screen but the chart banner calls it; the native patient apps never name it. */
    @Test
    void onlyTheStaffChartCallsTheSafeguardingEndpoints() throws IOException {
        List<String> callers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(PORTAL)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".ts") && !p.toString().endsWith(".spec.ts")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("'/safeguarding")) {
                    callers.add(PORTAL.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(callers).as("the portal's callers of /safeguarding").containsExactly("services/safeguarding.service.ts");
        for (Path app : NATIVE_APPS) {
            if (!Files.isDirectory(app)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(app)) {
                List<String> named = files.filter(Files::isRegularFile)
                    .filter(p -> p.toString().matches(".*\\.(kt|java|swift)$"))
                    .filter(p -> {
                        try {
                            return Files.readString(p, StandardCharsets.UTF_8).toLowerCase().contains("safeguarding");
                        } catch (IOException e) {
                            return false;
                        }
                    })
                    .map(Path::toString).toList();
                assertThat(named).as("a native patient app names the safeguarding review (r53)").isEmpty();
            }
        }
    }

    private static void checkGate(String where, String expression, List<String> wider) {
        Matcher matcher = QUOTED_ROLE.matcher(expression);
        int roles = 0;
        while (matcher.find()) {
            roles++;
            if (!TREATING_TEAM_AND_ADMIN.contains(matcher.group(1))) {
                wider.add(where + " admits " + matcher.group(1));
            }
        }
        assertThat(roles).as(where + ": roles read from " + expression).isPositive();
        assertThat(expression).as(where + " names roles only").doesNotContain("permitAll").doesNotContain("isAuthenticated");
    }

    private static boolean hasMapping(Method method) {
        return method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class)
            || method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
            || method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
            || method.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)
            || method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class)
            || method.isAnnotationPresent(RequestMapping.class);
    }
}
