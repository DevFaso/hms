package com.example.hms.architecture;

import com.example.hms.security.SessionLifetime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A session ends a fixed time after its sign-in, however often it is
 * refreshed: every refresh that succeeds has passed the lifetime check.
 *
 * <p>Held by types and by this scan together:
 * <ul>
 *   <li>The refresh token that continues a session is minted only with a
 *       {@link SessionLifetime.Live} (the four-argument
 *       {@code generateRefreshToken}), and only {@link SessionLifetime#live}
 *       makes one: {@code Live} has no constructor another class can call.</li>
 *   <li>The three-argument {@code generateRefreshToken}, which opens a
 *       session signed in to now, is called only by the two sign-ins: the
 *       password login ({@code AuthController.authenticateUser}) and the MFA
 *       step ({@code MfaController.verifyMfa}). Anywhere else, it would let a
 *       refresh restart the clock.</li>
 *   <li>The refresh endpoint itself asks {@code sessionLifetime().live(} and
 *       mints its refresh token with the four-argument form.</li>
 * </ul>
 */
@DisplayName("Every refresh that mints a pair has passed the session lifetime check")
class SessionLifetimeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String PROVIDER = "security/JwtTokenProvider.java";
    private static final Pattern REFRESH_MINT = Pattern.compile("\\bgenerateRefreshToken\\(");
    private static final String METHOD_END = "\n    }\n";

    /** The methods that open a session (sign-in time = now), by file. */
    private static final Map<String, String> SIGN_INS = Map.of(
        "controller/AuthController.java", "public ResponseEntity<Object> authenticateUser(",
        "controller/MfaController.java", "public ResponseEntity<Object> verifyMfa(");

    private static final String REFRESH_FILE = "controller/AuthController.java";
    private static final String REFRESH_METHOD = "public ResponseEntity<Object> refreshToken(";

    static List<String> offenders(String file, String source) {
        String src = source.replace("\r\n", "\n");
        List<String> out = new ArrayList<>();
        Matcher mint = REFRESH_MINT.matcher(src);
        while (mint.find()) {
            int args = topLevelArgs(src, mint.end());
            if (args >= 4) {
                continue; // the type forces a SessionLifetime.Live: the check was passed
            }
            String method = enclosingMethod(src, mint.start());
            String signIn = SIGN_INS.get(file);
            if (signIn == null || !method.contains(signIn)) {
                out.add(file + ":" + lineOf(src, mint.start())
                    + " mints a refresh token that restarts the session clock outside a sign-in;"
                    + " continue a session with generateRefreshToken(..., SessionLifetime.Live)");
            }
        }
        if (file.equals(REFRESH_FILE)) {
            int at = src.indexOf(REFRESH_METHOD);
            if (at < 0) {
                out.add(file + " has no refresh endpoint method to check (" + REFRESH_METHOD + ")");
            } else {
                String refresh = src.substring(at, methodEnd(src, at));
                if (!refresh.contains("sessionLifetime().live(")) {
                    out.add(file + ":" + lineOf(src, at) + " the refresh does not check the session lifetime");
                }
                Matcher inRefresh = REFRESH_MINT.matcher(refresh);
                boolean mints = false;
                while (inRefresh.find()) {
                    mints = true;
                    if (topLevelArgs(refresh, inRefresh.end()) < 4) {
                        out.add(file + ":" + lineOf(src, at + inRefresh.start())
                            + " the refresh mints a refresh token without the checked session");
                    }
                }
                if (!mints) {
                    out.add(file + ":" + lineOf(src, at) + " the refresh mints no refresh token the scan can see");
                }
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

    private static String enclosingMethod(String src, int at) {
        int end = src.lastIndexOf(METHOD_END, at);
        int start = end < 0 ? 0 : end + METHOD_END.length();
        return src.substring(start, methodEnd(src, at));
    }

    private static int methodEnd(String src, int at) {
        int end = src.indexOf(METHOD_END, at);
        return end < 0 ? src.length() : end;
    }

    private static int lineOf(String src, int at) {
        return (int) src.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
    }

    @Test
    @DisplayName("the scan catches a refresh that restarts the clock or skips the check")
    void catchesTheShapes() {
        String restarts = "class A {\n"
            + "    public ResponseEntity<Object> refreshToken(Map b) {\n"
            + "        var live = authProps.sessionLifetime().live(a, i, now);\n"
            + "        String r = jwtTokenProvider.generateRefreshToken(d, f, sid);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders(REFRESH_FILE, restarts)).hasSize(2);

        String unchecked = "class A {\n"
            + "    public ResponseEntity<Object> refreshToken(Map b) {\n"
            + "        String r = jwtTokenProvider.generateRefreshToken(d, f, sid, live);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders(REFRESH_FILE, unchecked)).hasSize(1);

        String elsewhere = "class S {\n"
            + "    public String renew(String t) {\n"
            + "        return jwtTokenProvider.generateRefreshToken(d, null, sid);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("service/RenewService.java", elsewhere)).hasSize(1);

        String fixed = "class A {\n"
            + "    public ResponseEntity<Object> authenticateUser(LoginRequest r) {\n"
            + "        String r = jwtTokenProvider.generateRefreshToken(d, null, sid);\n"
            + "    }\n"
            + "    public ResponseEntity<Object> refreshToken(Map b) {\n"
            + "        var live = authProps.sessionLifetime().live(a, i, now);\n"
            + "        String r = jwtTokenProvider.generateRefreshToken(d, f, sid,\n"
            + "                live.get());\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders(REFRESH_FILE, fixed)).isEmpty();
    }

    @Test
    @DisplayName("only SessionLifetime can make a Live session")
    void liveHasNoOtherMaker() {
        Constructor<?>[] constructors = SessionLifetime.Live.class.getDeclaredConstructors();
        assertThat(constructors).isNotEmpty();
        assertThat(Arrays.stream(constructors).allMatch(c -> Modifier.isPrivate(c.getModifiers())))
            .as("SessionLifetime.Live constructors are private").isTrue();
        assertThat(Modifier.isFinal(SessionLifetime.Live.class.getModifiers())).isTrue();
    }

    @Test
    @DisplayName("every production refresh mint is a sign-in or a checked continuation")
    void productionRefreshesPassTheLifetimeCheck() throws IOException {
        List<String> offenders = new ArrayList<>();
        int mints = 0;
        boolean sawRefresh = false;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.equals(PROVIDER)) {
                    continue;
                }
                String source = Files.readString(file, StandardCharsets.UTF_8);
                mints += (int) REFRESH_MINT.matcher(source).results().count();
                sawRefresh |= rel.equals(REFRESH_FILE);
                offenders.addAll(offenders(rel, source));
            }
        }
        assertThat(sawRefresh).as("the scan found the refresh endpoint").isTrue();
        assertThat(mints).as("the scan found the login, the MFA step and the refresh").isGreaterThanOrEqualTo(3);
        assertThat(offenders).isEmpty();
    }
}
