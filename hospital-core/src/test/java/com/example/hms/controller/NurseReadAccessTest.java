package com.example.hms.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reads a ward nurse must have, and the one they must not.
 *
 * <p>The portal listed Consultations for {@code ROLE_NURSE} and the route guard
 * admitted them, but {@code /overdue} refused them — so a nurse saw the
 * consultation COUNT on {@code /stats} and got a 403 on the overdue list behind
 * it. (An earlier version of this doc said three endpoints refused nurses; that
 * was the first cut of #587, narrowed during its own review to the one endpoint
 * that genuinely 403'd. The method comments below were correct; this was not.)
 * The imaging split was worse: {@code GET /order/{id}} admitted nurses and
 * {@code /order/{id}/all} did not, which withholds the addenda — the version
 * carrying "findings revised" — from the people acting on them.
 *
 * <p>Asserted against the annotation rather than through a {@code @WebMvcTest}
 * slice on purpose: the slices are expensive and fragile here (one new
 * WebMvcConfigurer once broke all 105 of them), and the annotation IS the
 * contract. This fails if someone narrows a guard again.
 */
@DisplayName("Nurse read access")
class NurseReadAccessTest {

    /**
     * One role check in a guard: {@code hasRole('X')}, {@code hasAnyRole('X','Y')},
     * {@code hasAuthority('ROLE_X')} or {@code hasAnyAuthority(...)}, optionally
     * negated with {@code !} or {@code not}.
     */
    private static final Pattern ROLE_CHECK = Pattern.compile(
        "(!\\s*|\\bnot\\s+)?\\bhas(?:Any)?(?:Role|Authority)\\s*\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    /**
     * The {@code @PreAuthorize} expression on the GET handler for {@code path}.
     * Resolved through Spring's merged-annotation view, as the dispatcher does:
     * {@code @GetMapping("/x")}, {@code @GetMapping(path = "/x")} and
     * {@code @RequestMapping(path = "/x", method = GET)} are one mapping to Spring,
     * so they are one mapping here.
     */
    private static String guardFor(Class<?> controller, String path) {
        Optional<Method> method = Arrays.stream(controller.getDeclaredMethods())
            .filter(m -> {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                return mapping != null
                    && Arrays.asList(mapping.method()).contains(RequestMethod.GET)
                    && Arrays.asList(mapping.path()).contains(path);
            })
            .findFirst();

        assertThat(method).as("GET %s on %s", path, controller.getSimpleName()).isPresent();
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(method.orElseThrow(), PreAuthorize.class);
        assertThat(pre).as("GET %s must carry @PreAuthorize", path).isNotNull();
        return pre.value();
    }

    /**
     * The roles the GET handler's guard admits, without the {@code ROLE_} prefix.
     * A negated check ({@code !hasRole('NURSE')}, {@code not hasAnyRole(...)})
     * admits nobody, so its roles are left out; a substring match counted them.
     */
    private static Set<String> admittedRoles(Class<?> controller, String path) {
        String guard = guardFor(controller, path);
        Set<String> roles = new LinkedHashSet<>();
        Matcher check = ROLE_CHECK.matcher(guard);
        while (check.find()) {
            if (check.group(1) != null) {
                continue;
            }
            Matcher role = QUOTED.matcher(check.group(2));
            while (role.find()) {
                String name = role.group(1).trim();
                roles.add(name.startsWith("ROLE_") ? name.substring("ROLE_".length()) : name);
            }
        }
        return roles;
    }

    @Test
    @DisplayName("a nurse can read the consultation lists whose count they already see")
    void nurseReadsConsultationLists() {
        // /stats has always admitted NURSE. Showing a clinician the number of
        // overdue consults and refusing the list is the drift being closed.
        assertThat(admittedRoles(ConsultationController.class, "/stats")).contains("NURSE");

        // ONLY /overdue. GET /consultations already admitted ROLE_NURSE, and the
        // portal's all/pending/active/completed tabs filter that one response
        // client-side — so /overdue was the only list a nurse could not reach.
        // The two /hospital/{id} reads have no portal caller and take the path
        // hospital as a trusted claim, so they stay closed.
        assertThat(admittedRoles(ConsultationController.class, "/overdue"))
            .as("ward nurses chase overdue consults and prep the patient")
            .contains("NURSE");
        assertThat(admittedRoles(ConsultationController.class, "/hospital/{hospitalId}"))
            .as("no portal caller, and the path hospital is an unvalidated claim")
            .doesNotContain("NURSE");
    }

    @Test
    @DisplayName("a nurse does NOT get /consultations/mine — they are never the consultant")
    void nurseDoesNotGetMine() {
        // Not an oversight, and not to be "fixed" by the next person widening
        // this controller: "mine" means consultations assigned to the caller AS
        // CONSULTANT, so for a nurse it can only ever be empty. The portal
        // hides the tab rather than rendering one that shows nothing.
        assertThat(admittedRoles(ConsultationController.class, "/mine"))
            .doesNotContain("NURSE");
    }

    @Test
    @DisplayName("a nurse can read imaging report history, where the addenda are")
    void nurseReadsImagingHistory() {
        Set<String> latest = admittedRoles(ImagingResultController.class, "/order/{orderId}");
        Set<String> history = admittedRoles(ImagingResultController.class, "/order/{orderId}/all");

        // The two must agree about nurses. Admitting them to the latest report
        // — which may itself be PRELIMINARY — while withholding the history
        // never implemented "no unconfirmed reads for non-physicians"; it only
        // hid the corrections.
        assertThat(latest).contains("NURSE");
        assertThat(history)
            .as("addenda live in the version history")
            .contains("NURSE");
    }

    // -- the lookup itself --------------------------------------------------

    /** Spellings Spring treats as one mapping, and guards a substring match misread. */
    @SuppressWarnings("unused")
    static final class Fixture {
        @GetMapping(path = "/by-path")
        @PreAuthorize("hasAnyRole('DOCTOR','NURSE')")
        void byPath() { }

        @GetMapping(value = "/by-value")
        @PreAuthorize("hasAuthority('ROLE_NURSE')")
        void byValue() { }

        @RequestMapping(path = "/by-request-mapping", method = RequestMethod.GET)
        @PreAuthorize("hasRole('NURSE')")
        void byRequestMapping() { }

        @GetMapping("/negated")
        @PreAuthorize("hasRole('DOCTOR') and !hasRole('NURSE')")
        void negated() { }

        @GetMapping("/not-negated")
        @PreAuthorize("hasAnyRole('DOCTOR') and not hasAnyAuthority('ROLE_NURSE', 'ROLE_MIDWIFE')")
        void notNegated() { }
    }

    @Test
    @DisplayName("a mapping is found however its path is spelled")
    void findsEverySpellingOfAMapping() {
        assertThat(admittedRoles(Fixture.class, "/by-path")).containsExactly("DOCTOR", "NURSE");
        assertThat(admittedRoles(Fixture.class, "/by-value")).containsExactly("NURSE");
        assertThat(admittedRoles(Fixture.class, "/by-request-mapping")).containsExactly("NURSE");
    }

    @Test
    @DisplayName("a negated role check does not count as admitting that role")
    void negatedRoleIsNotAdmitted() {
        assertThat(admittedRoles(Fixture.class, "/negated")).containsExactly("DOCTOR");
        assertThat(admittedRoles(Fixture.class, "/not-negated")).containsExactly("DOCTOR");
    }
}
