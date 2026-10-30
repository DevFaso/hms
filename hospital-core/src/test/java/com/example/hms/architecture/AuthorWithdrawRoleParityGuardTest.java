package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * QA D1026 guard: a record its author cannot withdraw.
 *
 * <p>A doctor wrote an OB/GYN referral ({@code POST /referrals/obgyn} admits
 * DOCTOR) and could not cancel it: {@code POST /referrals/obgyn/{id}/cancel}
 * admitted only MIDWIFE, so the author got 403 and the service's own author
 * rule never ran. The procedure-order cancel had the same shape for a nurse
 * (its update admitted her, its cancel did not).
 *
 * <p>For every controller, every write ending {@code /{id}/cancel} whose
 * collection has a {@code POST} create in the same controller must admit
 * every role the create admits: whoever may write it may be its author, and
 * the service decides whether this caller is. Fewer roles on the cancel is
 * allowed only when listed below with a reason a reviewer can check.
 */
@DisplayName("Every role that may create a record may reach its cancel endpoint")
class AuthorWithdrawRoleParityGuardTest {

    private static final Path CONTROLLERS = Paths.get("src/main/java/com/example/hms/controller");

    private static final Pattern MAPPING = Pattern.compile(
        "@(Post|Put|Patch)Mapping\\b(\\s*\\(([^)]*)\\))?");
    private static final Pattern CLASS_MAPPING = Pattern.compile(
        "@RequestMapping\\(\\s*(?:(?:value|path)\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"");
    private static final Pattern STRING_CONSTANT = Pattern.compile(
        "static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"([^\"]*)\"\\s*;");
    private static final Pattern ROLE_LIST = Pattern.compile("has(?:Any)?(Role|Authority)\\(([^)]*)\\)");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");
    private static final Pattern CANCEL = Pattern.compile("^(.*)/\\{\\w+}/cancel$");

    /** Known gaps, per {@code "Controller cancelPath missing=ROLE_X"}. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow();

    @Test
    @DisplayName("cancel admits every role of the create")
    void cancelAdmitsEveryCreatorRole() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path file : files.filter(p -> p.toString().endsWith("Controller.java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                for (String offence : offenders(file.getFileName().toString().replace(".java", ""), source)) {
                    actual.merge(offence, 1, Integer::sum);
                }
            }
        }
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a cancel endpoint refuses a role that may create the record, so its author gets 403 (QA D1026)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1026 shape")
    void catchesTheOldShape() {
        String before = """
            @RequestMapping("/referrals/obgyn")
            class X {
                @PostMapping
                @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')")
                public void create() {}
                @PostMapping("/{id}/cancel")
                @PreAuthorize("hasAnyAuthority('ROLE_MIDWIFE','ROLE_SUPER_ADMIN')")
                public void cancel() {}
            }
            """;
        assertThat(offenders("X", before)).containsExactly("X /referrals/obgyn/{id}/cancel missing=ROLE_DOCTOR");
        String constants = """
            @RequestMapping("/procedure-orders")
            class Y {
                private static final String WRITERS = "hasAnyRole('SUPER_ADMIN','DOCTOR','NURSE')";
                @PostMapping(consumes = "application/json")
                @PreAuthorize(WRITERS)
                public void create() {}
                @PostMapping("/{orderId}/cancel")
                @PreAuthorize("hasAnyRole('SUPER_ADMIN','DOCTOR')")
                public void cancel() {}
            }
            """;
        assertThat(offenders("Y", constants)).containsExactly("Y /procedure-orders/{orderId}/cancel missing=ROLE_NURSE");
        String fixed = before.replace("hasAnyAuthority('ROLE_MIDWIFE','ROLE_SUPER_ADMIN')",
            "hasAnyAuthority('ROLE_MIDWIFE','ROLE_DOCTOR','ROLE_SUPER_ADMIN')");
        assertThat(offenders("X", fixed)).isEmpty();
    }

    static List<String> offenders(String controller, String source) {
        Map<String, String> constants = new LinkedHashMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        Matcher cm = CLASS_MAPPING.matcher(source);
        String base = cm.find() ? cm.group(1) : "";
        Map<String, Set<String>> createRoles = new LinkedHashMap<>();
        Map<String, Set<String>> cancelRoles = new LinkedHashMap<>();
        Matcher m = MAPPING.matcher(source);
        while (m.find()) {
            String path = base + pathOf(m.group(3));
            Set<String> roles = rolesNear(source, m.start(), m.end(), constants);
            if (roles == null) {
                continue;
            }
            Matcher cancel = CANCEL.matcher(path);
            if (cancel.matches()) {
                cancelRoles.put(path, roles);
            } else if ("Post".equals(m.group(1))) {
                createRoles.putIfAbsent(path, roles);
            }
        }
        List<String> out = new ArrayList<>();
        cancelRoles.forEach((path, roles) -> {
            Matcher cancel = CANCEL.matcher(path);
            if (!cancel.matches()) {
                return;
            }
            Set<String> creators = createRoles.get(cancel.group(1));
            if (creators == null) {
                return;
            }
            Set<String> missing = new TreeSet<>(creators);
            missing.removeAll(roles);
            if (!missing.isEmpty()) {
                out.add(controller + " " + path + " missing=" + String.join(",", missing));
            }
        });
        return out;
    }

    private static String pathOf(String args) {
        if (args == null) {
            return "";
        }
        Matcher named = Pattern.compile("(?:value|path)\\s*=\\s*\\{?\\s*\"([^\"]*)\"").matcher(args);
        if (named.find()) {
            return named.group(1);
        }
        Matcher first = Pattern.compile("^\\s*\\{?\\s*\"([^\"]*)\"").matcher(args);
        return first.find() ? first.group(1) : "";
    }

    /** The roles of the {@code @PreAuthorize} next to the mapping (before or after it, same method). */
    private static Set<String> rolesNear(String source, int start, int end, Map<String, String> constants) {
        int methodStart = source.indexOf('(', source.indexOf("public", end));
        int windowStart = Math.max(source.lastIndexOf(';', start), source.lastIndexOf('}', start));
        String window = source.substring(Math.max(0, windowStart), methodStart < 0 ? source.length() : methodStart);
        Matcher p = Pattern.compile("@PreAuthorize\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|\\w+)\\s*\\)").matcher(window);
        if (!p.find()) {
            return null;
        }
        String expression = p.group(1);
        if (!expression.startsWith("\"")) {
            expression = constants.get(expression);
            if (expression == null) {
                return null;
            }
        }
        Set<String> roles = new TreeSet<>();
        Matcher list = ROLE_LIST.matcher(expression);
        while (list.find()) {
            Matcher q = QUOTED.matcher(list.group(2));
            while (q.find()) {
                String role = q.group(1);
                roles.add(role.startsWith("ROLE_") ? role : "ROLE_" + role);
            }
        }
        return roles;
    }
}
