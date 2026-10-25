package com.example.hms.security;

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
 * QA D427/D527: the portal derives its write buttons from one map,
 * {@code hospital-portal/src/app/core/action-permissions.ts}. Each entry names
 * the controller method it mirrors; this test reads the map and fails when an
 * entry's roles are not exactly the roles of that method's {@code @PreAuthorize},
 * so a button can no longer be offered to a role the API refuses (round 7 hid
 * the encounter/referral buttons page by page and round 8 found the lab,
 * admission, imaging and allergy ones still offered).
 */
class ActionPermissionsMirrorTest {

    private static final Path MAP = Path.of("..", "hospital-portal", "src", "app", "core", "action-permissions.ts");
    private static final Pattern ACTION_LINE = Pattern.compile("(\\w+): \\{");
    private static final Pattern ENDPOINT_LINE = Pattern.compile("endpoint: '(\\w+)#(\\w+)',");
    private static final Pattern QUOTED = Pattern.compile("'([A-Z_]+)'");
    private static final Pattern SIMPLE_EXPRESSION = Pattern.compile(
        "^(hasAnyAuthority|hasAuthority|hasAnyRole|hasRole)\\(\\s*'[A-Z_]+'(\\s*,\\s*'[A-Z_]+')*\\s*\\)$");

    record Entry(String action, String controller, String method, Set<String> roles) { }

    @Test
    void everyPortalActionMirrorsItsEndpointRoles() throws Exception {
        List<Entry> entries = readMap();
        assertThat(entries).as("entries parsed from %s", MAP).hasSizeGreaterThanOrEqualTo(15);
        List<String> drift = new ArrayList<>();
        for (Entry e : entries) {
            String expression = preAuthorizeOf(e.controller(), e.method());
            Set<String> backend = rolesOf(expression);
            if (!backend.equals(e.roles())) {
                drift.add(e.action() + " (" + e.controller() + "#" + e.method() + "): portal " + e.roles()
                    + " but @PreAuthorize admits " + backend);
            }
        }
        assertThat(drift).as("portal action map out of step with the API").isEmpty();
    }

    @Test
    void theParserReadsTheRolesOfASimpleExpression() {
        assertThat(rolesOf("hasAnyRole('ADMIN', 'SUPER_ADMIN')")).containsExactly("ROLE_ADMIN", "ROLE_SUPER_ADMIN");
        assertThat(rolesOf("hasAnyAuthority('ROLE_DOCTOR')")).containsExactly("ROLE_DOCTOR");
    }

    private static List<Entry> readMap() throws IOException {
        assertThat(Files.exists(MAP)).as("portal action map at %s", MAP.toAbsolutePath()).isTrue();
        String source = Files.readString(MAP, StandardCharsets.UTF_8);
        List<Entry> out = new ArrayList<>();
        String action = null;
        String[] endpoint = null;
        // A long list is wrapped one role per line by the formatter, so the
        // roles run from `roles: [` to the line that closes the bracket.
        Set<String> roles = null;
        for (String raw : source.split("\n")) {
            String line = raw.strip();
            Matcher a = ACTION_LINE.matcher(line);
            Matcher e = ENDPOINT_LINE.matcher(line);
            if (roles == null && a.matches()) {
                action = a.group(1);
                endpoint = null;
            } else if (roles == null && action != null && e.matches()) {
                endpoint = new String[] {e.group(1), e.group(2)};
            } else if (endpoint != null && (roles != null || line.startsWith("roles:"))) {
                if (roles == null) {
                    roles = new TreeSet<>();
                }
                Matcher q = QUOTED.matcher(line);
                while (q.find()) {
                    roles.add(q.group(1));
                }
                if (line.contains("]")) {
                    out.add(new Entry(action, endpoint[0], endpoint[1], roles));
                    action = null;
                    endpoint = null;
                    roles = null;
                }
            }
        }
        return out;
    }

    private static String preAuthorizeOf(String controller, String method) throws ClassNotFoundException {
        Class<?> type = Class.forName("com.example.hms.controller." + controller);
        List<Method> matches = Arrays.stream(type.getDeclaredMethods())
            .filter(candidate -> candidate.getName().equals(method))
            .filter(candidate -> candidate.isAnnotationPresent(PreAuthorize.class))
            .toList();
        assertThat(matches).as("exactly one @PreAuthorize method %s#%s", controller, method).hasSize(1);
        String expression = matches.get(0).getAnnotation(PreAuthorize.class).value().trim();
        assertThat(expression).as("%s#%s must be a plain role list to be mirrored", controller, method)
            .matches(SIMPLE_EXPRESSION);
        return expression;
    }

    static Set<String> rolesOf(String expression) {
        boolean roleForm = expression.startsWith("hasRole") || expression.startsWith("hasAnyRole");
        Set<String> roles = new TreeSet<>();
        Matcher q = QUOTED.matcher(expression);
        while (q.find()) {
            String role = q.group(1);
            roles.add(roleForm && !role.startsWith("ROLE_") ? "ROLE_" + role : role);
        }
        return roles;
    }
}
