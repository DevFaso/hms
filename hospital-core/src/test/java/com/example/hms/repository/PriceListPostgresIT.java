package com.example.hms.repository;

import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.enums.PriceListPriceStatus;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.payload.dto.billing.PriceListItemDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListPriceDTO;
import com.example.hms.payload.dto.billing.PriceListPriceRequestDTO;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.billing.PriceListLinks;
import com.example.hms.service.billing.PriceListService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
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
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 2 on real PostgreSQL built by Liquibase (V302): the test clinic's whole grid loads, the
 * constraints hold, two concurrent "add price" calls on one item are serialised (one wins, one is refused), a
 * hospital never reaches another's items, and a price change never rewrites a posted invoice line (D-4).
 * Each service call runs in its own transaction, as the API would. Foreign keys are off for the seed: the hospital,
 * invoice and patient rows say nothing here.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@org.junit.jupiter.api.Timeout(180)
class PriceListPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_price_list")
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

    /** The test clinic's business day: before the grid's 01/12/2026 start, as README A-5 requires. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    @Autowired private PriceListItemRepository items;
    @Autowired private PriceListPriceRepository prices;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final LabTestDefinitionRepository labTests = mock(LabTestDefinitionRepository.class);
    private final TreatmentRepository treatments = mock(TreatmentRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private PriceListService service;
    /** On the wall clock, as BaseEntity.createdAt is: for the "entered today" rule. */
    private PriceListService liveService;
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
        hospitalA = UUID.randomUUID();
        hospitalB = UUID.randomUUID();
        actor = UUID.randomUUID();
        service = new PriceListService(items, prices, new PriceListLinks(labTests, treatments), labTests, treatments,
            mock(UserRepository.class), roles, scope, audit, CLOCK, "Africa/Ouagadougou");
        liveService = new PriceListService(items, prices, new PriceListLinks(labTests, treatments), labTests, treatments,
            mock(UserRepository.class), roles, scope, audit, Clock.systemDefaultZone(), "Africa/Ouagadougou");
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
    }

    private void actAt(UUID hospitalId) {
        when(scope.requirePinned()).thenReturn(hospitalId);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalId);
    }

    private static LabTestDefinition labTest(String code, UUID hospitalId) {
        LabTestDefinition definition = new LabTestDefinition();
        definition.setId(UUID.nameUUIDFromBytes(code.getBytes(StandardCharsets.UTF_8)));
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

    private PriceListItemDTO create(String code, PriceListCategory category, String price, LocalDate from) {
        return inTx(() -> service.create(PriceListItemRequestDTO.builder()
            .code(code).label(code).category(category).unitPrice(new BigDecimal(price)).effectiveFrom(from).build()));
    }

    private PriceListItemDTO createLive(String code, PriceListCategory category, String price, LocalDate from) {
        return inTx(() -> liveService.create(PriceListItemRequestDTO.builder()
            .code(code).label(code).category(category).unitPrice(new BigDecimal(price)).effectiveFrom(from).build()));
    }

    // ───────────────────────── the test clinic's grid ─────────────────────────

    @Test
    @DisplayName("every row of tarifs-test.csv is held: codes, labels as typed, categories, links, 01/12/2026 start")
    void theTestClinicGridLoadsWhole() throws IOException {
        List<String[]> rows = gridRows();
        assertThat(rows).hasSize(60);
        for (String[] row : rows) {
            inTx(() -> service.create(PriceListItemRequestDTO.builder()
                .code(row[0]).label(row[1]).category(PriceListCategory.valueOf(row[2]))
                .unitPrice(new BigDecimal(row[3])).effectiveFrom(LocalDate.parse(row[4], FR_DATE))
                .linkType(row[5].isEmpty() ? null : PriceListLinkType.valueOf(row[5]))
                .linkKey(row[6].isEmpty() ? null : row[6])
                .build()));
        }

        List<PriceListItemDTO> grid = inTx(() -> service.list(null, false));
        assertThat(grid).hasSize(60);
        Map<String, PriceListItemDTO> byCode = grid.stream()
            .collect(Collectors.toMap(PriceListItemDTO::getCode, Function.identity()));
        for (String[] row : rows) {
            PriceListItemDTO item = byCode.get(row[0]);
            assertThat(item.getLabel()).as(row[0]).isEqualTo(row[1]);
            assertThat(item.getCategory()).as(row[0]).isEqualTo(PriceListCategory.valueOf(row[2]));
            assertThat(item.getCurrentPrice()).as(row[0] + " starts on 01/12/2026, not today").isNull();
            assertThat(item.getNextPrice().getUnitPrice()).as(row[0]).isEqualByComparingTo(row[3]);
            assertThat(item.getNextPrice().getEffectiveFrom()).isEqualTo(LocalDate.of(2026, 12, 1));
            assertThat(item.getNextPrice().getStatus()).isEqualTo(PriceListPriceStatus.SCHEDULED);
            if (row[5].isEmpty()) {
                assertThat(item.getLinkType()).as(row[0]).isNull();
            } else {
                assertThat(item.getLinkType()).as(row[0]).isEqualTo(PriceListLinkType.valueOf(row[5]));
            }
        }
        // A-1: the lab link is the test's id at the hospital, found by its TWP code.
        assertThat(byCode.get("LAB-NFS").getLinkKey())
            .isEqualTo(UUID.nameUUIDFromBytes("TWP-NFS".getBytes(StandardCharsets.UTF_8)).toString());
        assertThat(byCode.get("IMG-ECHO-OBS").getLinkKey()).isEqualTo("ULTRASOUND:echographie obstetricale");

        // exemples-chiffres §1: the bed day goes from 5 000 to 6 000 F on 15/12/2026.
        UUID bedDay = byCode.get("HOSP-JOUR").getId();
        PriceListItemDTO changed = inTx(() -> service.addPrice(bedDay, PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("6000")).effectiveFrom(LocalDate.of(2026, 12, 15))
            .changeReason("Révision du tarif des journées").build()));
        assertThat(changed.getHistory()).extracting(PriceListPriceDTO::getUnitPrice)
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(new BigDecimal("6000"), new BigDecimal("5000"));
        assertThat(changed.getHistory().get(1).getEffectiveTo()).isEqualTo(LocalDate.of(2026, 12, 14));
        assertThat(changed.getHistory().get(0).getChangeReason()).isEqualTo("Révision du tarif des journées");
    }

    private static List<String[]> gridRows() throws IOException {
        try (InputStream in = Objects.requireNonNull(
                PriceListPostgresIT.class.getResourceAsStream("/billing/tarifs-test.csv"))) {
            List<String> lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().toList();
            assertThat(lines.get(0)).isEqualTo("code;libelle;categorie;prix;date_effet;liaison_type;liaison_cle");
            return lines.stream().skip(1).filter(l -> !l.isBlank()).map(l -> l.split(";", -1)).toList();
        }
    }

    // ───────────────────────── versions ─────────────────────────

    @Test
    @DisplayName("2 500 F from next month keeps 2 000 F in force today; a past date is refused")
    void aFuturePriceKeepsTodaysPrice() {
        PriceListItemDTO item = create("CS-GEN", PriceListCategory.CONSULTATION, "2000", null);
        PriceListItemDTO after = inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("2500")).effectiveFrom(LocalDate.of(2026, 12, 1)).changeReason("Hausse").build()));
        assertThat(after.getCurrentPrice().getUnitPrice()).isEqualByComparingTo("2000");
        assertThat(after.getNextPrice().getUnitPrice()).isEqualByComparingTo("2500");
        assertThat(after.getHistory()).hasSize(2);
        assertThat(after.getHistory().get(1).getEffectiveTo()).isEqualTo(LocalDate.of(2026, 11, 30));

        assertThatThrownBy(() -> inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("1800")).effectiveFrom(TODAY.minusDays(1)).changeReason("x").build())))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("effectiveFrom"));
    }

    @Test
    @DisplayName("a planned price is replaced only when confirmed, and the replaced one stays in the history")
    void aPlannedPriceIsReplacedWithConfirmation() {
        PriceListItemDTO item = create("LAB-NFS", PriceListCategory.LAB_TEST, "3500", null);
        LocalDate december = LocalDate.of(2026, 12, 1);
        inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("4000")).effectiveFrom(december).changeReason("Hausse").build()));
        assertThatThrownBy(() -> inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("3800")).effectiveFrom(december).changeReason("Erreur").build())))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("replaceScheduled"));

        PriceListItemDTO replaced = inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("3800")).effectiveFrom(december).changeReason("Erreur de saisie")
            .replaceScheduled(true).build()));
        assertThat(replaced.getNextPrice().getUnitPrice()).isEqualByComparingTo("3800");
        assertThat(replaced.getHistory()).extracting(PriceListPriceDTO::getStatus)
            .containsExactlyInAnyOrder(PriceListPriceStatus.SCHEDULED, PriceListPriceStatus.REPLACED,
                PriceListPriceStatus.IN_FORCE);
    }

    @Test
    @DisplayName("two concurrent new prices from today on one item: one wins, one is refused")
    void concurrentNewPricesAreSerialised() throws Exception {
        PriceListItemDTO item = createLive("ACT-PANS-S", PriceListCategory.PROCEDURE, "1000", null);
        // The first price started two weeks ago (no endpoint can date one in the past: written as the history would be).
        jdbc.update("UPDATE billing.price_list_prices SET effective_from = ? WHERE item_id = ?",
            java.sql.Date.valueOf(java.time.LocalDate.now().minusDays(14)), item.getId());

        List<Callable<PriceListItemDTO>> calls = List.of(
            () -> liveService.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
                .unitPrice(new BigDecimal("1200")).changeReason("Caisse 1").build()),
            () -> liveService.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
                .unitPrice(new BigDecimal("1300")).changeReason("Caisse 2").build()));
        int won = 0;
        int refused = 0;
        for (Future<PriceListItemDTO> result : together(calls)) {
            try {
                result.get();
                won++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(FieldBusinessException.class);
                // The loser sees a version entered today and in force today: a correction it did not confirm.
                assertThat(((FieldBusinessException) e.getCause()).getField()).isEqualTo("replaceScheduled");
                refused++;
            }
        }
        assertThat(won).isEqualTo(1);
        assertThat(refused).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM billing.price_list_prices WHERE item_id = ? AND effective_to IS NULL",
            Integer.class, item.getId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM billing.price_list_prices WHERE item_id = ?", Integer.class, item.getId()))
            .isEqualTo(2);
    }

    private <T> List<Future<T>> together(List<Callable<T>> calls) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> results = new ArrayList<>();
        for (Callable<T> call : calls) {
            results.add(pool.submit(() -> {
                start.await();
                return inTx(call);
            }));
        }
        start.countDown();
        pool.shutdown();
        return results;
    }

    @Test
    @DisplayName("review 3b02ae1: a price between two planned ones replaces neither; the new one ends before the next")
    void aNewPriceBetweenTwoPlannedOnesKeepsBoth() {
        PriceListItemDTO item = create("IMG-ECHO-ABD", PriceListCategory.IMAGING, "12500", null);
        LocalDate b = TODAY.plusDays(16);
        LocalDate c = TODAY.plusDays(30);
        inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("13000")).effectiveFrom(b).changeReason("B").build()));
        inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("14000")).effectiveFrom(c).changeReason("C").build()));
        PriceListItemDTO after = inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("12800")).effectiveFrom(TODAY.plusDays(5)).changeReason("Entre les deux")
            .replaceScheduled(true).build()));
        assertThat(after.getHistory()).extracting(PriceListPriceDTO::getStatus).doesNotContain(PriceListPriceStatus.REPLACED);
        assertThat(after.getHistory()).extracting(PriceListPriceDTO::getEffectiveFrom, PriceListPriceDTO::getEffectiveTo)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple(c, null),
                org.assertj.core.groups.Tuple.tuple(b, c.minusDays(1)),
                org.assertj.core.groups.Tuple.tuple(TODAY.plusDays(5), b.minusDays(1)),
                org.assertj.core.groups.Tuple.tuple(TODAY, TODAY.plusDays(4)));
    }

    @Test
    @DisplayName("decision 2026-11-16: a price entered today is corrected the same day with a reason; history keeps it as replaced")
    void aSameDayEntryIsCorrectedAndKeptInTheHistory() {
        PriceListItemDTO item = createLive("LAB-GE", PriceListCategory.LAB_TEST, "1000", null);
        assertThatThrownBy(() -> inTx(() -> liveService.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("1100")).changeReason("Erreur de saisie").build())))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("replaceScheduled"));

        PriceListItemDTO corrected = inTx(() -> liveService.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("1100")).changeReason("Erreur de saisie").replaceScheduled(true).build()));
        assertThat(corrected.getCurrentPrice().getUnitPrice()).isEqualByComparingTo("1100");
        assertThat(corrected.getHistory()).extracting(PriceListPriceDTO::getStatus)
            .containsExactlyInAnyOrder(PriceListPriceStatus.IN_FORCE, PriceListPriceStatus.REPLACED);

        // Entered on an earlier day, the version in force is applied history and is never replaced.
        jdbc.update("UPDATE billing.price_list_prices SET created_at = created_at - interval '2 days' "
            + "WHERE item_id = ? AND effective_to IS NULL", corrected.getId());
        assertThatThrownBy(() -> inTx(() -> liveService.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("1200")).changeReason("x").replaceScheduled(true).build())))
            .isInstanceOfSatisfying(FieldBusinessException.class,
                e -> assertThat(e.getField()).isEqualTo("effectiveFrom"));
    }

    // ───────────────────────── constraints ─────────────────────────

    @Test
    @DisplayName("the database refuses a duplicate code, a second active item on one link, a decimal price and overlapping versions")
    void theConstraintsHold() {
        UUID item = UUID.randomUUID();
        jdbc.update("INSERT INTO billing.price_list_items (id, hospital_id, code, label, category, link_type, link_key) "
            + "VALUES (?, ?, 'HOSP-JOUR', 'Journée', 'BED_DAY', 'SYSTEM', 'BED_DAY')", item, hospitalA);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO billing.price_list_items (id, hospital_id, code, label, "
            + "category) VALUES (?, ?, 'HOSP-JOUR', 'Autre', 'BED_DAY')", UUID.randomUUID(), hospitalA))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO billing.price_list_items (id, hospital_id, code, label, "
            + "category, link_type, link_key) VALUES (?, ?, 'HOSP-J2', 'Journée 2', 'BED_DAY', 'SYSTEM', 'BED_DAY')",
            UUID.randomUUID(), hospitalA)).isInstanceOf(DataIntegrityViolationException.class);
        // The same code and link at another hospital are its own.
        jdbc.update("INSERT INTO billing.price_list_items (id, hospital_id, code, label, category, link_type, link_key) "
            + "VALUES (?, ?, 'HOSP-JOUR', 'Journée', 'BED_DAY', 'SYSTEM', 'BED_DAY')", UUID.randomUUID(), hospitalB);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO billing.price_list_items (id, hospital_id, code, label, "
            + "category) VALUES (?, ?, 'lower', 'Minuscules', 'BED_DAY')", UUID.randomUUID(), hospitalA))
            .isInstanceOf(DataIntegrityViolationException.class);

        String version = "INSERT INTO billing.price_list_prices (id, hospital_id, item_id, unit_price, effective_from, "
            + "effective_to) VALUES (?, ?, ?, ?, ?, ?)";
        jdbc.update(version, UUID.randomUUID(), hospitalA, item, new BigDecimal("5000"),
            java.sql.Date.valueOf(TODAY), null);
        assertThatThrownBy(() -> jdbc.update(version, UUID.randomUUID(), hospitalA, item, new BigDecimal("2500.50"),
            java.sql.Date.valueOf(TODAY.plusDays(30)), java.sql.Date.valueOf(TODAY.plusDays(40))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(version, UUID.randomUUID(), hospitalA, item, new BigDecimal("6000"),
            java.sql.Date.valueOf(TODAY), java.sql.Date.valueOf(TODAY.plusDays(3))))
            .as("two live versions from the same day").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(version, UUID.randomUUID(), hospitalA, item, new BigDecimal("6000"),
            java.sql.Date.valueOf(TODAY.plusDays(30)), null))
            .as("two open-ended versions").isInstanceOf(DataIntegrityViolationException.class);
        // Review 3b02ae1: a version's FK is (item_id, hospital_id) -> the item's (id, hospital_id), so a version
        // can never carry another hospital than its item. (FK triggers are off for this seed, so the catalogue
        // is read rather than the insert attempted.)
        assertThat(jdbc.queryForObject("""
            SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c
            WHERE c.conname = 'fk_price_list_price_item'
            """, String.class))
            .isEqualTo("FOREIGN KEY (item_id, hospital_id) REFERENCES billing.price_list_items(id, hospital_id)");
        // A replaced version (never in force) may share its start with the one that replaced it.
        jdbc.update(version, UUID.randomUUID(), hospitalA, item, new BigDecimal("6000"),
            java.sql.Date.valueOf(TODAY), java.sql.Date.valueOf(TODAY.minusDays(1)));
    }

    // ───────────────────────── tenancy ─────────────────────────

    @Test
    @DisplayName("hospital B gets 404 on A's items and an empty list; A's lab codes are unknown at B")
    void anotherHospitalNeverReachesTheItems() {
        PriceListItemDTO itemA = create("CS-GEN", PriceListCategory.CONSULTATION, "2000", null);
        actAt(hospitalB);
        assertThat(inTx(() -> service.list(null, true))).isEmpty();
        assertThatThrownBy(() -> inTx(() -> service.get(itemA.getId()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTx(() -> service.addPrice(itemA.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("1")).changeReason("x").build()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTx(() -> service.update(itemA.getId(),
            com.example.hms.payload.dto.billing.PriceListItemUpdateRequestDTO.builder()
                .label("Repris par B").category(PriceListCategory.CONSULTATION).build())))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTx(() -> service.deactivate(itemA.getId(),
            new com.example.hms.payload.dto.billing.PriceListDeactivateRequestDTO("x"))))
            .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTx(() -> service.create(PriceListItemRequestDTO.builder()
            .code("LAB-NFS").label("NFS").category(PriceListCategory.LAB_TEST).unitPrice(new BigDecimal("3500"))
            .linkType(PriceListLinkType.LAB_TEST_DEFINITION).linkKey("TWP-NFS").build())))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> assertThat(e.getField()).isEqualTo("linkKey"));
        // The same code at B is B's own.
        PriceListItemDTO itemB = create("CS-GEN", PriceListCategory.CONSULTATION, "1500", null);
        assertThat(itemB.getId()).isNotEqualTo(itemA.getId());
        actAt(hospitalA);
        assertThat(inTx(() -> service.get(itemA.getId())).getCurrentPrice().getUnitPrice()).isEqualByComparingTo("2000");
    }

    @Test
    @DisplayName("the super-admin with no assignment at the hospital writes nothing")
    void theSuperAdminAloneWritesNothing() {
        UUID superAdmin = UUID.randomUUID();
        when(roles.getCurrentUserId()).thenReturn(superAdmin);
        when(roles.canMaintainPriceList(superAdmin, hospitalA)).thenReturn(false);
        assertThatThrownBy(() -> create("CS-GEN", PriceListCategory.CONSULTATION, "2000", null))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.price_list_items WHERE hospital_id = ?",
            Integer.class, hospitalA)).isZero();
    }

    // ───────────────────────── posted lines ─────────────────────────

    @Test
    @DisplayName("D-4: a price change never rewrites a line already posted at the old price")
    void aPriceChangeNeverRewritesAPostedLine() {
        PriceListItemDTO item = create("CS-GEN", PriceListCategory.CONSULTATION, "2000", null);
        jdbc.execute("ALTER TABLE billing.billing_invoices DISABLE TRIGGER ALL");
        jdbc.execute("ALTER TABLE billing.invoice_items DISABLE TRIGGER ALL");
        UUID invoice = UUID.randomUUID();
        UUID line = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, invoice_number, invoice_date, due_date,
                total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, 2000, 0, 'SENT', false, now(), now())
            """, invoice, UUID.randomUUID(), hospitalA, "TWP-2026-" + invoice.toString().substring(0, 8),
            java.sql.Date.valueOf(TODAY), java.sql.Date.valueOf(TODAY.plusDays(30)));
        jdbc.update("""
            INSERT INTO billing.invoice_items (id, billing_invoice_id, item_description, quantity, item_category,
                unit_price, total_price, assignment_id, created_at, updated_at)
            VALUES (?, ?, 'Consultation générale', 1, 'SERVICE', 2000, 2000, ?, now(), now())
            """, line, invoice, UUID.randomUUID());

        inTx(() -> service.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("2500")).effectiveFrom(TODAY.plusDays(1)).changeReason("Hausse").build()));
        inTx(() -> service.deactivate(item.getId(),
            new com.example.hms.payload.dto.billing.PriceListDeactivateRequestDTO("Grille remplacée")));

        Map<String, Object> posted = jdbc.queryForMap(
            "SELECT unit_price, total_price, updated_at FROM billing.invoice_items WHERE id = ?", line);
        assertThat((BigDecimal) posted.get("unit_price")).isEqualByComparingTo("2000");
        assertThat((BigDecimal) posted.get("total_price")).isEqualByComparingTo("2000");
        assertThat(jdbc.queryForObject("SELECT total_amount FROM billing.billing_invoices WHERE id = ?",
            BigDecimal.class, invoice)).isEqualByComparingTo("2000");
        verify(audit, atLeastOnce()).logEvent(any());
    }
}
