package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session's role set is fixed when it is opened and never widened by a
 * later token of it, so the roles a token carries are the ones its sign-in
 * (and the MFA challenge of that sign-in) decided on.
 *
 * <p>Held by types and by this scan together:
 * <ul>
 *   <li>A sign-in path (a class that calls {@code generate(Access|Refresh)Token(})
 *       builds no {@code TokenUserDescriptor} itself; it mints from
 *       {@code SessionRoles.Granted#descriptor}, and a {@code Granted} set is made
 *       only by {@code SessionRoles.atSignIn} or {@code SessionRoles.stillHeld}.</li>
 *   <li>{@code SessionRoles.atSignIn(} appears only in the password login
 *       ({@code AuthController.authenticateUser}), the one place that has just
 *       authenticated the account.</li>
 *   <li>Outside that login, a minting method and the private methods of its
 *       class it calls read the account's roles ({@code .getRole()},
 *       {@code EffectiveRoles.codes(}, {@code loadUserByUsername(},
 *       {@code getAuthorities()}, {@code findAssignmentsForUser(},
 *       {@code getRolesFromToken(}) only as the "held" (second) argument of
 *       {@code SessionRoles.stillHeld(}, never as its "carried" (first) one.</li>
 *   <li>{@code JwtTokenProvider} mints the roles it is given
 *       ({@code descriptorRoles(}) and never looks any up.</li>
 * </ul>
 */
@DisplayName("A token-minting path mints only the session's own role set")
class SessionRoleScopeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String PROVIDER = "security/JwtTokenProvider.java";
    private static final String LOGIN_FILE = "controller/AuthController.java";
    private static final String LOGIN_METHOD = "public ResponseEntity<Object> authenticateUser(";

    private static final Pattern MINT = Pattern.compile("\\bgenerate(?:Access|Refresh)Token\\(");
    private static final Pattern CALL = Pattern.compile("\\b([a-z]\\w*)\\(");
    private static final Pattern ROLE_SOURCE = Pattern.compile(
        "\\.getRole\\(\\)|\\bEffectiveRoles\\.codes\\(|\\bloadUserByUsername\\(|\\bgetRolesFromToken\\("
            + "|\\bgetAuthorities\\(\\)|\\bfindAssignmentsForUser\\(");
    private static final Pattern NEW_DESCRIPTOR = Pattern.compile("\\bnew\\s+(?:[\\w.]+\\.)?TokenUserDescriptor\\(");
    private static final String STILL_HELD = "SessionRoles.stillHeld(";
    private static final String AT_SIGN_IN = "SessionRoles.atSignIn(";
    private static final String METHOD_END = "\n    }\n";

    static List<String> offenders(String file, String source) {
        String src = source.replace("\r\n", "\n");
        return file.equals(PROVIDER) ? providerOffenders(file, src) : signInPathOffenders(file, src);
    }

    /** JwtTokenProvider: every method that writes the roles claim takes the roles it is given. */
    private static List<String> providerOffenders(String file, String src) {
        List<String> out = new ArrayList<>();
        Matcher roles = Pattern.compile("ROLES_CLAIM, roles\\)").matcher(src);
        Set<Integer> seen = new LinkedHashSet<>();
        while (roles.find()) {
            int start = methodStart(src, roles.start());
            if (!seen.add(start)) {
                continue;
            }
            String method = src.substring(start, methodEnd(src, roles.start()));
            String where = file + ":" + lineOf(src, roles.start());
            if (!method.contains("descriptorRoles(")) {
                out.add(where + " mints roles that are not the descriptor's");
            }
            Matcher read = ROLE_SOURCE.matcher(method);
            while (read.find()) {
                out.add(where + " looks roles up (" + read.group() + ") instead of minting the ones it is given");
            }
        }
        return out;
    }

    private static List<String> signInPathOffenders(String file, String src) {
        List<String> out = new ArrayList<>();
        Matcher atSignIn = Pattern.compile(Pattern.quote(AT_SIGN_IN)).matcher(src);
        while (atSignIn.find()) {
            if (!(file.equals(LOGIN_FILE) && enclosingMethod(src, atSignIn.start()).contains(LOGIN_METHOD))) {
                out.add(file + ":" + lineOf(src, atSignIn.start())
                    + " opens a session role set outside the password login (SessionRoles.atSignIn)");
            }
        }
        if (!MINT.matcher(src).find()) {
            return out;
        }
        Matcher descriptor = NEW_DESCRIPTOR.matcher(src);
        while (descriptor.find()) {
            out.add(file + ":" + lineOf(src, descriptor.start())
                + " builds a TokenUserDescriptor itself; mint from a SessionRoles.Granted set");
        }
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher mint = MINT.matcher(src);
        while (mint.find()) {
            int start = methodStart(src, mint.start());
            if (!seen.add(start)) {
                continue;
            }
            String method = src.substring(start, methodEnd(src, mint.start()));
            String where = file + ":" + lineOf(src, mint.start());
            boolean login = file.equals(LOGIN_FILE) && method.contains(LOGIN_METHOD);
            List<String> path = new ArrayList<>();
            if (!login) {
                path.add(method);
            }
            path.addAll(calledPrivateMethods(src, method));
            for (String body : path) {
                Matcher read = ROLE_SOURCE.matcher(body);
                while (read.find()) {
                    if (!inHeldArgumentOfStillHeld(body, read.start())) {
                        out.add(where + " reads the account's roles (" + read.group()
                            + ") outside the held side of SessionRoles.stillHeld");
                    }
                }
            }
        }
        return out;
    }

    /** {@code at} sits in the second argument of a {@code SessionRoles.stillHeld(} call of its statement. */
    private static boolean inHeldArgumentOfStillHeld(String body, int at) {
        int semi = body.lastIndexOf(';', at);
        int brace = body.lastIndexOf('{', at);
        int statement = Math.max(semi, brace) + 1;
        int call = body.lastIndexOf(STILL_HELD, at);
        if (call < statement) {
            return false;
        }
        int depth = 0;
        for (int i = call + STILL_HELD.length(); i < at; i++) {
            char c = body.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return false;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                return true;
            }
        }
        return false;
    }

    private static String enclosingMethod(String src, int at) {
        return src.substring(methodStart(src, at), methodEnd(src, at));
    }

    private static int methodStart(String src, int at) {
        int end = src.lastIndexOf(METHOD_END, at);
        return end < 0 ? 0 : end + METHOD_END.length();
    }

    private static int methodEnd(String src, int at) {
        int end = src.indexOf(METHOD_END, at);
        return end < 0 ? src.length() : end;
    }

    private static int lineOf(String src, int at) {
        return (int) src.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
    }

    /** The bodies of the private methods of this file that {@code method} calls (one level). */
    private static List<String> calledPrivateMethods(String src, String method) {
        List<String> bodies = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Matcher call = CALL.matcher(method);
        while (call.find()) {
            names.add(call.group(1));
        }
        for (String callee : names) {
            Matcher decl = Pattern.compile("\n    private [^\n;=]*?\\b" + Pattern.quote(callee) + "\\(").matcher(src);
            while (decl.find()) {
                bodies.add(src.substring(decl.start(), methodEnd(src, decl.end())));
            }
        }
        return bodies;
    }

    @Test
    @DisplayName("the scan catches a role list built from the account's assignments")
    void catchesTheShapes() {
        String fromAssignments = "class A {\n"
            + "    public R refresh(String t) {\n"
            + "        List<String> roles = assignmentRepository.findByUser(user).stream()\n"
            + "                .map(a -> a.getRole().getCode()).distinct().toList();\n"
            + "        var d = new TokenUserDescriptor(id, name, roles);\n"
            + "        String at = jwtTokenProvider.generateAccessToken(d, f, sid);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", fromAssignments)).hasSize(2);

        String heldAsCarried = "class A {\n"
            + "    public R refresh(String t) {\n"
            + "        var granted = held(user);\n"
            + "        String at = jwtTokenProvider.generateAccessToken(granted.descriptor(id, name), f, sid);\n"
            + "    }\n"
            + "    private SessionRoles.Granted held(User user) {\n"
            + "        List<String> mine = EffectiveRoles.codes(user, rows);\n"
            + "        return SessionRoles.stillHeld(EffectiveRoles.codes(user, rows), mine);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", heldAsCarried)).hasSize(2);

        String signInElsewhere = "class A {\n"
            + "    public R refresh(String t) {\n"
            + "        var granted = SessionRoles.atSignIn(List.of(\"ROLE_DOCTOR\"));\n"
            + "        String at = jwtTokenProvider.generateAccessToken(granted.descriptor(id, name), f, sid);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", signInElsewhere)).hasSize(1);

        String fixed = "class A {\n"
            + "    public ResponseEntity<Object> authenticateUser(LoginRequest r) {\n"
            + "        var held = details.getAuthorities();\n"
            + "        var granted = SessionRoles.atSignIn(effectiveRoles);\n"
            + "        String at = jwtTokenProvider.generateAccessToken(granted.descriptor(id, name), null, sid);\n"
            + "    }\n"
            + "    public R refresh(String t) {\n"
            + "        var granted = stillHeld(t, user);\n"
            + "        String at = jwtTokenProvider.generateAccessToken(granted.descriptor(id, name), f, sid);\n"
            + "    }\n"
            + "    private SessionRoles.Granted stillHeld(String t, User user) {\n"
            + "        List<String> carried = jwtTokenProvider.carriedRoles(t).orElse(List.of());\n"
            + "        return SessionRoles.stillHeld(carried,\n"
            + "                EffectiveRoles.codes(user, assignmentRepository.findByUser(user)));\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", fixed)).isEmpty();

        String lookedUp = "class P {\n"
            + "    public String generateAccessToken(TokenUserDescriptor d, Instant f, String sid) {\n"
            + "        List<String> roles = Optional.ofNullable(d.roles()).filter(r -> !r.isEmpty())\n"
            + "            .orElseGet(() -> tenantRoleAssignmentAccessor.findAssignmentsForUser(id).stream().toList());\n"
            + "        claims.put(ROLES_CLAIM, roles);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders(PROVIDER, lookedUp)).hasSize(2);
        String given = "class P {\n"
            + "    public String generateAccessToken(TokenUserDescriptor d, Instant f, String sid) {\n"
            + "        List<String> roles = descriptorRoles(d);\n"
            + "        claims.put(ROLES_CLAIM, roles);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders(PROVIDER, given)).isEmpty();
    }

    @Test
    @DisplayName("every production token-minting path mints only the session's own role set")
    void productionMintsKeepTheSessionRoleSet() throws IOException {
        List<String> offenders = new ArrayList<>();
        int mintingFiles = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.equals("security/SessionRoles.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (MINT.matcher(source).find() && !rel.equals(PROVIDER)) {
                    mintingFiles++;
                }
                offenders.addAll(offenders(rel, source));
            }
        }
        assertThat(mintingFiles).as("the scan found the sign-in paths (AuthController, MfaController)")
            .isGreaterThanOrEqualTo(2);
        assertThat(offenders).isEmpty();
    }
}
