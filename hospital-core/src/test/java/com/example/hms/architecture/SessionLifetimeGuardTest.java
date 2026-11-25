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
 *   <li>The refresh endpoint itself asks the lifetime about the session
 *       {@code JwtTokenProvider.presentedSession(} read from the token, and
 *       mints its refresh token with the four-argument form.</li>
 *   <li>No method reference ({@code ::generateRefreshToken}) hides a mint
 *       from the argument count, and only {@code JwtTokenProvider} builds a
 *       {@link SessionLifetime.Presented}, so no caller hands the check a
 *       time of its own.</li>
 * </ul>
 */
@DisplayName("Every refresh that mints a pair has passed the session lifetime check")
class SessionLifetimeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String PROVIDER = "security/JwtTokenProvider.java";
    private static final String LIFETIME = "security/SessionLifetime.java";
    private static final Pattern REFRESH_MINT = Pattern.compile("\\bgenerateRefreshToken\\(");
    private static final Pattern MINT_REFERENCE = Pattern.compile("::\\s*generateRefreshToken\\b");
    private static final Pattern NEW_PRESENTED = Pattern.compile("\\bnew\\s+(?:[\\w.]+\\.)?Presented\\s*\\(");
    private static final String METHOD_END = "\n    }\n";

    /** The methods that open a session (sign-in time = now), by file. */
    private static final Map<String, String> SIGN_INS = Map.of(
        "controller/AuthController.java", "public ResponseEntity<Object> authenticateUser(",
        "controller/MfaController.java", "public ResponseEntity<Object> verifyMfa(");

    private static final String REFRESH_FILE = "controller/AuthController.java";
    private static final String REFRESH_METHOD = "public ResponseEntity<Object> refreshToken(";

    static List<String> offenders(String file, String source) {
        String src = source.replace("\r\n", "\n");
        List<String> out = new ArrayList<>(hiddenOffenders(file, src));
        if (file.equals(PROVIDER)) {
            return out; // its own mints are the ones under guard
        }
        out.addAll(clockRestarts(file, src));
        if (file.equals(REFRESH_FILE)) {
            out.addAll(refreshEndpointOffenders(file, src));
        }
        return out;
    }

    /** A method reference hides a mint from the argument count; a hand-built Presented, the token's own times. */
    private static List<String> hiddenOffenders(String file, String src) {
        List<String> out = new ArrayList<>();
        Matcher reference = MINT_REFERENCE.matcher(src);
        while (reference.find()) {
            out.add(file + ":" + lineOf(src, reference.start())
                + " mints a refresh token through a method reference the scan cannot check; call it");
        }
        if (file.equals(PROVIDER) || file.equals(LIFETIME)) {
            return out;
        }
        Matcher presented = NEW_PRESENTED.matcher(src);
        while (presented.find()) {
            out.add(file + ":" + lineOf(src, presented.start())
                + " builds the session the lifetime judges; only JwtTokenProvider.presentedSession reads one");
        }
        return out;
    }

    /** A three-argument mint (signed in now) outside the two sign-ins restarts the session clock. */
    private static List<String> clockRestarts(String file, String src) {
        List<String> out = new ArrayList<>();
        Matcher mint = REFRESH_MINT.matcher(src);
        while (mint.find()) {
            if (topLevelArgs(src, mint.end()) >= 4) {
                continue; // the type forces a SessionLifetime.Live: the check was passed
            }
            String signIn = SIGN_INS.get(file);
            if (signIn == null || !enclosingMethod(src, mint.start()).contains(signIn)) {
                out.add(file + ":" + lineOf(src, mint.start())
                    + " mints a refresh token that restarts the session clock outside a sign-in;"
                    + " continue a session with generateRefreshToken(..., SessionLifetime.Live)");
            }
        }
        return out;
    }

    /** The refresh reads the token's session, asks the lifetime, and mints only with the answer. */
    private static List<String> refreshEndpointOffenders(String file, String src) {
        int at = src.indexOf(REFRESH_METHOD);
        if (at < 0) {
            return List.of(file + " has no refresh endpoint method to check (" + REFRESH_METHOD + ")");
        }
        List<String> out = new ArrayList<>();
        String refresh = src.substring(at, methodEnd(src, at));
        boolean asks = refresh.contains("sessionLifetime()::live") || refresh.contains("sessionLifetime().live(");
        if (!refresh.contains("presentedSession(") || !asks) {
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
            + "        var live = jwtTokenProvider.presentedSession(t).flatMap(authProps.sessionLifetime()::live);\n"
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
            + "        var live = jwtTokenProvider.presentedSession(t).flatMap(authProps.sessionLifetime()::live);\n"
            + "        String r = jwtTokenProvider.generateRefreshToken(d, f, sid,\n"
            + "                live.get());\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders(REFRESH_FILE, fixed)).isEmpty();

        String reference = "class S {\n"
            + "    public Function<D, String> minter() {\n"
            + "        return d -> mint(jwtTokenProvider::generateRefreshToken, d);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("service/RenewService.java", reference)).hasSize(1);

        String ownTimes = "class S {\n"
            + "    public void x() {\n"
            + "        var s = new SessionLifetime.Presented(Instant.now(), Instant.now(), roles);\n"
            + "    }\n"
            + "}\n";
        assertThat(offenders("security/Other.java", ownTimes)).hasSize(1);
        assertThat(offenders(PROVIDER, ownTimes)).isEmpty();
    }

    @Test
    @DisplayName("only SessionLifetime makes a Live session, and live takes no time of the caller's")
    void liveHasNoOtherMaker() {
        assertThat(Arrays.stream(SessionLifetime.Presented.class.getDeclaredConstructors())
            .noneMatch(c -> Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers())))
            .as("SessionLifetime.Presented constructors are package-private at most").isTrue();
        assertThat(Arrays.stream(SessionLifetime.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("live"))
            .allMatch(m -> m.getParameterCount() == 1 && m.getParameterTypes()[0] == SessionLifetime.Presented.class))
            .as("live takes only a presented session, never a time").isTrue();
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
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!rel.equals(PROVIDER)) {
                    mints += (int) REFRESH_MINT.matcher(source).results().count();
                }
                sawRefresh |= rel.equals(REFRESH_FILE);
                offenders.addAll(offenders(rel, source));
            }
        }
        assertThat(sawRefresh).as("the scan found the refresh endpoint").isTrue();
        assertThat(mints).as("the scan found the login, the MFA step and the refresh").isGreaterThanOrEqualTo(3);
        assertThat(offenders).isEmpty();
    }
}
