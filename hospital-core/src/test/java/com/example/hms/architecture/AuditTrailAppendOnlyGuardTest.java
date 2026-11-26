package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.FrontendAuditEventRepository;
import com.example.hms.repository.PermissionMatrixAuditEventRepository;
import com.example.hms.testsupport.SourceScan;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Modifying;

/**
 * V382 (class guard): the audit tables are append-only. The database refuses UPDATE, DELETE and TRUNCATE with
 * triggers, and the one delete is {@code support.purge_expired_audit_rows}, called by
 * {@code AuditRetentionPurgeJob}. This keeps both halves true:
 *
 * <ul>
 *   <li>V382 creates the triggers on every audit table, and no other migration drops, disables or redefines
 *   them, drops an audit table, or switches triggers off with {@code session_replication_role}. A migration
 *   that genuinely has to correct audit rows goes on {@link #REVIEWED_MIGRATIONS} with its reason.</li>
 *   <li>No application code deletes or updates audit rows: the audit repositories declare no modifying or
 *   delete method, nothing calls a delete on them, no SQL or JPQL in the code targets an audit table with
 *   UPDATE/DELETE/TRUNCATE, nothing touches the {@code User.auditEvents} collection (its orphan removal would
 *   delete rows) or rewrites a signature's trail, and only the retention job calls the purge function.</li>
 * </ul>
 */
class AuditTrailAppendOnlyGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");
    private static final String V382 = "V382__audit_rows_append_only.sql";
    private static final String PURGE_JOB = "AuditRetentionPurgeJob.java";

    /** Every audit table V382 protects. */
    private static final List<String> AUDIT_TABLES = List.of(
        "support.audit_event_logs",
        "support.frontend_audit_events",
        "public.permission_matrix_audit_events",
        "clinical.digital_signature_audit_log");

    private static final String TABLE_NAMES =
        "(?:audit_event_logs|frontend_audit_events|permission_matrix_audit_events|digital_signature_audit_log)";
    private static final String ENTITY_NAMES = "(?:AuditEventLog|FrontendAuditEvent|PermissionMatrixAuditEvent)";

    /**
     * Migrations other than V382 allowed to contain a pattern below, each with the reason it was reviewed.
     * Empty: none today.
     */
    private static final Set<String> REVIEWED_MIGRATIONS = Set.of();

    private static final List<Pattern> MIGRATION_BYPASSES = List.of(
        Pattern.compile("DROP\\s+TRIGGER[^;]*\\btrg_audit_(?:append_only|no_truncate)\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("ALTER\\s+TABLE[^;]*\\b" + TABLE_NAMES + "\\b[^;]*\\bDISABLE\\s+TRIGGER",
            Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\baudit_row_is_append_only\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bpurge_expired_audit_rows\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("DROP\\s+TABLE[^;]*\\b" + TABLE_NAMES + "\\b", Pattern.CASE_INSENSITIVE),
        Pattern.compile("\\bsession_replication_role\\b", Pattern.CASE_INSENSITIVE));

    private static final Pattern SQL_WRITE_TO_AUDIT_TABLE = Pattern.compile(
        "\\b(?:DELETE\\s+FROM|UPDATE|TRUNCATE(?:\\s+TABLE)?)\\s+(?:ONLY\\s+)?(?:\"?\\w+\"?\\.)?\"?" + TABLE_NAMES
            + "\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern JPQL_WRITE_TO_AUDIT_ENTITY = Pattern.compile(
        "(?i:\\b(?:DELETE(?:\\s+FROM)?|UPDATE))\\s+" + ENTITY_NAMES + "\\b");
    private static final Pattern AUDIT_REPOSITORY_VARIABLE = Pattern.compile(
        "\\b(?:AuditEventLogRepository|FrontendAuditEventRepository|PermissionMatrixAuditEventRepository)\\s+(\\w+)");
    private static final Pattern USER_AUDIT_COLLECTION = Pattern.compile("\\b(?:get|set)AuditEvents\\s*\\(");
    private static final Pattern SIGNATURE_TRAIL_REWRITE = Pattern.compile(
        "\\bsetAuditLog\\s*\\(|\\bgetAuditLog\\s*\\(\\s*\\)\\s*\\.\\s*(?:remove\\w*|clear|set|replaceAll|sort|retainAll)"
            + "\\s*\\(");
    private static final Pattern ADJACENT_LITERALS = Pattern.compile("\"\\s*\\+\\s*\"");

    @Test
    void v382CreatesTheTriggersOnEveryAuditTable() throws IOException {
        String v382 = withoutSqlComments(Files.readString(MIGRATIONS.resolve(V382), StandardCharsets.UTF_8));
        for (String table : AUDIT_TABLES) {
            assertThat(v382).as("row trigger on " + table).containsPattern(Pattern.compile(
                "CREATE\\s+TRIGGER\\s+trg_audit_append_only\\s+BEFORE\\s+UPDATE\\s+OR\\s+DELETE\\s+ON\\s+"
                    + Pattern.quote(table) + "\\s+FOR\\s+EACH\\s+ROW", Pattern.CASE_INSENSITIVE));
            assertThat(v382).as("truncate trigger on " + table).containsPattern(Pattern.compile(
                "CREATE\\s+TRIGGER\\s+trg_audit_no_truncate\\s+BEFORE\\s+TRUNCATE\\s+ON\\s+"
                    + Pattern.quote(table) + "\\s+FOR\\s+EACH\\s+STATEMENT", Pattern.CASE_INSENSITIVE));
        }
        assertThat(v382).as("the six-year floor in the purge function")
            .containsPattern("retention_years\\s*<\\s*6")
            .containsPattern("now\\(\\)\\s*-\\s*interval\\s*'6 years'");
        assertThat(v382).as("the purge exception holds only inside the function, which runs as its owner")
            .containsPattern("p\\.proowner\\s+INTO\\s+purge_owner")
            .containsPattern("rolname\\s*=\\s*current_user")
            .containsPattern("SECURITY\\s+DEFINER");
        assertThat(v382).as("UPDATE/DELETE/TRUNCATE revoked in the migration itself").containsPattern(Pattern.compile(
            "REVOKE\\s+UPDATE,\\s*DELETE,\\s*TRUNCATE\\s+ON\\s+support\\.audit_event_logs[^;]*FROM\\s+PUBLIC",
            Pattern.CASE_INSENSITIVE));

        String changelog = Files.readString(MIGRATIONS.resolve("changelog.xml"), StandardCharsets.UTF_8);
        assertThat(changelog).as("V382 registered as one statement (dollar-quoted bodies)").containsPattern(
            Pattern.compile("<sqlFile\\s+path=\"" + Pattern.quote(V382) + "\"[^>]*splitStatements=\"false\"",
                Pattern.DOTALL));
    }

    @Test
    void noOtherMigrationDropsOrDisablesTheAuditTriggers() throws IOException {
        List<String> sites = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(p -> p.getFileName().toString().matches("V\\d+__.*\\.sql")).toList()) {
                String name = file.getFileName().toString();
                if (name.equals(V382) || REVIEWED_MIGRATIONS.contains(name)) {
                    continue;
                }
                scanned++;
                String sql = withoutSqlComments(Files.readString(file, StandardCharsets.UTF_8));
                for (Pattern bypass : MIGRATION_BYPASSES) {
                    Matcher m = bypass.matcher(sql);
                    if (m.find()) {
                        sites.add(name + ": " + m.group().replaceAll("\\s+", " "));
                    }
                }
            }
        }
        assertThat(scanned).isGreaterThan(300);
        assertThat(sites)
            .as("migrations that drop, disable or redefine the audit append-only triggers; the audit tables are "
                + "append-only (V382). A reviewed correction goes on REVIEWED_MIGRATIONS with its reason")
            .isEmpty();
    }

    @Test
    void theAuditRepositoriesDeclareNoModifyingOrDeleteMethod() {
        List<String> declared = new ArrayList<>();
        for (Class<?> repository : List.of(AuditEventLogRepository.class, FrontendAuditEventRepository.class,
                PermissionMatrixAuditEventRepository.class)) {
            for (Method method : repository.getDeclaredMethods()) {
                String name = method.getName().toLowerCase(Locale.ROOT);
                if (method.isAnnotationPresent(Modifying.class) || name.startsWith("delete")
                        || name.startsWith("remove") || name.startsWith("update")) {
                    declared.add(repository.getSimpleName() + "." + method.getName());
                }
            }
        }
        assertThat(declared).as("audit repository methods that would change or remove audit rows").isEmpty();
    }

    @Test
    void noApplicationCodeChangesOrRemovesAuditRows() throws IOException {
        List<String> sites = new ArrayList<>();
        Set<String> purgeCallers = new LinkedHashSet<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                scanned++;
                String name = file.getFileName().toString();
                String src = ADJACENT_LITERALS.matcher(
                    SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8))).replaceAll("");

                addSites(sites, name, SQL_WRITE_TO_AUDIT_TABLE.matcher(src));
                addSites(sites, name, JPQL_WRITE_TO_AUDIT_ENTITY.matcher(src));
                if (!name.equals("User.java")) {
                    addSites(sites, name, USER_AUDIT_COLLECTION.matcher(src));
                }
                addSites(sites, name, SIGNATURE_TRAIL_REWRITE.matcher(src));

                Matcher variable = AUDIT_REPOSITORY_VARIABLE.matcher(src);
                Set<String> variables = new LinkedHashSet<>();
                while (variable.find()) {
                    variables.add(variable.group(1));
                }
                for (String v : variables) {
                    addSites(sites, name, Pattern.compile("\\b" + Pattern.quote(v)
                        + "\\s*\\.\\s*(?:delete|remove|update)\\w*\\s*\\(").matcher(src));
                }
                if (src.contains("purge_expired_audit_rows")) {
                    purgeCallers.add(name);
                }
            }
        }
        assertThat(scanned).isGreaterThan(1000);
        assertThat(sites).as("application code that would change or remove audit rows (V382: append-only; the "
            + "retention purge is support.purge_expired_audit_rows, called by " + PURGE_JOB + ")").isEmpty();
        assertThat(purgeCallers).as("the only caller of the retention function").containsExactly(PURGE_JOB);
    }

    private static void addSites(List<String> sites, String file, Matcher matcher) {
        while (matcher.find()) {
            sites.add(file + ": " + matcher.group().replaceAll("\\s+", " "));
        }
    }

    /** SQL with every {@code --} line comment removed (no migration puts {@code --} inside a literal). */
    private static String withoutSqlComments(String sql) {
        return sql.replaceAll("--[^\\n]*", "");
    }
}
