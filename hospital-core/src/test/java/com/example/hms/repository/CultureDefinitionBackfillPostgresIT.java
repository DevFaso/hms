package com.example.hms.repository;

import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class CultureDefinitionBackfillPostgresIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_culture_backfill").withUsername("hms_test_user").withPassword("hms_test_pass");

    record Definition(String code, String name, String category, boolean culture) { }

    @Test
    void approvedBackfillFlagsCultureDefinitionsAndKeepsTheExistingNonCultureSeedsFalse() throws Exception {
        List<Definition> definitions = List.of(
            new Definition("DEV-CBC", "Complete Blood Count", "Routine diagnostics", false),
            new Definition("DEV-HBA1C", "Hemoglobin A1C", "Diabetes monitoring", false),
            new Definition("DEV-LIPID", "Lipid Panel", "Cholesterol panel", false),
            new Definition("CREAT", "Créatinine", "Biochimie", false),
            new Definition("PREL-SANG", "Prélèvement sanguin", "Biochimie", false),
            new Definition("ECBU", "ECBU", "Analyses urinaires", true),
            new Definition("BLOOD-CULTURE", "HÉMOCULTURE", "Bilan infectieux", true),
            new Definition("COPROCULTURE", "Examen des selles", "Autre", true),
            new Definition("PV", "Prélèvement vaginal / antibiogramme", "Autre", true),
            new Definition("MICRO", "Examen bactériologique", "MICROBIOLOGY", false),
            new Definition("BACT", "Examen bactérien", "Bactériologie", false),
            new Definition("TWP-ECBU", "ECBU", "Microbiologie", true),
            new Definition("TWP-CREA", "Créatinine", "Biochimie", false),
            new Definition("TWP-GS-RH", "Groupe sanguin et rhésus", "Hématologie", false),
            new Definition("MICRO-FR", "Examen microbiologique", "  MICROBIOLOGIE  ", false),
            new Definition("HEMO-COUNT", "Hemogram", "Microbiology", false),
            new Definition("HEMO", "Creatinine", "Microbiology", false),
            new Definition("MISC-CREA", "Creatinine", "Microbiology", false),
            new Definition("SENS-EN", "Antibiogram", "Other", true));
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var schema = connection.createStatement()) {
                schema.execute("CREATE SCHEMA lab");
                schema.execute("CREATE TABLE lab.lab_test_definitions (id uuid PRIMARY KEY, test_code text, name text, category text)");
            }
            try (var insert = connection.prepareStatement("INSERT INTO lab.lab_test_definitions VALUES (?, ?, ?, ?)")) {
                for (Definition definition : definitions) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, definition.code());
                    insert.setString(3, definition.name());
                    insert.setString(4, definition.category());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            try (var migration = connection.createStatement()) {
                migration.execute(Files.readString(Path.of("src/main/resources/db/migration/V394__lab_culture_definition_flag.sql")));
            }
            List<String> flagged = new ArrayList<>();
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT test_code, culture FROM lab.lab_test_definitions ORDER BY test_code")) {
                while (rows.next()) {
                    String code = rows.getString(1);
                    boolean actual = rows.getBoolean(2);
                    assertThat(actual).as(code).isEqualTo(definitions.stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow().culture());
                    if (actual) flagged.add(code);
                }
            }
            assertThat(flagged).containsExactly("BLOOD-CULTURE", "COPROCULTURE", "ECBU", "PV", "SENS-EN", "TWP-ECBU");
            System.out.println("V394|fictional-clinic flags|" + String.join(",", flagged));
            System.out.println("V394|existing synthetic seed definitions unchanged|DEV-CBC,DEV-HBA1C,DEV-LIPID");
            try (var query = connection.createStatement(); var rows = query.executeQuery("SELECT is_nullable, column_default FROM information_schema.columns WHERE table_schema = 'lab' AND table_name = 'lab_test_definitions' AND column_name = 'culture'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("NO");
                assertThat(rows.getString(2)).contains("false");
            }
        }
    }
}
