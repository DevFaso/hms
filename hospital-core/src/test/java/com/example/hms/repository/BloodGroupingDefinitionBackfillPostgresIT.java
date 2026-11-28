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

/**
 * QA D3905 (V408): the blood-group definitions of the catalogue are flagged, so the blood-bank grouping closes their
 * order; a screen alone, a crossmatch, a culture or any other test is left as it was.
 */
@Testcontainers
class BloodGroupingDefinitionBackfillPostgresIT {
    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_grouping_backfill").withUsername("hms_test_user").withPassword("hms_test_pass");

    record Definition(String code, String name, String loinc, boolean culture, boolean grouping) { }

    @Test
    void reviewedGroupingDefinitionsAreFlaggedAndNothingElse() throws Exception {
        List<Definition> definitions = List.of(
            new Definition("TWP-GS-RH", "Groupe sanguin et rhésus", null, false, true),
            new Definition("GS5", "Groupe sanguin ABO", null, false, true),
            new Definition("GRP", "Groupage ABO-Rh", null, false, true),
            new Definition("ABO", "ABO and Rh", "882-1", false, true),
            new Definition("BT-EN", "Blood group and Rh", null, false, true),
            new Definition("RAI", "Recherche d'agglutinines irrégulières", null, false, false),
            new Definition("XM", "Épreuve de compatibilité", null, false, false),
            new Definition("TWP-CREA", "Créatinine", null, false, false),
            new Definition("TWP-ECBU", "ECBU", null, true, false),
            new Definition("DEV-CBC", "Complete Blood Count", null, false, false),
            // A grouping name that also names a screen, an antibody, HLA, Coombs or a crossmatch is not flagged.
            new Definition("GS-RAI", "Groupe sanguin + RAI", null, false, false),
            new Definition("GRP-HLA", "Groupage HLA", null, false, false),
            new Definition("BG-AS", "Blood group antibody screen", null, false, false),
            new Definition("GS-AGG", "Groupe sanguin et agglutinines irrégulières", null, false, false),
            new Definition("GS-AC", "Groupe sanguin, anticorps anti-érythrocytaires", null, false, false),
            new Definition("BT-COOMBS", "Blood type and Coombs test", null, false, false),
            new Definition("GS-XM", "Groupage et épreuve de compatibilité", null, false, false),
            new Definition("GS-CAPS", "GROUPAGE SANGUIN - R.A.I. / ANTICORPS", null, false, false),
            // ...whatever its code or LOINC.
            new Definition("GSRH", "Groupe sanguin et RAI", null, false, false),
            new Definition("ABO-AS", "ABO, Rh and antibody screen", "882-1", false, false));
        try (var connection = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            try (var schema = connection.createStatement()) {
                schema.execute("CREATE SCHEMA lab");
                schema.execute("CREATE TABLE lab.lab_test_definitions (id uuid PRIMARY KEY, test_code text, name text, "
                    + "loinc_code text, culture boolean NOT NULL DEFAULT false)");
            }
            try (var insert = connection.prepareStatement("INSERT INTO lab.lab_test_definitions VALUES (?, ?, ?, ?, ?)")) {
                for (Definition definition : definitions) {
                    insert.setObject(1, UUID.randomUUID());
                    insert.setString(2, definition.code());
                    insert.setString(3, definition.name());
                    insert.setString(4, definition.loinc());
                    insert.setBoolean(5, definition.culture());
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            String migration = Files.readString(Path.of("src/main/resources/db/migration/V408__lab_blood_grouping_definition_flag.sql"));
            try (var statement = connection.createStatement()) {
                statement.execute(migration);
            }
            List<String> flagged = new ArrayList<>();
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT test_code, blood_grouping FROM lab.lab_test_definitions ORDER BY test_code")) {
                while (rows.next()) {
                    String code = rows.getString(1);
                    boolean actual = rows.getBoolean(2);
                    assertThat(actual).as(code)
                        .isEqualTo(definitions.stream().filter(d -> d.code().equals(code)).findFirst().orElseThrow().grouping());
                    if (actual) flagged.add(code);
                }
            }
            assertThat(flagged).containsExactly("ABO", "BT-EN", "GRP", "GS5", "TWP-GS-RH");
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT is_nullable, column_default FROM information_schema.columns "
                     + "WHERE table_schema = 'lab' AND table_name = 'lab_test_definitions' AND column_name = 'blood_grouping'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("NO");
                assertThat(rows.getString(2)).contains("false");
            }
        }
    }
}
