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
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D2000 class: "a channel reported as sent when nothing was sent". On a
 * deployment without an SMS provider the {@code SmsService} bean is the
 * logging {@code MockSmsServiceImpl}: {@code send} returns normally, so a
 * caller that does not ask {@code deliversRealSms()} tells its user (or its
 * log, or its delivery counter) that a text went out when none did.
 *
 * <p>Every production class that holds an {@code SmsService} and calls
 * {@code send} must therefore ask {@code deliversRealSms()} in the same file,
 * and refuse, fall back or report "not sent". Known offenders not fixed in
 * the D2000 PR are listed below with their follow-up, never silently skipped.
 */
@DisplayName("An SMS is reported sent only over a real transport")
class SmsRealTransportGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** Holds an SmsService (field or provider), by any name. */
    private static final Pattern HOLDS_SMS_SERVICE = Pattern.compile(
        "(?:private|protected)\\s+(?:final\\s+)?(?:SmsService|ObjectProvider<SmsService>)\\s+\\w+\\s*;");
    private static final Pattern CALLS_SEND = Pattern.compile("\\.send\\(");
    private static final Pattern ASKS_REAL_TRANSPORT = Pattern.compile("\\.deliversRealSms\\(\\)");

    /**
     * Known offenders, each with the reason it is not fixed here (tasklist.md,
     * "Standing debt — recovery phone code (fix/recovery-phone-code-sms, D2000)").
     */
    private static final Map<String, String> KNOWN = Map.of(
        "service/impl/PrescriptionSmsDispatchServiceImpl.java",
        "D2000-followup: records a SENT transmission and tells the prescriber the pharmacy was reached over the mock",
        "service/pharmacy/partner/SmsPartnerNotificationChannel.java",
        "D2000-followup: partner pharmacy notices go to the mock without a not-sent trace");

    static boolean offends(String source) {
        return HOLDS_SMS_SERVICE.matcher(source).find()
            && CALLS_SEND.matcher(source).find()
            && !ASKS_REAL_TRANSPORT.matcher(source).find();
    }

    @Test
    @DisplayName("the scan catches the D2000 shape and accepts the fix")
    void catchesTheShape() {
        String before = """
            class S {
                private final SmsService smsService;
                void remind(String phone) { smsService.send(phone, "x"); }
            }
            """;
        assertThat(offends(before)).isTrue();
        assertThat(offends(before.replace("smsService.send(phone, \"x\");",
            "if (smsService.deliversRealSms()) { smsService.send(phone, \"x\"); }"))).isFalse();
        assertThat(offends(before.replace("private final SmsService smsService;",
            "private final ObjectProvider<SmsService> smsService;"))).isTrue();
    }

    @Test
    @DisplayName("every SmsService caller asks deliversRealSms(), or is a listed follow-up")
    void everyCallerAsksForARealTransport() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (offends(source)) {
                    offenders.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        assertThat(new TreeSet<>(offenders))
            .as("call SmsService.send only after deliversRealSms(); the mock sends nothing")
            .isEqualTo(new TreeSet<>(KNOWN.keySet()));
    }
}
