package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA r81 D4317: an organization ended {@code active:false} with
 * {@code lifecycleState: ACTIVE} because a controller cleared the flag on
 * its own. The flag and the state move together in the lifecycle service;
 * this scans the main sources so that no other class writes an
 * organization's {@code active} flag again.
 *
 * <p>The mapper is listed: it copies the request's flag only after the
 * controller has run the transition through the lifecycle service, and on
 * create the controller aligns the state itself.
 */
class OrganizationActiveFlagGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Pattern WRITE = Pattern.compile(
        "\\b(?:org|organization|organisation|tenant)\\w*\\.setActive\\(", Pattern.CASE_INSENSITIVE);
    private static final Set<String> ALLOWED = Set.of(
        "OrganizationLifecycleServiceImpl.java",
        "OrganizationMapper.java",
        // The purge job's last step: PURGED and inactive in the same statement block.
        "TenantPurgeExecutor.java");

    @Test
    void onlyTheLifecycleServiceWritesAnOrganizationsActiveFlag() throws IOException {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                scanned++;
                String name = file.getFileName().toString();
                if (ALLOWED.contains(name) || name.contains("Security")) {
                    continue;
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    Matcher m = WRITE.matcher(lines.get(i));
                    if (m.find()) {
                        offenders.add(MAIN.relativize(file) + ":" + (i + 1) + " " + lines.get(i).trim());
                    }
                }
            }
        }
        assertThat(scanned).as("main sources scanned").isGreaterThan(500);
        assertThat(offenders)
            .as("an organization's active flag is written by OrganizationLifecycleService only "
                + "(deactivate / reactivate / suspend / restore / archive), so it never disagrees with lifecycleState")
            .isEmpty();
    }
}
