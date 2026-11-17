package com.example.hms.architecture;

import com.example.hms.service.maternity.ObstetricAlertEscalationService;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1762: {@code LABOR_TRANSFER} was written into
 * {@code obstetric_alert_escalations.source_type}, whose V222 CHECK did not
 * allow it, so every transfer's escalation failed at the database. H2 builds
 * that table from the entity, without the CHECK, so no unit test could see it.
 *
 * <p>This guard reads the newest migration that (re)defines
 * {@code chk_oae_source_type} and requires it to allow every
 * {@code SOURCE_*} constant the service declares, and {@code SOURCE_TYPES}
 * (the set {@code raiseAfterCommit} admits) to hold every one of them.
 */
class ObstetricAlertEscalationSourceTypeGuardTest {

    private static final Path MIGRATIONS = Path.of("src/main/resources/db/migration");
    private static final Pattern VERSION = Pattern.compile("^V(\\d+)__.*\\.sql$");
    private static final Pattern CHECK = Pattern.compile(
        "chk_oae_source_type\\s+CHECK\\s*\\(\\s*source_type\\s+IN\\s*\\(([^)]*)\\)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern LITERAL = Pattern.compile("'([^']*)'");

    @Test
    void theLatestCheckAllowsEverySourceTheServiceCanWrite() throws IOException {
        Set<String> declared = declaredSourceConstants();
        assertThat(declared).as("SOURCE_* constants").isNotEmpty();
        assertThat(ObstetricAlertEscalationService.SOURCE_TYPES)
            .as("SOURCE_TYPES must list every SOURCE_* constant")
            .containsExactlyInAnyOrderElementsOf(declared);

        Set<String> allowed = latestAllowedSourceTypes();
        assertThat(allowed)
            .as("the newest chk_oae_source_type must allow every source the service writes")
            .containsAll(declared);
    }

    @Test
    void theLaborTransferNoticeUsesADeclaredSource() {
        assertThat(ObstetricAlertEscalationService.SOURCE_TYPES).contains("LABOR_TRANSFER");
    }

    private static Set<String> declaredSourceConstants() {
        Set<String> values = new LinkedHashSet<>();
        for (Field field : ObstetricAlertEscalationService.class.getDeclaredFields()) {
            int mod = field.getModifiers();
            if (field.getName().startsWith("SOURCE_") && field.getType() == String.class
                && Modifier.isStatic(mod) && Modifier.isFinal(mod)) {
                try {
                    values.add((String) field.get(null));
                } catch (IllegalAccessException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        }
        return values;
    }

    private static Set<String> latestAllowedSourceTypes() throws IOException {
        List<Path> files;
        try (Stream<Path> list = Files.list(MIGRATIONS)) {
            files = list.filter(p -> VERSION.matcher(p.getFileName().toString()).matches())
                .sorted(Comparator.comparingInt(ObstetricAlertEscalationSourceTypeGuardTest::version).reversed())
                .toList();
        }
        for (Path file : files) {
            Optional<Set<String>> allowed = allowedIn(Files.readString(file, StandardCharsets.UTF_8));
            if (allowed.isPresent()) {
                return allowed.get();
            }
        }
        throw new AssertionError("no migration defines chk_oae_source_type");
    }

    private static Optional<Set<String>> allowedIn(String sql) {
        Matcher m = CHECK.matcher(sql);
        Set<String> last = null;
        while (m.find()) {
            Set<String> values = new LinkedHashSet<>();
            Matcher lit = LITERAL.matcher(m.group(1));
            while (lit.find()) {
                values.add(lit.group(1));
            }
            last = values;
        }
        return Optional.ofNullable(last);
    }

    private static int version(Path file) {
        Matcher m = VERSION.matcher(file.getFileName().toString());
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }
}
