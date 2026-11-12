package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA CX-011 guard: Hibernate flushes INSERTs before UPDATEs and DELETEs. So a
 * method that releases a row from a partial unique index with a plain
 * {@code save()} or {@code delete()} and then builds the row that takes its slot
 * fails on PostgreSQL with a unique violation, every time. H2 tables are built
 * from the entities and carry no such index, so no H2 test can see it: every
 * repeat type and screen was refused as "this record already exists".
 *
 * <p>Two checks:
 * <ul>
 *   <li>every partial unique index in the migrations is listed below with the
 *       entity it guards, so a new one cannot arrive without this scan knowing
 *       it;</li>
 *   <li>for those entities, a method that writes a releasing setter or deletes
 *       and then calls {@code Entity.builder()} must flush between the two
 *       ({@code saveAndFlush} or {@code flush()}).</li>
 * </ul>
 * A heuristic over one method body: a release in a helper or a row built by a
 * mapper needs the integration test of that service. It catches the two shapes
 * this repo has had, the blood group and the organization subscription.
 */
@DisplayName("A row released from a partial unique index is flushed before its replacement is built")
class PartialUniqueReleaseFlushGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Path MIGRATIONS = Paths.get("src/main/resources/db/migration");

    /** Table of each partial unique index -> the entity that writes it. */
    private static final Map<String, String> ENTITY_OF_TABLE = Map.ofEntries(
        Map.entry("death_records", "DeathRecord"),
        Map.entry("death_disputes", "DeathDispute"),
        Map.entry("order_review_holds", "OrderReviewHold"),
        Map.entry("patient_blood_groups", "PatientBloodGroup"),
        Map.entry("transfer_orders", "TransferOrder"),
        Map.entry("patient_recalls", "PatientRecall"),
        Map.entry("panel_assignments", "PanelAssignment"),
        Map.entry("organization_subscriptions", "OrganizationSubscription"),
        Map.entry("program_enrollments", "ProgramEnrollment"),
        Map.entry("hospitals", "Hospital"),
        Map.entry("provider_verifications", "ProviderVerification"),
        Map.entry("lab_results", "LabResult"),
        Map.entry("isolation_precautions", "IsolationPrecaution"),
        Map.entry("stock_lots", "StockLot"),
        Map.entry("users", "User"),
        Map.entry("dispenses", "Dispense"),
        Map.entry("user_mfa_enrollments", "UserMfaEnrollment"),
        Map.entry("patient_hospital_registrations", "PatientHospitalRegistration"),
        Map.entry("admissions", "Admission"),
        Map.entry("medication_catalog_items", "MedicationCatalogItem"),
        Map.entry("encounters", "Encounter"),
        Map.entry("user_role_hospital_assignment", "UserRoleHospitalAssignment"),
        Map.entry("billing_invoices", "BillingInvoice"),
        // QA D1613 (V288): one live claim per dispense; a rejection releases the slot.
        Map.entry("pharmacy_claims", "PharmacyClaim"),
        Map.entry("smart_phrases", "SmartPhrase"));

    /** Indexes whose rows no setter can release: the slot is the row's own key, not its state. */
    private static final Set<String> KEY_ONLY_ENTITIES =
        Set.of("Hospital", "User", "LabResult", "StockLot", "Encounter", "BillingInvoice", "MedicationCatalogItem");

    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow();

    private static final Pattern PARTIAL_UNIQUE = Pattern.compile(
        "CREATE\\s+UNIQUE\\s+INDEX[^;]*?\\bON\\s+(?:[a-z_]+\\.)?([a-z_]+)\\s*\\([^;]*?\\bWHERE\\b[^;]*?;",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern RELEASE = Pattern.compile(
        "\\.set(?:Superseded|Status|Active|IsActive|EndedAt|Current)\\(|[Rr]epository\\.delete(?:All)?\\(");
    private static final Pattern FLUSH = Pattern.compile("saveAndFlush\\(|\\.flush\\(\\)");
    /** A method's header: a modifier, a return type, a name, a parameter list and an opening brace. */
    private static final Pattern METHOD = Pattern.compile(
        "(?:public|private|protected)\\s+(?:static\\s+)?[\\w<>\\[\\], .?]+\\s+(\\w+)\\s*\\([^)]*\\)[^;{]*\\{");

    @Test
    @DisplayName("every partial unique index of the migrations is known to this scan")
    void everyPartialUniqueIndexIsKnown() throws IOException {
        Set<String> tables = new TreeSet<>();
        try (Stream<Path> files = Files.list(MIGRATIONS)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".sql")).toList()) {
                Matcher index = PARTIAL_UNIQUE.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (index.find()) {
                    tables.add(index.group(1).toLowerCase(Locale.ROOT));
                }
            }
        }
        assertThat(tables).as("the scan must find the indexes it is about").contains("patient_blood_groups", "panel_assignments");
        assertThat(ENTITY_OF_TABLE.keySet())
            .as("a new partial unique index: name its entity above, so its writers are scanned (CX-011)")
            .containsAll(tables);
    }

    @Test
    @DisplayName("no method releases a slot and builds its replacement without a flush between")
    void releaseIsFlushedBeforeTheReplacement() throws IOException {
        Map<String, Integer> sites = new TreeMap<>();
        List<String> where = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("service"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                scan(MAIN.relativize(file).toString().replace('\\', '/'), source, sites, where);
            }
        }
        assertThat(GuardAllowlist.overAllowance(sites, ALLOWED))
            .as("flush the released row before building its replacement (CX-011): " + where).isEmpty();
        assertThat(GuardAllowlist.stale(sites, ALLOWED)).as("the fix landed: delete its allowance").isEmpty();
    }

    @Test
    @DisplayName("the scan sees the shape it is for")
    void scanSeesTheShape() {
        Map<String, Integer> sites = new TreeMap<>();
        scan("probe.java", """
            class P {
                public void repeat() {
                    current.setSuperseded(true);
                    repository.save(current);
                    repository.save(PatientBloodGroup.builder().build());
                }
                public void flushed() {
                    current.setSuperseded(true);
                    repository.saveAndFlush(current);
                    repository.save(PatientBloodGroup.builder().build());
                }
                public void builtFirst() {
                    repository.save(PatientBloodGroup.builder().build());
                    other.setStatus(DONE);
                }
            }
            """, sites, new ArrayList<>());
        assertThat(sites).containsOnlyKeys("probe.java#PatientBloodGroup");
        assertThat(sites.get("probe.java#PatientBloodGroup")).isOne();
    }

    private static void scan(String file, String source, Map<String, Integer> sites, List<String> where) {
        Matcher method = METHOD.matcher(source);
        while (method.find()) {
            int open = method.end() - 1;
            String body = source.substring(open, SourceScan.closingBrace(source, open) + 1);
            for (String entity : ENTITY_OF_TABLE.values()) {
                if (KEY_ONLY_ENTITIES.contains(entity)) {
                    continue;
                }
                int built = body.indexOf(entity + ".builder()");
                if (built < 0) {
                    continue;
                }
                String before = body.substring(0, built);
                if (RELEASE.matcher(before).find() && !FLUSH.matcher(before).find()) {
                    sites.merge(file + "#" + entity, 1, Integer::sum);
                    where.add(file + ":" + SourceScan.lineOf(source, open) + " " + method.group(1));
                }
            }
        }
    }
}
