package com.example.hms.db;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every migration on disk must be registered in {@code changelog.xml}.
 *
 * <p>A {@code .sql} file under {@code db/migration} does nothing on its own.
 * Liquibase runs exactly what this changelog lists, so an unregistered file is
 * inert — and inert in the most dangerous way available, because it looks
 * present in code review, in the diff, and in the directory listing.
 *
 * <p>This has now cost two outages. V47/V48/V49 sat unregistered as empty
 * placeholders, so anybody who later wrote SQL into one would have watched it
 * run nothing. Then V116, V117 and V118 shipped unregistered behind three
 * merged PRs: the entities declared the columns, {@code ddl-auto=validate}
 * found them missing, and the application refused to start with
 * "missing column [critical_escalation_level] in table [lab.lab_results]" —
 * a total outage from three files that were, by every other measure, delivered.
 *
 * <p>Nothing else catches this. The H2 test profile builds its schema from the
 * entities with {@code create-drop}, so the columns always exist there no
 * matter what the changelog says; the mismatch is only reachable against a real
 * Postgres that migrated. Hence a plain unit test that compares two lists.
 */
class MigrationRegistrationTest {

    /**
     * Matches the {@code path=} of every {@code <sqlFile>} in the changelog.
     *
     * <p>The previous form was {@code <sqlFile\s+[^>]*path\s*=\s*"([^"]+)"}.
     * There {@code \s+} and {@code [^>]*} can both match the same run of
     * whitespace, so on a tag that starts to match and then fails the engine
     * re-partitions that run every possible way before giving up — the
     * super-linear backtracking Sonar flagged. Making the middle reluctant and
     * anchoring on {@code path="} removes the overlap. The changelog never
     * writes spaces around the equals, so nothing that used to match stops.
     */
    private static final Pattern SQL_FILE_PATH =
        Pattern.compile("<sqlFile\\b[^>]*?path=\"([^\"]+)\"", Pattern.DOTALL);

    /**
     * The versioned migrations — {@code V1__…} through {@code V121__…}, plus the
     * dotted-minor form {@code V1_1__add_unique_constraints.sql} that predates
     * the flat numbering.
     */
    private static final Pattern VERSIONED = Pattern.compile("^V\\d+(_\\d+)*__.*\\.sql$");

    /**
     * {@code R__prod_role_grants.sql} is excluded from the registration check
     * because it is not a Liquibase artefact at all.
     *
     * <p>Its {@code R__} prefix is Flyway's repeatable-migration convention, not
     * Liquibase's — Liquibase discovers nothing by filename, and expresses
     * repeatability as {@code runOnChange="true"} on a changeSet it lists.
     * Nothing in the changelog references the file; it is run by hand, as
     * postgres, on prod (see its header). What this class does check is that
     * its schema arrays keep up with the migrations —
     * {@link #roleGrantsCoverEverySchemaTheMigrationsCreate()}.
     *
     * <p>It is excluded rather than registered because registering it here would
     * make every deploy attempt to GRANT to hms_app / hms_readonly /
     * hms_migrator, which fails outright where those roles do not exist. Whether
     * those grants should run, and in which environments, is an operational
     * decision — not one to smuggle into a test fix.
     */
    private static final Set<String> NOT_LIQUIBASE_MANAGED = Set.of("R__prod_role_grants.sql");

    @Test
    void everyMigrationFileIsRegisteredInTheChangelog() throws IOException {
        Path migrationDir = migrationDirectory();
        String changelog = Files.readString(migrationDir.resolve("changelog.xml"), StandardCharsets.UTF_8);

        Set<String> registered = new TreeSet<>();
        Matcher matcher = SQL_FILE_PATH.matcher(changelog);
        while (matcher.find()) {
            registered.add(matcher.group(1));
        }

        Set<String> onDisk = new TreeSet<>();
        try (Stream<Path> files = Files.list(migrationDir)) {
            files.map(p -> p.getFileName().toString())
                .filter(name -> name.endsWith(".sql"))
                .filter(name -> !NOT_LIQUIBASE_MANAGED.contains(name))
                .forEach(onDisk::add);
        }

        // Anything that is neither a versioned migration nor a documented
        // exception is a naming mistake: Liquibase will not pick it up and no
        // convention here explains it.
        Set<String> unrecognised = new TreeSet<>(onDisk);
        unrecognised.removeIf(name -> VERSIONED.matcher(name).matches());
        assertThat(unrecognised)
            .withFailMessage(
                "These files do not follow the V<n>__name.sql convention, so nothing will run "
                    + "them: %s", unrecognised)
            .isEmpty();

        Set<String> unregistered = new TreeSet<>(onDisk);
        unregistered.removeAll(registered);

        assertThat(unregistered)
            .withFailMessage(
                "These migrations exist on disk but are not registered in changelog.xml, so "
                    + "Liquibase will never run them: %s%n%n"
                    + "Add a <changeSet> with a <sqlFile path=\"...\"> for each. Use "
                    + "splitStatements=\"false\" if the file contains a DO $$ block.",
                unregistered)
            .isEmpty();
    }

