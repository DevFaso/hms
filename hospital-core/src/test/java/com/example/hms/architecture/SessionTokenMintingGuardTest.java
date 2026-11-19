package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2182 / D2184, the class: every place production code mints a session
 * (an access or refresh token outside {@code JwtTokenProvider}) is
 *
 * <ul>
 *   <li>one of the reviewed sign-in paths: the password login and refresh
 *       ({@code AuthController}, whose login asks every enrolled account for
 *       its code) and the MFA step ({@code MfaController}). A new sign-in path
 *       must be added here on purpose, after applying the same MFA challenge;
 *       and</li>
 *   <li>stamped with a session id (the three-argument overloads), so logout
 *       can revoke the whole family. The one- and two-argument overloads mint
 *       a token no logout can reach beyond its own jti.</li>
 * </ul>
 */
@DisplayName("Sessions are minted only by the reviewed sign-in paths, always with a session id")
class SessionTokenMintingGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern MINT = Pattern.compile("\\bgenerate(?:Access|Refresh)Token\\(");
    private static final Set<String> SIGN_IN_PATHS = Set.of(
        "controller/AuthController.java", "controller/MfaController.java");

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = MINT.matcher(source);
        while (m.find()) {
            int args = topLevelArgs(source, m.end());
            int line = (int) source.substring(0, m.start()).chars().filter(c -> c == '\n').count() + 1;
            if (!SIGN_IN_PATHS.contains(file)) {
                out.add(file + ":" + line + " mints a session outside the reviewed sign-in paths");
            } else if (args != 3) {
                out.add(file + ":" + line + " mints a token without a session id");
            }
        }
        return out;
    }

    /** Top-level argument count of the call whose '(' ends just before {@code from}. */
    private static int topLevelArgs(String s, int from) {
        int depth = 0;
        int commas = 0;
        boolean any = false;
        for (int i = from; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                if (depth == 0) {
                    return any ? commas + 1 : 0;
                }
                depth--;
            } else if (c == ',' && depth == 0) {
                commas++;
            } else if (!Character.isWhitespace(c)) {
                any = true;
            }
        }
        return -1;
    }

    @Test
    @DisplayName("the scan catches a token without a session id and a new sign-in path")
    void catchesTheShapes() {
        String old = "class A { void x() { String t = jwtTokenProvider.generateAccessToken(descriptor); "
            + "String r = jwtTokenProvider.generateRefreshToken(descriptor, secondFactor); } }";
        assertThat(offenders("controller/AuthController.java", old)).hasSize(2);
        String fixed = "class A { void x() { String t = jwtTokenProvider.generateAccessToken(d, f(a, b), sid); } }";
        assertThat(offenders("controller/AuthController.java", fixed)).isEmpty();
        assertThat(offenders("service/impl/NewLoginServiceImpl.java", fixed)).hasSize(1);
    }

    @Test
    @DisplayName("every production session is minted by a reviewed sign-in path, with a session id")
    void productionMintsSessionsOnlyThroughReviewedPaths() throws IOException {
        List<String> offenders = new ArrayList<>();
        int mints = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.equals("security/JwtTokenProvider.java")) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                mints += (int) MINT.matcher(source).results().count();
                offenders.addAll(offenders(rel, source));
            }
        }
        assertThat(mints).as("the scan found the known sign-in paths").isGreaterThanOrEqualTo(6);
        assertThat(offenders).isEmpty();
    }
}
