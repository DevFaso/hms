package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Log lines on the login, session, token, dashboard and rate-limit paths carry no username, email,
 * phone or token: the sink is a third-party store. A log call in these classes may name a user id
 * (a UUID) or nothing. An email may appear only through {@code maskEmail}.
 */
class AuthLogPrivacyGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final List<String> CLASSES = List.of(
        "controller/AuthController.java",
        "service/AuthBootstrapServiceImpl.java",
        "service/DashboardConfigService.java",
        "security/JwtAuthenticationFilter.java",
        "security/JwtTokenProvider.java",
        "security/WsTicketService.java",
        "security/RateLimitFilter.java",
        "security/CustomUserDetailsService.java",
        "security/JwtAuthenticationEntryPoint.java");

    private static final Pattern LOG_CALL =
        Pattern.compile("\\blog\\.(?:trace|debug|info|warn|error)\\((.*?)\\);", Pattern.DOTALL);
    /** An identifier that carries a person or a credential. */
    private static final Pattern PERSONAL_ARGUMENT = Pattern.compile(
        "(?i)username|email|phone|subject|principal|getName\\(\\)|authHeader|getHeader\\(");

    /** The call text with every string literal emptied, so only arguments are judged. */
    static String arguments(String call) {
        StringBuilder out = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < call.length(); i++) {
            char c = call.charAt(i);
            if (quoted) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    quoted = false;
                }
            } else if (c == '"') {
                quoted = true;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static List<String> offenders(String source) {
        List<String> found = new ArrayList<>();
        Matcher m = LOG_CALL.matcher(source);
        while (m.find()) {
            String call = m.group(1);
            String args = arguments(call).replaceAll("maskEmail\\([^)]*\\)", "")
                .replace("Thread.currentThread().getName()", "");
            boolean literalUser = call.contains("user='") || call.contains("user '") || call.contains("username='");
            if (literalUser || PERSONAL_ARGUMENT.matcher(args).find()) {
                found.add(call.replaceAll("\\s+", " ").trim());
            }
        }
        return found;
    }

    @Test
    void noLogCallOnTheAuthPathsNamesAPerson() throws IOException {
        List<String> all = new ArrayList<>();
        for (String name : CLASSES) {
            String source = Files.readString(MAIN.resolve(name), StandardCharsets.UTF_8);
            offenders(source).forEach(o -> all.add(name + ": " + o));
        }
        assertThat(all).as("log calls that print a username, email, phone, subject or header").isEmpty();
    }

    @Test
    void theScannerSeesAUsernameInterpolation() {
        assertThat(offenders("log.info(\"[LOGIN] Success user='{}'\", loginRequest.getUsername());")).hasSize(1);
        assertThat(offenders("log.debug(\"[X] {}\", request.getHeader(HEADER));")).hasSize(1);
        assertThat(offenders("log.info(\"[LOGIN] Success roles={}\", roles);")).isEmpty();
        assertThat(offenders("log.warn(\"to '{}'\", maskEmail(user.getEmail()));")).isEmpty();
    }
}
