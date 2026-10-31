package com.example.hms.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D965 (class guard): "Déclencher les alertes" was offered to the
 * pharmacist on the inventory page while its endpoint admits only the store
 * manager and the admins, so the click answered 403 and threw the user onto
 * the 403 page. The same mismatch sat behind four more buttons and five page
 * loads (the inventory clerk's pharmacy picker and catalogue, the pharmacy
 * verifier's witness list...).
 *
 * <p>For every pharmacy page in {@code app.routes.ts}, this test reads the
 * component's {@code PharmacyService} calls, resolves each to the controller
 * method that serves it, and fails when the endpoint refuses a role that can
 * open the page - unless the call is gated in the page by a role check named
 * in {@link #GATED}, whose evidence must be present in the page source.
 */
class PharmacyPageEndpointRolesTest {

    private static final Path PORTAL = Path.of("..", "hospital-portal", "src", "app");
    private static final Path ROUTES = PORTAL.resolve("app.routes.ts");
    private static final Path SERVICE = PORTAL.resolve(Path.of("services", "pharmacy.service.ts"));

    /** Pharmacy pages owned elsewhere: mtm-review is guarded by its own route data (requiresHospitalScope). */
    private static final Set<String> SKIPPED_PAGES = Set.of("mtm-review");

    /**
     * Calls a page offers only to the roles its endpoint admits:
     * {@code page#serviceMethod} to the role check that gates it in the page.
     */
    static final Map<String, String> GATED = Map.ofEntries(
            Map.entry("inventory-dashboard#triggerReorderAlerts", "actionRoles('inventoryReorderTrigger')"),
            Map.entry("medication-catalog#createMedication", "canManage()"),
            Map.entry("medication-catalog#updateMedication", "canManage()"),
            Map.entry("medication-catalog#deleteMedication", "canManage()"),
            Map.entry("pharmacy-registry#createPharmacy", "canManage()"),
            Map.entry("pharmacy-registry#updatePharmacy", "canManage()"),
            Map.entry("pharmacy-registry#deletePharmacy", "canManage()"));

    private static final Pattern PAGE_IMPORT = Pattern.compile("import\\('\\./pharmacy/([\\w-]+)'\\)");
    private static final Pattern ROLES = Pattern.compile("roles:\\s*\\[([^\\]]*)]");
    private static final Pattern QUOTED_ROLE = Pattern.compile("'(ROLE_[A-Z_]+)'");
    private static final Pattern SERVICE_FIELD = Pattern.compile("\\b(\\w++)\\s*+=\\s*+inject\\(PharmacyService\\)");
    private static final Pattern METHOD_START = Pattern.compile("(?m)^  (\\w+)\\(");
    private static final Pattern HTTP_CALL = Pattern.compile(
            "this\\.http\\.(get|post|put|patch|delete)(?:<[^(]*?>)?\\(\\s*(['`])(.*?)\\2", Pattern.DOTALL);

    record Endpoint(String verb, String path) { }

    record Handler(String verb, String[] segments, Method method) { }

    @Test
    void noPharmacyPageCallsAnEndpointThatRefusesARoleThatCanOpenIt() throws Exception {
        Map<String, Set<String>> pages = pageRoles();
        assertThat(pages).as("pharmacy pages read from %s", ROUTES).hasSizeGreaterThanOrEqualTo(8);
        Map<String, Endpoint> service = serviceEndpoints();
        assertThat(service).as("PharmacyService methods parsed").hasSizeGreaterThan(40);
        List<Handler> handlers = handlers();

        List<String> refused = new ArrayList<>();
        for (Map.Entry<String, Set<String>> page : pages.entrySet()) {
            String name = page.getKey();
            Path ts = PORTAL.resolve(Path.of("pharmacy", name + ".ts"));
            String source = Files.readString(ts, StandardCharsets.UTF_8);
            Path html = PORTAL.resolve(Path.of("pharmacy", name + ".html"));
            String template = Files.exists(html) ? Files.readString(html, StandardCharsets.UTF_8) : "";
            Matcher field = SERVICE_FIELD.matcher(source);
            if (!field.find()) {
                continue;
            }
            Matcher call = Pattern.compile("this\\." + field.group(1) + "\\.(\\w+)\\(").matcher(source);
            Set<String> called = new TreeSet<>();
            while (call.find()) {
                called.add(call.group(1));
            }
            for (String method : called) {
                Endpoint endpoint = service.get(method);
                assertThat(endpoint).as("%s calls PharmacyService#%s, which has no HTTP call parsed", name, method)
                        .isNotNull();
                Handler handler = resolve(endpoint, handlers);
                assertThat(handler).as("no controller serves %s %s (%s#%s)", endpoint.verb(), endpoint.path(),
                        name, method).isNotNull();
                Set<String> allowed = rolesOf(handler.method());
                if (allowed == null) {
                    continue; // isAuthenticated() / permitAll(): every page role passes
                }
                Set<String> missing = new TreeSet<>(page.getValue());
                missing.removeAll(allowed);
                if (missing.isEmpty()) {
                    continue;
                }
                String gate = GATED.get(name + "#" + method);
                if (gate != null) {
                    assertThat(source + template).as("%s#%s is listed as gated by %s", name, method, gate)
                            .contains(gate);
                    continue;
                }
                refused.add(name + " -> " + method + " (" + endpoint.verb() + " " + endpoint.path() + ", "
                        + handler.method().getDeclaringClass().getSimpleName() + "#" + handler.method().getName()
                        + ") refuses " + missing);
            }
        }
        assertThat(refused).as("pharmacy page calls refused for a role that can open the page (QA D965)").isEmpty();
    }

    @Test
    void theGuardSeesTheTriggerButtonOfTheInventoryPage() throws Exception {
        Map<String, Endpoint> service = serviceEndpoints();
        Handler handler = resolve(service.get("triggerReorderAlerts"), handlers());
        assertThat(handler.method().getName()).isEqualTo("triggerReorderAlerts");
        assertThat(rolesOf(handler.method())).doesNotContain("ROLE_PHARMACIST");
        assertThat(pageRoles().get("inventory-dashboard")).contains("ROLE_PHARMACIST");
    }

    /** Each pharmacy page's roles, from its route's {@code data.roles}. */
    private static Map<String, Set<String>> pageRoles() throws IOException {
        String routes = Files.readString(ROUTES, StandardCharsets.UTF_8);
        Map<String, Set<String>> pages = new LinkedHashMap<>();
        Matcher page = PAGE_IMPORT.matcher(routes);
        while (page.find()) {
            String name = page.group(1);
            if (SKIPPED_PAGES.contains(name)) {
                continue;
            }
            int pathAt = routes.lastIndexOf("path:", page.start());
            Matcher roles = ROLES.matcher(routes.substring(pathAt, page.start()));
            if (!roles.find()) {
                continue;
            }
            Set<String> set = pages.computeIfAbsent(name, k -> new TreeSet<>());
            Matcher role = QUOTED_ROLE.matcher(roles.group(1));
            while (role.find()) {
                set.add(role.group(1));
            }
        }
        return pages;
    }

    /** {@code PharmacyService} method name to the HTTP call it makes. */
    private static Map<String, Endpoint> serviceEndpoints() throws IOException {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8);
        int classStart = source.indexOf("export class PharmacyService");
        String body = source.substring(classStart);
        Map<String, Endpoint> out = new LinkedHashMap<>();
        Matcher start = METHOD_START.matcher(body);
        List<int[]> spans = new ArrayList<>();
        List<String> names = new ArrayList<>();
        while (start.find()) {
            spans.add(new int[] {start.start()});
            names.add(start.group(1));
        }
        for (int i = 0; i < spans.size(); i++) {
            int from = spans.get(i)[0];
            int to = i + 1 < spans.size() ? spans.get(i + 1)[0] : body.length();
            Matcher http = HTTP_CALL.matcher(body.substring(from, to));
            if (http.find()) {
                String path = http.group(3).replaceAll("\\$\\{[^}]*}", "{}");
                int query = path.indexOf('?');
                out.put(names.get(i), new Endpoint(http.group(1).toUpperCase(java.util.Locale.ROOT),
                        query >= 0 ? path.substring(0, query) : path));
            }
        }
        return out;
    }

    private static List<Handler> handlers() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Handler> out = new ArrayList<>();
        for (BeanDefinition bean : scanner.findCandidateComponents("com.example.hms.controller")) {
            Class<?> type = Class.forName(bean.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(type, RequestMapping.class);
            String[] prefixes = base != null && base.path().length > 0 ? base.path() : new String[] {""};
            for (Method method : type.getDeclaredMethods()) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                String[] paths = mapping.path().length > 0 ? mapping.path() : new String[] {""};
                RequestMethod[] verbs = mapping.method();
                for (String prefix : prefixes) {
                    for (String path : paths) {
                        String[] segments = segments(prefix + "/" + path);
                        for (RequestMethod verb : verbs) {
                            out.add(new Handler(verb.name(), segments, method));
                        }
                    }
                }
            }
        }
        return out;
    }

    /** The handler with the most literal segments matching, like Spring's own pattern ranking. */
    private static Handler resolve(Endpoint endpoint, List<Handler> handlers) {
        String[] wanted = segments(endpoint.path());
        Handler best = null;
        int bestScore = -1;
        for (Handler handler : handlers) {
            if (!handler.verb().equals(endpoint.verb()) || handler.segments().length != wanted.length) {
                continue;
            }
            int score = 0;
            boolean matches = true;
            for (int i = 0; i < wanted.length && matches; i++) {
                String have = handler.segments()[i];
                boolean haveVariable = have.startsWith("{");
                boolean wantedVariable = wanted[i].equals("{}");
                if (!haveVariable && !wantedVariable) {
                    if (have.equals(wanted[i])) {
                        score += 2;
                    } else {
                        matches = false;
                    }
                } else if (haveVariable && wantedVariable) {
                    score++; // an id where the controller takes one, not a literal sibling
                }
            }
            if (matches && score > bestScore) {
                best = handler;
                bestScore = score;
            }
        }
        return best;
    }

    private static String[] segments(String path) {
        return java.util.Arrays.stream(path.split("/")).filter(s -> !s.isBlank()).toArray(String[]::new);
    }

    /** The roles a handler admits; null when it admits every authenticated caller. */
    private static Set<String> rolesOf(Method method) {
        PreAuthorize pre = AnnotatedElementUtils.findMergedAnnotation(method, PreAuthorize.class);
        if (pre == null) {
            pre = AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), PreAuthorize.class);
        }
        if (pre == null) {
            return null;
        }
        String expression = pre.value().trim();
        if (expression.equals("isAuthenticated()") || expression.equals("permitAll()")) {
            return null;
        }
        assertThat(expression)
                .as("%s#%s: a role list the guard can read", method.getDeclaringClass().getSimpleName(), method.getName())
                .matches("^(hasAnyAuthority|hasAuthority|hasAnyRole|hasRole)\\(.*\\)$");
        return ActionPermissionsMirrorTest.rolesOf(expression);
    }
}
