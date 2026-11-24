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
 * The class: a session's role set is fixed when it is opened and never
 * widened by a later token of it, so the roles a token carries are the ones
 * its sign-in (and its MFA challenge) decided on. A role list rebuilt from
 * the account's assignments ({@code assignmentRepository.findByUser(user)...
 * getRole().getCode()}) in a minting path breaks that rule.
 *
 * <p>Every production method that mints an access or refresh token (outside
 * {@code JwtTokenProvider}), together with the private methods of the same
 * class it calls, either
 * <ul>
 *   <li>is a fresh password sign-in ({@code authenticationManager.authenticate(}),
 *       whose roles are the ones just authenticated and picked; or</li>
 *   <li>derives its roles through {@code SessionRoles.stillHeld(} — the roles
 *       the session carries, intersected with what the account still holds.</li>
 * </ul>
 * and reads the account's roles (assignment rows' {@code getRole()},
 * {@code EffectiveRoles.codes(}, {@code loadUserByUsername(},
 * {@code getRolesFromToken(}) only as the "still held" side of that
 * intersection, in the same statement.
 */
@DisplayName("A token-minting path never builds its roles from the account's assignments alone")
class SessionRoleScopeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern MINT = Pattern.compile("\\bgenerate(?:Access|Refresh)Token\\(");
    private static final Pattern CALL = Pattern.compile("\\b([a-z]\\w*)\\(");
    /** A read of the roles an account holds now (or a token's raw role claim). */
    private static final Pattern ROLE_SOURCE = Pattern.compile(
        "\\.getRole\\(\\)|\\bEffectiveRoles\\.codes\\(|\\bloadUserByUsername\\(|\\bgetRolesFromToken\\(");
    private static final String INTERSECTION = "SessionRoles.stillHeld(";
    private static final String PASSWORD_SIGN_IN = "authenticationManager.authenticate(";
    private static final String METHOD_END = "\n    }\n";

    static List<String> offenders(String file, String source) {
        String src = source.replace("\r\n", "\n");
        List<String> out = new ArrayList<>();
        Set<Integer> seen = new LinkedHashSet<>();
        Matcher mint = MINT.matcher(src);
        while (mint.find()) {
            int start = methodStart(src, mint.start());
            if (!seen.add(start)) {
                continue;
            }
            String method = src.substring(start, methodEnd(src, mint.start()));
            String name = file + ":" + lineOf(src, mint.start());
            List<String> path = new ArrayList<>();
            path.add(method);
            path.addAll(calledPrivateMethods(src, method));
            boolean intersects = path.stream().anyMatch(body -> body.contains(INTERSECTION));
            if (!method.contains(PASSWORD_SIGN_IN) && !intersects) {
                out.add(name + " mints a token whose roles are not the session's carried roles "
                    + "(SessionRoles.stillHeld) nor a fresh password sign-in's");
            }
            for (String body : path) {
                Matcher read = ROLE_SOURCE.matcher(body);
                while (read.find()) {
                    String statement = body.substring(statementStart(body, read.start()), read.start());
                    if (!statement.contains(INTERSECTION)) {
                        out.add(name + " builds roles from what the account holds (" + read.group()
                            + ") without intersecting them with the session's carried roles");
                    }
                }
            }
        }
        return out;
    }

    private static int methodStart(String src, int at) {
        int end = src.lastIndexOf(METHOD_END, at);
        return end < 0 ? 0 : end + METHOD_END.length();
    }

    private static int methodEnd(String src, int at) {
        int end = src.indexOf(METHOD_END, at);
        return end < 0 ? src.length() : end;
    }

    private static int statementStart(String body, int at) {
        int semi = body.lastIndexOf(';', at);
        int brace = body.lastIndexOf('{', at);
        return Math.max(semi, brace) + 1;
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
    @DisplayName("the scan catches the pre-fix refresh and accepts the fixed one")
    void catchesTheShapes() {
        String before = "class A {\n"
            + "    public R refresh(String t) {\n"
            + "        List<String> roles = assignmentRepository.findByUser(user).stream()\n"
            + "                .filter(a -> Boolean.TRUE.equals(a.getActive()))\n"
            + "                .map(a -> a.getRole().getCode()).distinct().toList();\n"
            + "        String at = jwtTokenProvider.generateAccessToken(descriptor, f, sid);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", before)).hasSize(2);

        String viaHelperWithoutIntersection = "class A {\n"
            + "    public R refresh(String t) {\n"
            + "        List<String> roles = held(user);\n"
            + "        String at = jwtTokenProvider.generateAccessToken(descriptor, f, sid);\n"
            + "    }\n"
            + "    private List<String> held(User user) {\n"
            + "        return EffectiveRoles.codes(user, assignmentRepository.findByUser(user)).stream().toList();\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", viaHelperWithoutIntersection)).hasSize(2);

        String fixed = "class A {\n"
            + "    public R refresh(String t) {\n"
            + "        List<String> roles = stillHeld(t, user);\n"
            + "        String at = jwtTokenProvider.generateAccessToken(descriptor, f, sid);\n"
            + "    }\n"
            + "    private List<String> stillHeld(String t, User user) {\n"
            + "        return jwtTokenProvider.carriedRoles(t).map(carried -> SessionRoles.stillHeld(carried,\n"
            + "                EffectiveRoles.codes(user, assignmentRepository.findByUser(user)))).orElse(List.of());\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", fixed)).isEmpty();

        String signIn = "class A {\n"
            + "    public R login(LoginRequest r) {\n"
            + "        Authentication a = authenticationManager.authenticate(token);\n"
            + "        String at = jwtTokenProvider.generateAccessToken(descriptor, null, sid);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("controller/AuthController.java", signIn)).isEmpty();
    }

    @Test
    @DisplayName("every production token-minting path keeps the session's carried roles")
    void productionMintsKeepTheCarriedRoles() throws IOException {
        List<String> offenders = new ArrayList<>();
        int mintingFiles = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.equals("security/JwtTokenProvider.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (MINT.matcher(source).find()) {
                    mintingFiles++;
                    offenders.addAll(offenders(rel, source));
                }
            }
        }
        assertThat(mintingFiles).as("the scan found the sign-in paths (AuthController, MfaController)")
            .isGreaterThanOrEqualTo(2);
        assertThat(offenders).isEmpty();
    }
}