    @Test
    void everyRegisteredMigrationExistsOnDisk() throws IOException {
        // The mirror image: a changelog entry pointing at a file that is not
        // there fails the deploy at startup rather than silently, but it fails
        // it in production, which is a poor place to find out.
        Path migrationDir = migrationDirectory();
        String changelog = Files.readString(migrationDir.resolve("changelog.xml"), StandardCharsets.UTF_8);

        Set<String> missing = new TreeSet<>();
        Matcher matcher = SQL_FILE_PATH.matcher(changelog);
        while (matcher.find()) {
            String path = matcher.group(1);
            if (!Files.exists(migrationDir.resolve(path))) {
                missing.add(path);
            }
        }

        assertThat(missing)
            .withFailMessage("changelog.xml references migrations that do not exist: %s", missing)
            .isEmpty();
    }

    @Test
    void noMigrationIsEmpty() throws IOException {
        // V47/V48/V49 were 0-byte files. An empty migration is a trap: it reads
        // as "this phase shipped" while doing nothing, and writing SQL into one
        // later still runs nothing unless it is also registered.
        Path migrationDir = migrationDirectory();

        List<String> empty;
        try (Stream<Path> files = Files.list(migrationDir)) {
            empty = files.filter(p -> p.getFileName().toString().endsWith(".sql"))
                .filter(MigrationRegistrationTest::isBlank)
                .map(p -> p.getFileName().toString())
                .sorted()
                .toList();
        }

        assertThat(empty)
            .withFailMessage("These migrations are empty and will apply nothing: %s", empty)
            .isEmpty();
    }

    /** {@code CREATE SCHEMA [IF NOT EXISTS] name} in any versioned migration. */
    private static final Pattern CREATE_SCHEMA =
        Pattern.compile("CREATE\\s+SCHEMA\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z_]+)", Pattern.CASE_INSENSITIVE);

    /** One {@code schemas TEXT[] := ARRAY[...]} literal in the grants file. */
    private static final Pattern SCHEMA_ARRAY = Pattern.compile("ARRAY\\[([^\\]]+)\\]");

    @Test
    void roleGrantsCoverEverySchemaTheMigrationsCreate() throws IOException {
        // R__prod_role_grants.sql is run by hand on prod, where Liquibase runs
        // as postgres and the application as hms_app. A schema missing from its
        // arrays is a schema hms_app cannot even USAGE: `scheduling` (V19) and
        // `integration` (V68) sat outside the list until 2026-09-05, and every
        // waitlist / recall / DHIS2 query on prod failed with
        // "permission denied for schema scheduling" while the H2 suite, which
        // has one user, saw nothing.
        Path migrationDir = migrationDirectory();

        Set<String> created = new TreeSet<>();
        try (Stream<Path> files = Files.list(migrationDir)) {
            for (Path file : files.filter(p -> VERSIONED.matcher(p.getFileName().toString()).matches()).toList()) {
                Matcher matcher = CREATE_SCHEMA.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (matcher.find()) {
                    created.add(matcher.group(1).toLowerCase());
                }
            }
        }
        assertThat(created).isNotEmpty();

        String grants = Files.readString(migrationDir.resolve("R__prod_role_grants.sql"), StandardCharsets.UTF_8);
        Matcher arrays = SCHEMA_ARRAY.matcher(grants);
        int arraysChecked = 0;
        while (arrays.find()) {
            arraysChecked++;
            Set<String> granted = new TreeSet<>();
            for (String element : arrays.group(1).split(",")) {
                granted.add(element.trim().replace("'", "").toLowerCase());
            }
            Set<String> missing = new TreeSet<>(created);
            missing.removeAll(granted);
            assertThat(missing)
                .withFailMessage(
                    "R__prod_role_grants.sql array #%d omits schemas the migrations create, so "
                        + "hms_app on prod cannot reach any table in them: %s%n%n"
                        + "Add them to every schema array in the file and re-run it on prod.",
                    arraysChecked, missing)
                .isEmpty();
        }
        assertThat(arraysChecked)
            .withFailMessage("R__prod_role_grants.sql no longer declares its schema arrays as ARRAY[...]")
            .isPositive();
    }

    private static boolean isBlank(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).isBlank();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read migration " + path, e);
        }
    }

    /**
     * Resolved from the classpath rather than a hard-coded relative path, so the
     * test does not depend on the working directory Gradle happens to use.
     */
    private static Path migrationDirectory() {
        URL changelog = MigrationRegistrationTest.class.getClassLoader()
            .getResource("db/migration/changelog.xml");
        assertThat(changelog)
            .withFailMessage("db/migration/changelog.xml is not on the test classpath")
            .isNotNull();
        try {
            return Paths.get(changelog.toURI()).getParent();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalStateException("Could not resolve the migration directory", e);
        }
    }
}
