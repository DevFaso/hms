package com.example.hms.config;

import com.example.hms.BaseIT;
import com.example.hms.security.RoleExpansion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pairs every {@code @PreAuthorize} with the {@code SecurityConfig} matcher
 * that covers its path, through the REAL filter chain's authorization manager.
 *
 * <p>Neither layer is wrong on its own, and nobody compared them: the
 * record-sharing opt-out shipped admitting ROLE_PATIENT at the annotation
 * while the {@code /patients/**} matchers refused a patient on GET and DELETE,
 * so the feature was half-reachable for months and every test agreed it
 * worked ({@code @WebMvcTest} slices never run the chain; the full-context
 * patient ITs set {@code addFilters = false}). Here, for each role an
 * annotation admits, a caller holding exactly that role (widened by
 * {@link RoleExpansion}, as both auth paths do) is put through the matcher
 * for that verb and path. A matcher that refuses it is NARROWER than the
 * annotation: the endpoint is unreachable for a role its own guard names.
 *
 * <p>Today's such pairs are frozen below with a reason; a new one fails the
 * test, and so does an entry that no longer occurs (the list only shrinks).
 */
public class PreAuthorizeMatcherPairingTest extends BaseIT {

    private static final Pattern ROLE_CALL = Pattern.compile("has(Any)?Role\\(([^)]*)\\)");
    private static final Pattern AUTHORITY_CALL = Pattern.compile("has(Any)?Authority\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([A-Za-z_]+)'");
    private static final Pattern PATH_VARIABLE = Pattern.compile("\\{[^}]*}");
    private static final String SAMPLE_ID = "00000000-0000-0000-0000-000000000001";

    /**
     * "VERB /pattern ROLE" → why the matcher refusing the role is accepted for
     * now. Frozen as found when this ratchet landed (2026-09-26); each is an
     * endpoint unreachable at the edge for a role its own annotation admits.
     */
    private static final Map<String, String> ACCEPTED = acceptedNarrowerMatchers();

    @Autowired
    private FilterChainProxy filterChainProxy;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    @Test
    @DisplayName("no SecurityConfig matcher is narrower than the @PreAuthorize of the handler it covers")
    void matchersAreNeverNarrowerThanTheirAnnotations() {
        Set<String> narrower = new TreeSet<>();
        int pairsChecked = 0;
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping.getHandlerMethods().entrySet()) {
            HandlerMethod handler = entry.getValue();
            PreAuthorize guard = AnnotatedElementUtils.findMergedAnnotation(handler.getMethod(), PreAuthorize.class);
            if (guard == null) {
                guard = AnnotatedElementUtils.findMergedAnnotation(handler.getBeanType(), PreAuthorize.class);
            }
            if (guard == null) {
                continue;
            }
            Set<String> roles = rolesAdmittedBy(guard.value());
            for (String pattern : entry.getKey().getPatternValues()) {
                for (String verb : verbs(entry.getKey())) {
                    for (String role : roles) {
                        pairsChecked++;
                        if (!matcherAdmits(verb, concretePath(pattern), role)) {
                            narrower.add(verb + " " + pattern + " " + role);
                        }
                    }
                }
            }
        }
        assertThat(pairsChecked).as("the scan reached the handlers").isGreaterThan(1000);

        Set<String> unexpected = new TreeSet<>(narrower);
        unexpected.removeAll(ACCEPTED.keySet());
        Set<String> stale = new TreeSet<>(ACCEPTED.keySet());
        stale.removeAll(narrower);
        assertThat(unexpected)
            .as("matchers narrower than the annotation they cover: widen the matcher, narrow the annotation, "
                + "or (a decision) add the pair to ACCEPTED with its reason")
            .isEmpty();
        assertThat(stale).as("ACCEPTED pairs that no longer occur: delete them, the list only shrinks").isEmpty();
    }

    @Test
    @DisplayName("QA D1224: no accepted narrower pair under /patients - every chart sub-resource reaches its annotation")
    void noPatientSubResourceIsShadowedByABlanketMatcher() {
        assertThat(ACCEPTED.keySet())
            .as("a /patients route a blanket matcher hides from a role its annotation admits is a defect (QA D1224),"
                + " never an accepted pair: carve the route out of the blanket in SecurityConfig")
            .noneMatch(pair -> pair.split(" ")[1].startsWith("/patients"));
    }

    @Test
    @DisplayName("QA D1224: clinicians pass the edge on allergy and diagnosis writes; deleting a patient stays admin-only")
    void chartWritesReachTheirAnnotationAndWholePatientDeleteStaysAdminOnly() {
        String patient = "/patients/" + SAMPLE_ID;
        for (String role : List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_PHARMACIST")) {
            assertThat(matcherAdmits("DELETE", patient + "/allergies/" + SAMPLE_ID, role)).as(role).isTrue();
            assertThat(matcherAdmits("PUT", patient + "/allergies/" + SAMPLE_ID, role)).as(role).isTrue();
        }
        for (String role : List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE")) {
            assertThat(matcherAdmits("DELETE", patient + "/diagnoses/" + SAMPLE_ID, role)).as(role).isTrue();
        }
        for (String role : List.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE", "ROLE_RECEPTIONIST",
                "ROLE_PHARMACIST", "ROLE_LAB_SCIENTIST")) {
            assertThat(matcherAdmits("DELETE", patient, role)).as("whole-patient delete, " + role).isFalse();
        }
        assertThat(matcherAdmits("DELETE", patient, "ROLE_HOSPITAL_ADMIN")).isTrue();
        assertThat(matcherAdmits("DELETE", patient, "ROLE_SUPER_ADMIN")).isTrue();
        // The carve-out is the chart sub-resources only: a receptionist still cannot delete clinical entries.
        assertThat(matcherAdmits("DELETE", patient + "/allergies/" + SAMPLE_ID, "ROLE_RECEPTIONIST")).isFalse();
        assertThat(matcherAdmits("DELETE", patient + "/diagnoses/" + SAMPLE_ID, "ROLE_HOSPITAL_ADMIN")).isFalse();
    }

    @Test
    @DisplayName("the pairing sees a refusal: deleting a hospital refuses a doctor at the matcher, not a super-admin")
    void pairingDetectsARefusal() {
        String hospital = "/hospitals/" + SAMPLE_ID;
        assertThat(matcherAdmits("DELETE", hospital, "ROLE_DOCTOR")).isFalse();
        assertThat(matcherAdmits("DELETE", hospital, "ROLE_SUPER_ADMIN")).isTrue();
    }

    @Test
    @DisplayName("QA D846: a lab director passes the edge on PUT /staff/{id}/lab-role, and only there under /staff")
    void labDirectorReachesTheLabRoleEditOnly() {
        String staff = "/staff/" + SAMPLE_ID;
        assertThat(matcherAdmits("PUT", staff + "/lab-role", "ROLE_LAB_DIRECTOR")).isTrue();
        assertThat(matcherAdmits("PUT", staff + "/lab-role", "ROLE_HOSPITAL_ADMIN")).isTrue();
        assertThat(matcherAdmits("PUT", staff + "/lab-role", "ROLE_LAB_TECHNICIAN")).isFalse();
        // The narrow matcher widens nothing else: editing the staff row stays administrators-only.
        assertThat(matcherAdmits("PUT", staff, "ROLE_LAB_DIRECTOR")).isFalse();
        assertThat(matcherAdmits("DELETE", staff, "ROLE_LAB_DIRECTOR")).isFalse();
    }

    /** Every {@code ROLE_*} the expression names, as a role call or an authority call. */
    public static Set<String> rolesAdmittedBy(String expression) {
        Set<String> roles = new LinkedHashSet<>();
        Matcher role = ROLE_CALL.matcher(expression);
        while (role.find()) {
            Matcher token = QUOTED.matcher(role.group(2));
            while (token.find()) {
                String name = token.group(1);
                roles.add(name.startsWith("ROLE_") ? name : "ROLE_" + name);
            }
        }
        Matcher authority = AUTHORITY_CALL.matcher(expression);
        while (authority.find()) {
            Matcher token = QUOTED.matcher(authority.group(2));
            while (token.find()) {
                if (token.group(1).startsWith("ROLE_")) {
                    roles.add(token.group(1));
                }
            }
        }
        return roles;
    }

    private static List<String> verbs(RequestMappingInfo info) {
        Set<RequestMethod> methods = info.getMethodsCondition().getMethods();
        if (methods.isEmpty()) {
            return List.of("GET");
        }
        List<String> verbs = new ArrayList<>();
        methods.forEach(method -> verbs.add(method.name()));
        return verbs;
    }

    private static String concretePath(String pattern) {
        return PATH_VARIABLE.matcher(pattern).replaceAll(SAMPLE_ID);
    }

    private boolean matcherAdmits(String verb, String path, String role) {
        MockHttpServletRequest request = new MockHttpServletRequest(verb, path);
        request.setServletPath(path);
        Authentication caller = UsernamePasswordAuthenticationToken.authenticated("pairing", null,
            RoleExpansion.expand(List.of(role)).stream().map(SimpleGrantedAuthority::new).toList());
        for (SecurityFilterChain chain : filterChainProxy.getFilterChains()) {
            if (!chain.matches(request)) {
                continue;
            }
            for (Filter filter : chain.getFilters()) {
                if (filter instanceof AuthorizationFilter authorization) {
                    AuthorizationManager<HttpServletRequest> manager = authorization.getAuthorizationManager();
                    AuthorizationResult result = manager.authorize(() -> caller, request);
                    return result == null || result.isGranted();
                }
            }
            return true;
        }
        return true;
    }

    private static Map<String, String> acceptedNarrowerMatchers() {
        String labPr = "Found by this ratchet; the lab files are owned by another PR of this batch, recorded on the PR";
        Map<String, String> accepted = new TreeMap<>();
        accepted.put("GET /lab-qc-events/summary ROLE_ADMIN", labPr);
        accepted.put("GET /lab-specimens/{specimenId}/label.pdf ROLE_HOSPITAL_ADMIN", labPr);
        return accepted;
    }
}
