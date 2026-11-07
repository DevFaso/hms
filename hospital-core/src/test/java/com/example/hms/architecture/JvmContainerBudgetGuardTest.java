package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1314: the container was killed ("Killed", then CRASHED, no
 * OutOfMemoryError in the log): heap at 70% of the limit and every native part
 * unbounded add up past the limit, and a kernel kill takes every request down
 * where an OutOfMemoryError fails one. This reads the entrypoint's real flags
 * and the real Tomcat bound, adds up the worst case and fails when a flag goes,
 * when a bound grows past what fits, or when a heap default stops fitting.
 */
@DisplayName("D1314: the JVM's worst case fits in its container")
class JvmContainerBudgetGuardTest {

    private static final Path DOCKERFILE = Path.of("../Dockerfile");
    private static final Path PROPERTIES = Path.of("src/main/resources/application.properties");

    /** Threads outside Tomcat's pool: GC, JIT, Hikari, schedulers, Kafka, the agent. Measured in WardLoadPostgresIT. */
    private static final int OTHER_THREADS = 70;
    /** G1's tables as a share of the heap: an allowance, not a bound. */
    private static final double GC_SHARE_OF_HEAP = 0.08;
    /** malloc, symbols and the agent's native part: an allowance, not a bound. */
    private static final int OTHER_NATIVE_MB = 150;
    /** The 2 GB case has no headroom by design; the runbook says so. Nothing may make it worse. */
    private static final int TOLERATED_OVER_AT_2GB_MB = 45;

    private static String entrypoint() throws IOException {
        return Files.readString(DOCKERFILE, StandardCharsets.UTF_8);
    }

    private static int flagMb(String flag) throws IOException {
        Matcher matcher = Pattern.compile(Pattern.quote(flag) + "(\\d++)m").matcher(entrypoint());
        assertThat(matcher.find()).as("the entrypoint bounds " + flag).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static int intIn(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertThat(matcher.find()).as(regex).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private static long worstCaseMb(int limitMb, int heapPercent) throws IOException {
        int stackKb = intIn(entrypoint(), "-Xss(\\d++)k");
        int tomcat = intIn(Files.readString(PROPERTIES, StandardCharsets.UTF_8),
            "server\\.tomcat\\.threads\\.max=\\$\\{TOMCAT_MAX_THREADS:(\\d++)}");
        double heap = limitMb * heapPercent / 100.0;
        double stacks = (tomcat + OTHER_THREADS) * stackKb / 1024.0;
        return Math.round(heap + flagMb("-XX:MaxMetaspaceSize=") + flagMb("-XX:ReservedCodeCacheSize=")
            + flagMb("-XX:MaxDirectMemorySize=") + stacks + heap * GC_SHARE_OF_HEAP + OTHER_NATIVE_MB);
    }

    @Test
    @DisplayName("the heap defaults are the ones the budget is worked out for")
    void heapDefaults() throws IOException {
        String source = entrypoint();
        assertThat(intIn(source, "DEFAULT_HEAP_PCT=(\\d++)\\s")).isEqualTo(60);
        assertThat(source).contains("-lt 3072 ]; then DEFAULT_HEAP_PCT=50;");
        assertThat(source).contains("JVM_MEMORY_OPTS=\"-XX:MaxRAMPercentage=${HEAP_PCT} ${JVM_NATIVE_BOUNDS}");
        assertThat(source).contains("export MALLOC_ARENA_MAX=2");
        assertThat(source).as("the decision the bounds exist for: a spike fails one request")
            .doesNotContain("-XX:+ExitOnOutOfMemoryError").doesNotContain("-XX:+CrashOnOutOfMemoryError");
    }

    @ParameterizedTest(name = "a {0} MB limit at {1}% of heap")
    @CsvSource({"4096, 60", "8192, 60", "3072, 60"})
    void itFits(int limitMb, int heapPercent) throws IOException {
        assertThat(worstCaseMb(limitMb, heapPercent)).isLessThanOrEqualTo(limitMb);
    }

    @Test
    @DisplayName("a 2 GB limit has no headroom, and nothing may make it worse")
    void twoGigabytes() throws IOException {
        assertThat(worstCaseMb(2048, 50)).isLessThanOrEqualTo(2048L + TOLERATED_OVER_AT_2GB_MB);
    }

    @Test
    @DisplayName("the sum can fail: a heap well above the default does not fit")
    void aHeapAboveTheDefaultDoesNotFit() throws IOException {
        assertThat(worstCaseMb(4096, 80)).isGreaterThan(4096L);
    }
}
