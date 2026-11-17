package com.example.hms.architecture;

import com.example.hms.enums.PerinatalAgeBasis;
import com.example.hms.enums.PerinatalBirthStatus;
import com.example.hms.enums.PerinatalDeathType;
import com.example.hms.enums.PerinatalReportingEligibility;
import com.example.hms.model.DeathRecord;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CR-07: a 10-day-old's death recorded as LATE_NEONATAL raised the perinatal total, because a count read the broad
 * {@code perinatalDeath} flag. WHO's perinatal period ends at days 0-6 (provisional: awaits the obstetric/neonatal
 * lead and the responsible coding authority).
 *
 * <p>The flag says only that a classification was chosen. So nothing may filter or query on it alone: a query names
 * the categories, and a stream filter reads the type with it. This scans every main source, so a new dashboard,
 * export or FHIR mapper counting on the flag fails here.
 */
@DisplayName("No perinatal numerator counts a late neonatal death")
class PerinatalNumeratorGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern FLAG_FILTER = Pattern.compile("\\.filter\\([^;]*?getPerinatalDeath\\(\\)[^;]*?;");
    /** The flag read in a query: the JPQL property, not a builder call, or the native column. */
    private static final Pattern FLAG_IN_QUERY = Pattern.compile("\\.perinatalDeath\\b(?!\\()|\\bperinatal_death\\b");

    private static List<Path> sources() throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    private static String code(Path file) throws IOException {
        return SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
    }

    private static String name(Path file) {
        return MAIN.relativize(file).toString().replace('\\', '/');
    }

    @Test
    void onlyTheNamedCategoriesCanBeInTheNumerator() {
        assertThat(Arrays.stream(PerinatalDeathType.values()).filter(PerinatalDeathType::inWhoPerinatalPeriod))
            .containsExactlyInAnyOrder(PerinatalDeathType.STILLBIRTH, PerinatalDeathType.EARLY_NEONATAL);
    }

    @Test
    void lateNeonatalIsOutOfTheNumeratorWhateverElseTheRowHolds() {
        for (Integer weeks : new Integer[] {null, 21, 22, 38}) {
            for (PerinatalBirthStatus status : PerinatalBirthStatus.values()) {
                for (PerinatalAgeBasis basis : PerinatalAgeBasis.values()) {
                    DeathRecord row = DeathRecord.builder().perinatalDeath(true).perinatalType(PerinatalDeathType.LATE_NEONATAL)
                        .gestationalAgeWeeks(weeks).birthStatus(status).perinatalAgeBasis(basis).build();
                    assertThat(row.isInWhoPerinatalNumerator()).isFalse();
                    assertThat(row.getPerinatalEligibility()).isEqualTo(PerinatalReportingEligibility.NOT_ELIGIBLE);
                }
            }
        }
    }

    @Test
    void theRegistersFigureIsTheRecordsOwnDefinition() throws IOException {
        assertThat(code(MAIN.resolve("service/impl/MortalityServiceImpl.java")))
            .contains(".filter(DeathRecord::isInWhoPerinatalNumerator)");
    }

    @Test
    void noStreamFiltersOnTheFlagAlone() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : sources()) {
            Matcher matcher = FLAG_FILTER.matcher(code(source));
            while (matcher.find()) {
                if (!matcher.group().contains("getPerinatalType()")) {
                    offenders.add(name(source) + ": " + matcher.group());
                }
            }
        }
        assertThat(offenders).as(SourceScan.MANDATE).isEmpty();
    }

    @Test
    void theOnlyQueryOnTheFlagNamesTheCategoriesItCounts() throws IOException {
        Set<String> readers = new TreeSet<>();
        for (Path source : sources()) {
            if (source.endsWith("DeathRecord.java")) {
                continue;
            }
            if (FLAG_IN_QUERY.matcher(code(source)).find()) {
                readers.add(name(source));
            }
        }
        assertThat(readers).as("A new query on the perinatal flag must exclude LATE_NEONATAL, then be listed here")
            .containsExactly("repository/DeathRecordRepository.java");

        String repository = code(MAIN.resolve("repository/DeathRecordRepository.java"));
        assertThat(repository).doesNotContain("PerinatalDeathType.LATE_NEONATAL");
        assertThat(repository).contains("d.perinatalType IN (com.example.hms.enums.PerinatalDeathType.STILLBIRTH");
    }
}
