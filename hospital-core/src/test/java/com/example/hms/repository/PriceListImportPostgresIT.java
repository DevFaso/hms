package com.example.hms.repository;

import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListImportResult;
import com.example.hms.enums.PriceListImportRowOutcome;
import com.example.hms.exception.ClientSafeAccessDeniedException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.AuditEventRequestDTO;
import com.example.hms.payload.dto.billing.PriceListImportReportDTO;
import com.example.hms.payload.dto.billing.PriceListItemDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListPriceDTO;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.billing.PriceListImportService;
import com.example.hms.service.billing.PriceListLinks;
import com.example.hms.service.billing.PriceListService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.example.hms.testsupport.RandomIds.withoutRandomIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 4 on real PostgreSQL built by Liquibase (V302-V304): the fictional test clinic's grid
 * ({@code /billing/tarifs-test.csv}, FICTIONAL data, see README-FICTIF.txt next to it) is checked, applied, recognised
 * when applied again, exported and re-imported unchanged; a file with one bad row applies nothing; a changed price is a
 * new version from the row's date; a hospital imports into its own grid only. Each call runs in its own transaction,
 * as the API would. Foreign keys are off for the seed: the hospital rows say nothing here.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@org.junit.jupiter.api.Timeout(180)
@DisplayName("Price list CSV import on PostgreSQL")
class PriceListImportPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_price_import")
        .withUsername("hms_test_user")
        .withPassword("hms_test_pass");

    @DynamicPropertySource
    static void realDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.liquibase.enabled", () -> "true");
        registry.add("spring.sql.init.mode", () -> "never");
    }

    /** Before the grid's 01/12/2026 start, as README A-5 requires. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC);
    private static final String FIXTURE = "tarifs-test.csv";
    private static final String HEADER = "code;libelle;categorie;prix;date_effet;liaison_type;liaison_cle";

    @Autowired private PriceListItemRepository items;
    @Autowired private PriceListPriceRepository prices;
    @Autowired private PriceListImportRepository imports;
    @Autowired private InvoiceItemRepository invoiceLines;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final LabTestDefinitionRepository labTests = mock(LabTestDefinitionRepository.class);
    private final TreatmentRepository treatments = mock(TreatmentRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private PriceListService service;
    private PriceListImportService importer;
    private TransactionTemplate tx;
    private UUID hospitalA;
    private UUID hospitalB;
    private UUID actor;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        jdbc.execute("ALTER TABLE billing.price_list_items DISABLE TRIGGER ALL");
        jdbc.execute("ALTER TABLE billing.price_list_prices DISABLE TRIGGER ALL");
        jdbc.execute("ALTER TABLE billing.price_list_imports DISABLE TRIGGER ALL");
        hospitalA = UUID.randomUUID();
        hospitalB = UUID.randomUUID();
        actor = UUID.randomUUID();
        PriceListLinks links = new PriceListLinks(labTests, treatments);
        service = new PriceListService(items, prices, links, invoiceLines, labTests, treatments,
            mock(UserRepository.class), mock(com.example.hms.repository.HospitalRepository.class), roles, scope, audit,
            CLOCK, "Africa/Ouagadougou");
        importer = new PriceListImportService(service, items, prices, imports, links, labTests, audit, jdbc, "1s");
        tx = new TransactionTemplate(transactions);
        actAt(hospitalA);
        when(roles.getCurrentUserId()).thenReturn(actor);
        when(roles.canMaintainPriceList(eq(actor), any())).thenReturn(true);
        when(roles.canReadPriceList(eq(actor), any())).thenReturn(true);
        // The TWP lab tests exist in hospital A's lab configuration (README A-1); B has none.
        when(labTests.findLiveGlobalByCode(anyString())).thenReturn(List.of());
        when(labTests.findLiveByCodeAtHospital(anyString(), any())).thenReturn(List.of());
        when(labTests.findLiveByCodeAtHospital(org.mockito.ArgumentMatchers.startsWith("TWP-"), eq(hospitalA)))
            .thenAnswer(call -> List.of(labTest(call.getArgument(0), hospitalA)));
        when(labTests.findLiveByCodeAtHospital(eq("TWP-INCONNU"), any())).thenReturn(List.of());
        // The export names a linked test by its code.
        when(labTests.findAllById(any())).thenAnswer(call -> {
            Iterable<UUID> ids = call.getArgument(0);
            Map<UUID, String> codes = new java.util.HashMap<>();
            for (String row : fixtureLines()) {
                String key = row.split(";", -1)[6];
                if (key.startsWith("TWP-")) {
                    codes.put(idOf(key), key);
                }
            }
            return StreamSupport.stream(ids.spliterator(), false).filter(codes::containsKey)
                .map(id -> labTest(codes.get(id), hospitalA)).toList();
        });
    }

    private void actAt(UUID hospitalId) {
        when(scope.requirePinned()).thenReturn(hospitalId);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private static UUID idOf(String testCode) {
        return UUID.nameUUIDFromBytes(testCode.getBytes(StandardCharsets.UTF_8));
    }

    private static LabTestDefinition labTest(String code, UUID hospitalId) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(idOf(code));
        definition.setTestCode(code);
        definition.setName(code);
        definition.setActive(true);
        Hospital hospital = new Hospital();
        hospital.setId(hospitalId);
        definition.setHospital(hospital);
        return definition;
    }

    private <T> T inTx(Callable<T> call) {
        return tx.execute(status -> {
            try {
                return call.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private static byte[] fixture() {
        try (InputStream in = Objects.requireNonNull(
                PriceListImportPostgresIT.class.getResourceAsStream("/billing/" + FIXTURE))) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> fixtureLines() {
        return new String(fixture(), StandardCharsets.UTF_8).lines().skip(1).filter(l -> !l.isBlank()).toList();
    }

    private PriceListImportReportDTO check(byte[] file, String name) {
        return inTx(() -> importer.importFile(file, name, true, false));
    }

    private PriceListImportReportDTO apply(byte[] file, String name) {
        return inTx(() -> importer.importFile(file, name, false, false));
    }

    private int itemCount(UUID hospitalId) {
        return jdbc.queryForObject("SELECT count(*) FROM billing.price_list_items WHERE hospital_id = ?",
            Integer.class, hospitalId);
    }

    private int versionCount(UUID hospitalId) {
        return jdbc.queryForObject("SELECT count(*) FROM billing.price_list_prices WHERE hospital_id = ?",
            Integer.class, hospitalId);
    }

    private Map<String, PriceListItemDTO> gridOf() {
        return inTx(() -> service.list(null, true)).stream()
            .collect(Collectors.toMap(PriceListItemDTO::getCode, Function.identity()));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ───────────────────────── the whole grid ─────────────────────────

    @Test
    @DisplayName("the test clinic's 60 rows: a dry run writes nothing, the apply writes 60, the same file again nothing")
    void dryRunThenApplyThenTheSameFileAgain() {
        PriceListImportReportDTO dry = check(fixture(), FIXTURE);
        assertThat(dry.getResult()).isEqualTo(PriceListImportResult.DRY_RUN);
        assertThat(dry.getRefused()).as(dry.getRows().stream()
            .filter(r -> r.getOutcome() == PriceListImportRowOutcome.REFUSED)
            .map(r -> r.getLine() + " " + r.getRefusals().get(0).getMessage()).toList().toString()).isZero();
        assertThat(dry.getNewItems()).isEqualTo(60);
        assertThat(dry.getRows()).hasSize(60);
        assertThat(dry.getRows().get(0).getLine()).isEqualTo(2);
        assertThat(itemCount(hospitalA)).as("a dry run changes nothing").isZero();
        assertThat(versionCount(hospitalA)).isZero();

        PriceListImportReportDTO applied = apply(fixture(), FIXTURE);
        assertThat(applied.getResult()).isEqualTo(PriceListImportResult.APPLIED);
        assertThat(applied.getNewItems()).isEqualTo(60);
        assertThat(itemCount(hospitalA)).isEqualTo(60);
        assertThat(versionCount(hospitalA)).isEqualTo(60);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM billing.price_list_prices
            WHERE hospital_id = ? AND import_id = ? AND change_reason = 'Import tarifs-test.csv'
              AND effective_from = DATE '2026-12-01'
            """, Integer.class, hospitalA, applied.getImportId())).isEqualTo(60);
        Map<String, PriceListItemDTO> grid = gridOf();
        assertThat(grid.get("LAB-NFS").getLinkKey()).isEqualTo(idOf("TWP-NFS").toString());
        assertThat(grid.get("IMG-ECHO-OBS").getLinkKey()).isEqualTo("ULTRASOUND:echographie obstetricale");
        assertThat(grid.get("ACT-HERNIE").getNextPrice().getUnitPrice()).isEqualByComparingTo("125000");

    }

    @Test
    @DisplayName("the same file applied again writes nothing; a dry run of it says so; one audit row")
    void theSameFileAgainWritesNothing() {
        PriceListImportReportDTO applied = apply(fixture(), FIXTURE);
        PriceListImportReportDTO again = apply(fixture(), "copie.csv");
        assertThat(again.getResult()).isEqualTo(PriceListImportResult.ALREADY_IMPORTED);
        assertThat(again.getImportId()).isEqualTo(applied.getImportId());
        assertThat(itemCount(hospitalA)).isEqualTo(60);
        assertThat(versionCount(hospitalA)).isEqualTo(60);
        // A dry run of the applied file says so up front.
        PriceListImportReportDTO dryAgain = check(fixture(), FIXTURE);
        assertThat(dryAgain.isAlreadyImported()).isTrue();
        assertThat(dryAgain.getUnchanged()).isEqualTo(60);

        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM billing.price_list_imports WHERE hospital_id = ? AND status = 'APPLIED'",
            Integer.class, hospitalA)).isEqualTo(1);
        ArgumentCaptor<AuditEventRequestDTO> logged = ArgumentCaptor.forClass(AuditEventRequestDTO.class);
        verify(audit, times(1)).logEvent(logged.capture());
        assertThat(logged.getValue().getEventType()).isEqualTo(AuditEventType.PRICE_LIST_IMPORTED);
        assertThat(logged.getValue().getResourceId()).isEqualTo(applied.getImportId().toString());
        assertThat(withoutRandomIds(logged.getValue().getEventDescription())).doesNotContain("Consultation", "2000");
    }

    @Test
    @DisplayName("the template filled in Excel FR (Windows-1252, BOM-less, '2 000', CRLF) imports with no refusal")
    void excelFrenchWindows1252() {
        String text = new String(fixture(), StandardCharsets.UTF_8).replace("\r\n", "\n")
            .replace(";2000;", ";2 000;").replace(";125000;", ";125\u00A0000;").replace("\n", "\r\n");
        byte[] cp1252 = text.getBytes(Charset.forName("windows-1252"));
        PriceListImportReportDTO dry = check(cp1252, "grille excel.csv");
        assertThat(dry.getRefused()).isZero();
        assertThat(apply(cp1252, "grille excel.csv").getNewItems()).isEqualTo(60);
        Map<String, PriceListItemDTO> grid = gridOf();
        assertThat(grid.get("IMG-ECHO-OBS").getLabel()).isEqualTo("Échographie obstétricale");
        assertThat(grid.get("CS-GEN").getNextPrice().getUnitPrice()).isEqualByComparingTo("2000");
        assertThat(grid.get("ACT-HERNIE").getNextPrice().getUnitPrice()).isEqualByComparingTo("125000");
    }

    // ───────────────────────── refusals ─────────────────────────

    @Test
    @DisplayName("README §6.1's five wrong rows: each refused with line, column and French reason; nothing applies")
    void wrongRowsAreRefusedAndNothingApplies() {
        String file = new String(fixture(), StandardCharsets.UTF_8).stripTrailing() + "\n"
            + "LAB-FAUX;Test avec prix décimal;LAB_TEST;2500,50;01/12/2026;;\n"
            + ";Ligne sans code;CONSULTATION;2000;01/12/2026;;\n"
            + "IMG-FAUX;Catégorie inconnue;RADIO;5000;01/12/2026;;\n"
            + "LAB-LIEN;Test lié inconnu;LAB_TEST;2000;01/12/2026;LAB_TEST_DEFINITION;TWP-INCONNU\n"
            + "=cmd|' /C calc'!A0;Injection de formule;OTHER_SERVICE;100;01/12/2026;;\n";
        PriceListImportReportDTO dry = check(utf8(file), "fautes.csv");
        assertThat(dry.getNewItems()).isEqualTo(60);
        assertThat(dry.getRefused()).isEqualTo(5);
        Map<Integer, PriceListImportReportDTO.Row> byLine = dry.getRows().stream()
            .collect(Collectors.toMap(PriceListImportReportDTO.Row::getLine, Function.identity()));
        assertRefused(byLine.get(62), "prix", "Saisissez le prix unitaire en francs CFA entiers, sans décimales.");
        assertRefused(byLine.get(63), "code", "Saisissez le code tarifaire.");
        assertRefused(byLine.get(64), "categorie", "Catégorie inconnue « RADIO ». Utilisez l’une de : CONSULTATION,"
            + " LAB_TEST, IMAGING, PROCEDURE, BED_DAY, TRANSFUSION, DELIVERY, OTHER_SERVICE.");
        assertRefused(byLine.get(65), "liaison_cle",
            "Aucun test de laboratoire TWP-INCONNU n’est disponible dans cet établissement.");
        assertRefused(byLine.get(66), "code", "Une cellule ne peut pas commencer par =, +, -, @ ou une tabulation : "
            + "un tableur l’exécuterait comme une formule.");

        PriceListImportReportDTO refused = apply(utf8(file), "fautes.csv");
        assertThat(refused.getResult()).isEqualTo(PriceListImportResult.REFUSED);
        assertThat(refused.getImportId()).isNull();
        assertThat(itemCount(hospitalA)).as("all or nothing").isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM billing.price_list_imports WHERE hospital_id = ? AND status = 'APPLIED'",
            Integer.class, hospitalA)).isZero();
        verify(audit, never()).logEvent(any());
    }

    @Test
    @DisplayName("the reasons follow the reader's language (English)")
    void reasonsInEnglish() {
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        PriceListImportReportDTO dry = check(utf8(HEADER + "\nLAB-FAUX;Test;LAB_TEST;2500,50;;;\n"), "x.csv");
        assertRefused(dry.getRows().get(0), "prix", "Enter the unit price in whole CFA francs, without decimals.");
    }

    private static void assertRefused(PriceListImportReportDTO.Row row, String column, String message) {
        assertThat(row.getOutcome()).isEqualTo(PriceListImportRowOutcome.REFUSED);
        assertThat(row.getRefusals()).extracting(PriceListImportReportDTO.Refusal::getColumn,
                PriceListImportReportDTO.Refusal::getMessage)
            .contains(org.assertj.core.groups.Tuple.tuple(column, message));
    }

    @Test
    @DisplayName("a duplicate code, a past date and a label that differs from the grid are refused")
    void otherRowRules() {
        apply(utf8(HEADER + "\nCS-GEN;Consultation générale;CONSULTATION;2000;;SYSTEM;CONSULTATION_DEFAULT\n"),
            "a.csv");
        PriceListImportReportDTO dry = check(utf8(HEADER + "\n"
            + "ADM-A;Carnet;OTHER_SERVICE;500;;;\n"
            + "adm-a;Carnet bis;OTHER_SERVICE;500;;;\n"
            + "ADM-B;Certificat;OTHER_SERVICE;500;01/01/2026;;\n"
            + "CS-GEN;Consultation;CONSULTATION;2500;01/01/2027;SYSTEM;CONSULTATION_DEFAULT\n"), "b.csv");
        assertThat(dry.getRows().get(0).getOutcome()).isEqualTo(PriceListImportRowOutcome.NEW_ITEM);
        assertRefused(dry.getRows().get(1), "code",
            "Le code ADM-A figure déjà à la ligne 2 de ce fichier : une seule ligne par code.");
        assertRefused(dry.getRows().get(2), "date_effet",
            "La date d’effet ne peut pas être dans le passé : un prix s’applique au plus tôt à partir d’aujourd’hui.");
        assertRefused(dry.getRows().get(3), "libelle", "Le libellé diffère de celui de la grille (« Consultation "
            + "générale »). L’import ne modifie que les prix : modifiez le libellé à l’écran, ou reprenez celui de la grille.");
        PriceListImportReportDTO taken = check(utf8(HEADER + "\n"
            + "CS-AUTRE;Autre consultation;CONSULTATION;2500;;SYSTEM;CONSULTATION_DEFAULT\n"), "c.csv");
        // QA D2070 (develop): the link is named in words, never by its stored key.
        PriceListImportReportDTO.Refusal linkTaken = taken.getRows().get(0).getRefusals().get(0);
        assertThat(linkTaken.getColumn()).isEqualTo("liaison_cle");
        assertThat(linkTaken.getMessage()).doesNotContain("CONSULTATION_DEFAULT")
            .endsWith("est déjà utilisé par l’article actif CS-GEN : un seul article actif par lien.");
        PriceListImportReportDTO twice = check(utf8(HEADER + "\n"
            + "HOSP-A;Journée A;BED_DAY;5000;;SYSTEM;BED_DAY\n"
            + "HOSP-B;Journée B;BED_DAY;6000;;SYSTEM;bed_day\n"), "d.csv");
        assertRefused(twice.getRows().get(1), "liaison_cle",
            "Le lien bed_day figure déjà à la ligne 2 de ce fichier : un seul article par lien.");
    }

    // ───────────────────────── prices ─────────────────────────

    @Test
    @DisplayName("an equal price is unchanged; a changed one is a new version from its date, 'Import <file>'")
    void unchangedAndNewPrice() {
        apply(fixture(), FIXTURE);
        PriceListImportReportDTO applied = apply(utf8(HEADER + "\n"
            + "CS-GEN;Consultation générale (médecin généraliste);CONSULTATION;2 500;01/01/2027;SYSTEM;CONSULTATION_DEFAULT\n"
            + "CS-PED;Consultation spécialisée pédiatrie;CONSULTATION;5000;01/12/2026;;\n"), "hausse 2027.csv");
        assertThat(applied.getResult()).isEqualTo(PriceListImportResult.APPLIED);
        assertThat(applied.getNewPrices()).isEqualTo(1);
        assertThat(applied.getUnchanged()).isEqualTo(1);
        assertThat(applied.getRows().get(0).getPreviousPrice()).isEqualByComparingTo("2000");

        Map<String, PriceListItemDTO> grid = gridOf();
        PriceListItemDTO gen = inTx(() -> service.get(grid.get("CS-GEN").getId()));
        assertThat(gen.getHistory()).extracting(PriceListPriceDTO::getUnitPrice)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("2500"), new BigDecimal("2000"));
        assertThat(gen.getHistory().get(0).getEffectiveFrom()).isEqualTo(LocalDate.of(2027, 1, 1));
        assertThat(gen.getHistory().get(0).getChangeReason()).isEqualTo("Import hausse 2027.csv");
        assertThat(gen.getHistory().get(1).getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(versionCount(hospitalA)).isEqualTo(61);
    }

    @Test
    @DisplayName("a price already planned on the row's date is replaced only with 'replace planned prices'")
    void aPlannedPriceOnTheSameDateNeedsTheOption() {
        apply(fixture(), FIXTURE);
        byte[] fix = utf8(HEADER + "\nCS-GEN;Consultation générale (médecin généraliste);CONSULTATION;2200;"
            + "01/12/2026;SYSTEM;CONSULTATION_DEFAULT\n");
        PriceListImportReportDTO without = check(fix, "correction.csv");
        PriceListImportReportDTO.Refusal planned = without.getRows().get(0).getRefusals().get(0);
        assertThat(planned.getColumn()).isEqualTo("date_effet");
        assertThat(planned.getMessage()).startsWith("Un prix de 2").contains("F CFA est déjà prévu à partir du 1")
            .endsWith("Cochez « Remplacer les prix prévus à la même date » pour le remplacer, ou choisissez une "
                + "autre date d’effet.");
        PriceListImportReportDTO with = inTx(() -> importer.importFile(fix, "correction.csv", false, true));
        assertThat(with.getNewPrices()).isEqualTo(1);
        PriceListItemDTO gen = gridOf().get("CS-GEN");
        assertThat(gen.getNextPrice().getUnitPrice()).isEqualByComparingTo("2200");
    }

    // ───────────────────────── export ─────────────────────────

    @Test
    @DisplayName("export, then re-import: 0 new, 60 unchanged; lab links by test code; formula cells escaped")
    void exportRoundTrip() {
        apply(fixture(), FIXTURE);
        byte[] exported = inTx(() -> importer.export());
        String text = new String(exported, StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF" + HEADER + "\r\n")
            .contains("LAB-NFS;Numération formule sanguine (NFS);LAB_TEST;3500;01/12/2026;LAB_TEST_DEFINITION;TWP-NFS");

        PriceListImportReportDTO dry = check(exported, "export.csv");
        assertThat(dry.getNewItems()).isZero();
        assertThat(dry.getNewPrices()).isZero();
        assertThat(dry.getRefused()).isZero();
        assertThat(dry.getUnchanged()).isEqualTo(60);
        PriceListImportReportDTO applied = apply(exported, "export.csv");
        assertThat(applied.getResult()).isEqualTo(PriceListImportResult.APPLIED);
        assertThat(versionCount(hospitalA)).isEqualTo(60);

    }

    @Test
    @DisplayName("review #1206: a formula-looking label is refused on the screen; an older one exports escaped and "
        + "re-imports unchanged")
    void aFormulaLookingLabelRoundTrips() {
        // The screen applies the import's own rule (CsvCells.isFormula) to a label.
        PriceListItemRequestDTO formula = PriceListItemRequestDTO.builder().code("ADM-F").label("=HYPERLINK(\"x\")")
            .category(PriceListCategory.OTHER_SERVICE).unitPrice(new BigDecimal("100")).build();
        assertThatThrownBy(() -> inTx(() -> service.create(formula)))
            .isInstanceOfSatisfying(com.example.hms.exception.FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("label");
                assertThat(e.getMessage()).startsWith("Le libellé ne peut pas commencer par =, +, -, @");
            });
        // A label typed before the rule ("- Pansement") is still in a grid: written behind an apostrophe...
        inTx(() -> service.create(PriceListItemRequestDTO.builder().code("ACT-PANS").label("Pansement")
            .category(PriceListCategory.PROCEDURE).unitPrice(new BigDecimal("1000")).build()));
        jdbc.update("UPDATE billing.price_list_items SET label = '- Pansement' WHERE hospital_id = ? AND code = ?",
            hospitalA, "ACT-PANS");
        byte[] exported = inTx(() -> importer.export());
        assertThat(new String(exported, StandardCharsets.UTF_8)).contains("ACT-PANS;'- Pansement;PROCEDURE;1000;");
        // ...and read back as stored: unchanged, not a label that differs nor a formula.
        PriceListImportReportDTO back = check(exported, "export.csv");
        assertThat(back.getRefused()).as(String.valueOf(back.getRows().get(0).getRefusals().stream()
            .map(PriceListImportReportDTO.Refusal::getMessage).toList())).isZero();
        assertThat(back.getUnchanged()).isEqualTo(1);
        // A NEW item with such a label is refused at import, escaped or not.
        PriceListImportReportDTO fresh = check(utf8(HEADER + "\nACT-NEW;'- Nouveau;PROCEDURE;1000;;;\n"), "n.csv");
        assertRefused(fresh.getRows().get(0), "libelle", "Une cellule ne peut pas commencer par =, +, -, @ ou une "
            + "tabulation : un tableur l’exécuterait comme une formule.");
    }

    @Test
    @DisplayName("review #1206: a linked lab test deactivated since the export still re-imports unchanged")
    void aDeactivatedLabTestStillRoundTrips() {
        apply(fixture(), FIXTURE);
        byte[] exported = inTx(() -> importer.export());
        // TWP-NFS is deactivated: its code no longer resolves to a live test at A.
        when(labTests.findLiveByCodeAtHospital(eq("TWP-NFS"), any())).thenReturn(List.of());
        PriceListImportReportDTO dry = check(exported, "export.csv");
        assertThat(dry.getRefused()).isZero();
        assertThat(dry.getNewItems()).isZero();
        assertThat(dry.getUnchanged()).isEqualTo(60);
        // A NEW item linked to it is still refused: only an item's own stored link is taken as it was.
        PriceListImportReportDTO fresh = check(utf8(HEADER
            + "\nLAB-NFS2;NFS bis;LAB_TEST;3500;;LAB_TEST_DEFINITION;TWP-NFS\n"), "n.csv");
        assertThat(fresh.getRows().get(0).getOutcome()).isEqualTo(PriceListImportRowOutcome.REFUSED);
    }

    @Test
    @DisplayName("review #1206: an item locked by someone else refuses the apply with 'try again', never waits forever")
    void aLockedItemRefusesTheApplyWithTryAgain() throws Exception {
        apply(fixture(), FIXTURE);
        byte[] change = utf8(HEADER + "\nCS-GEN;Consultation générale (médecin généraliste);CONSULTATION;2500;"
            + "01/01/2027;SYSTEM;CONSULTATION_DEFAULT\n");
        java.util.concurrent.CountDownLatch locked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.ExecutorService other = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            java.util.concurrent.Future<?> holder = other.submit(() -> tx.executeWithoutResult(status -> {
                jdbc.queryForList("SELECT id FROM billing.price_list_items WHERE hospital_id = ? AND code = 'CS-GEN' "
                    + "FOR UPDATE", hospitalA);
                locked.countDown();
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            assertThatThrownBy(() -> apply(change, "hausse.csv"))
                .isInstanceOf(com.example.hms.exception.BusinessException.class)
                .hasMessageContaining("Réessayez dans un instant");
            assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(10));
            release.countDown();
            holder.get(30, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            other.shutdownNow();
        }
        assertThat(versionCount(hospitalA)).as("nothing applied").isEqualTo(60);
        // Once the lock is gone, the same file applies.
        assertThat(apply(change, "hausse.csv").getNewPrices()).isEqualTo(1);
    }

    /**
     * The portal's nginx gives an API call 120 s before it gives up (hospital-portal/nginx.conf), so a
     * 5 000-row import must finish well inside that. 90 s keeps a 30 s margin under the proxy while
     * leaving room for shared CI runners, where the same import measured 67-70 s on 2026-11-24/25.
     */
    private static final java.time.Duration IMPORT_BUDGET = java.time.Duration.ofSeconds(90);

    @Test
    @DisplayName("review #1206: 5 000 rows (the maximum) apply, then re-price, within the time budget")
    void theMaximumFileAppliesWithinBudget() {
        StringBuilder grid = new StringBuilder(HEADER).append('\n');
        StringBuilder raised = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < com.example.hms.service.billing.PriceListCsv.MAX_ROWS; i++) {
            String code = String.format(java.util.Locale.ROOT, "ART-%05d", i);
            grid.append(code).append(";Article ").append(i).append(";OTHER_SERVICE;").append(100 + i)
                .append(";01/12/2026;;\n");
            raised.append(code).append(";Article ").append(i).append(";OTHER_SERVICE;").append(200 + i)
                .append(";01/01/2027;;\n");
        }
        long started = System.nanoTime();
        PriceListImportReportDTO first = apply(utf8(grid.toString()), "grand.csv");
        java.time.Duration firstTook = java.time.Duration.ofNanos(System.nanoTime() - started);
        assertThat(first.getNewItems()).isEqualTo(5_000);
        assertThat(firstTook).as("5 000 new items").isLessThan(IMPORT_BUDGET);

        started = System.nanoTime();
        PriceListImportReportDTO second = apply(utf8(raised.toString()), "hausse.csv");
        java.time.Duration secondTook = java.time.Duration.ofNanos(System.nanoTime() - started);
        assertThat(second.getNewPrices()).isEqualTo(5_000);
        assertThat(secondTook).as("5 000 new prices").isLessThan(IMPORT_BUDGET);
        assertThat(versionCount(hospitalA)).isEqualTo(10_000);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.price_list_prices WHERE hospital_id = ? "
            + "AND effective_to = DATE '2026-12-31'", Integer.class, hospitalA)).isEqualTo(5_000);
    }

    // ───────────────────────── who and where ─────────────────────────

    @Test
    @DisplayName("hospital B imports into its own grid: A's items untouched, A's lab tests unknown at B")
    void anotherHospitalImportsIntoItsOwnGrid() {
        apply(fixture(), FIXTURE);
        actAt(hospitalB);
        PriceListImportReportDTO atB = check(fixture(), FIXTURE);
        assertThat(atB.isAlreadyImported()).as("A's import is not B's").isFalse();
        assertThat(atB.getNewItems()).as("B's grid is empty: every code is new").isEqualTo(45);
        assertThat(atB.getRefused()).as("the 15 TWP lab tests exist only at A").isEqualTo(15);
        String nonLab = new String(fixture(), StandardCharsets.UTF_8).lines()
            .filter(l -> !l.contains("LAB_TEST_DEFINITION")).collect(Collectors.joining("\n"));
        assertThat(apply(utf8(nonLab), "b.csv").getNewItems()).isEqualTo(45);
        assertThat(itemCount(hospitalB)).isEqualTo(45);
        assertThat(itemCount(hospitalA)).isEqualTo(60);
        assertThat(new String(inTx(() -> importer.export()), StandardCharsets.UTF_8)).doesNotContain("LAB-NFS");
    }

    @Test
    @DisplayName("without HOSPITAL_ADMIN or ACCOUNTANT at the hospital the file is not even read")
    void aNonMaintainerIsRefusedBeforeTheFileIsRead() {
        when(roles.canMaintainPriceList(eq(actor), any())).thenReturn(false);
        byte[] file = fixture();
        assertThatThrownBy(() -> check(file, FIXTURE)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThatThrownBy(() -> apply(new byte[0], FIXTURE)).isInstanceOf(ClientSafeAccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.price_list_imports WHERE hospital_id = ?",
            Integer.class, hospitalA)).isZero();
        assertThat(itemCount(hospitalA)).isZero();
    }
}
