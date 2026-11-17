package com.example.hms.security;

import com.example.hms.config.SecurityConstants;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
 * QA D1012 (class guard, server half): the portal's table of the requests the
 * shell sends on every page load ({@code shell/shell-startup-endpoints.ts})
 * says which roles the server admits for each. The portal spec boots the shell
 * for every role and refuses a request the table does not admit for it; this
 * test holds the table to the controllers, so it cannot claim "any signed-in
 * role" for an endpoint whose {@code @PreAuthorize} names a list, nor a list
 * that differs from the annotation's.
 */
class ShellStartupEndpointRolesTest {

    private static final Path TABLE = Path.of("..", "hospital-portal", "src", "app", "shell",
            "shell-startup-endpoints.ts");
    private static final Pattern ENTRY = Pattern.compile(
            "'(GET|POST|PUT|PATCH|DELETE) (/[^']*)':\\s*(ANY_AUTHENTICATED|\\[([^\\]]*)])");
    private static final Pattern QUOTED_ROLE = Pattern.compile("'(ROLE_[A-Z_]+)'");

    @Test
    void everyShellStartupEndpointAdmitsExactlyTheRolesTheTableNames() throws Exception {
        String table = Files.readString(TABLE, StandardCharsets.UTF_8);
        int start = table.indexOf("SHELL_STARTUP_ENDPOINTS");
        int end = table.indexOf("};", start);
        Matcher entry = ENTRY.matcher(table.substring(start, end));
        List<PharmacyPageEndpointRolesTest.Handler> handlers = PharmacyPageEndpointRolesTest.handlers();
        List<String> wrong = new ArrayList<>();
        int read = 0;
        while (entry.find()) {
            read++;
            String verb = entry.group(1);
            String path = entry.group(2);
            PharmacyPageEndpointRolesTest.Handler handler = PharmacyPageEndpointRolesTest.resolve(
                    new PharmacyPageEndpointRolesTest.Endpoint(verb, path), handlers);
            assertThat(handler).as("no controller serves %s %s", verb, path).isNotNull();
            Set<String> server = PharmacyPageEndpointRolesTest.rolesOf(handler.method());
            Set<String> listed = entry.group(4) == null ? null : roles(entry.group(4));
            if (server == null ? listed != null : !server.equals(listed)) {
                wrong.add(verb + " " + path + ": table says " + (listed == null ? "ANY_AUTHENTICATED" : listed)
                        + ", " + handler.method().getDeclaringClass().getSimpleName() + "#"
                        + handler.method().getName() + " admits "
                        + (server == null ? "every authenticated caller" : server));
            }
        }
        assertThat(read).as("entries read from %s", TABLE).isGreaterThanOrEqualTo(6);
        assertThat(wrong).as("shell startup table out of step with the controllers (QA D1012)").isEmpty();
    }

    @Test
    void theShellGuardBootsEveryRoleTheServerDeclares() throws Exception {
        String table = Files.readString(TABLE, StandardCharsets.UTF_8);
        int start = table.indexOf("SHELL_GUARD_ROLES");
        Set<String> booted = roles(table.substring(start, table.indexOf("];", start)));
        Set<String> declared = new TreeSet<>();
        for (Field field : SecurityConstants.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class
                    && field.getName().startsWith("ROLE_") && !field.getName().equals("ROLE_PREFIX")) {
                declared.add((String) field.get(null));
            }
        }
        assertThat(declared).hasSizeGreaterThan(20);
        assertThat(booted).as("SHELL_GUARD_ROLES must name every role in SecurityConstants").containsAll(declared);
    }

    @Test
    void theGuardSeesTheEmpiProbeAsRoleLimited() throws Exception {
        Map<String, Set<String>> limited = new LinkedHashMap<>();
        String table = Files.readString(TABLE, StandardCharsets.UTF_8);
        Matcher entry = ENTRY.matcher(table);
        while (entry.find()) {
            if (entry.group(4) != null) {
                limited.put(entry.group(2), roles(entry.group(4)));
            }
        }
        assertThat(limited.get("/empi/candidates/status")).doesNotContain("ROLE_PATIENT", "ROLE_PHARMACIST")
                .contains("ROLE_RECEPTIONIST");
    }

    private static Set<String> roles(String list) {
        Set<String> out = new TreeSet<>();
        Matcher role = QUOTED_ROLE.matcher(list);
        while (role.find()) {
            out.add(role.group(1));
        }
        return out;
    }
}
