package com.example.hms.repository;

import com.example.hms.enums.PayerCoverageStatus;
import com.example.hms.enums.PayerType;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.PriceListItem;
import com.example.hms.payload.dto.billing.PayerDTO;
import com.example.hms.payload.dto.billing.PayerPlanDTO;
import com.example.hms.payload.dto.billing.PayerPlanRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanRuleDTO;
import com.example.hms.payload.dto.billing.PayerPlanRuleRequestDTO;
import com.example.hms.payload.dto.billing.PayerPlanTermsRequestDTO;
import com.example.hms.payload.dto.billing.PayerRequestDTO;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.billing.PayerCatalogueService;
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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 6 on real PostgreSQL built by Liquibase (V305): the test clinic's fictional payers load
 * (docs/plan/test-hospital/payeurs-test.csv, never a real insurer), the constraints hold (one target per rule, a rule
 * on an item of the same hospital only, a plan under a payer of the same hospital), two concurrent rules for one
 * target are serialised (one wins, one is asked to confirm), and a hospital never reaches another's catalogue. Each
 * service call runs in its own transaction, as the API would. The hospital FKs are dropped in this throwaway
 * container: the hospital rows say nothing here, and the composite FKs between the catalogue tables stay on.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@org.junit.jupiter.api.Timeout(180)
class PayerCataloguePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_payer_catalogue")
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

    /** Before the test clinic's 01/12/2026 start, so its dates are accepted. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC);
    private static final DateTimeFormatter FR_DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final LocalDate GO_LIVE = LocalDate.of(2026, 12, 1);

    @Autowired private PayerRepository payers;
    @Autowired private PayerPlanRepository plans;
    @Autowired private PayerPlanRuleRepository rules;
    @Autowired private PayerPlanTermsRepository terms;
    @Autowired private PriceListItemRepository items;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private PayerCatalogueService service;
    private TransactionTemplate tx;
    private UUID hospitalA;
    private UUID hospitalB;
    private UUID actor;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        for (String fk : List.of("billing.payers DROP CONSTRAINT IF EXISTS fk_payer_hospital",
                "billing.payer_plans DROP CONSTRAINT IF EXISTS fk_payer_plan_hospital",
                "billing.payer_plan_rules DROP CONSTRAINT IF EXISTS fk_payer_rule_hospital",
                "billing.payer_plan_terms DROP CONSTRAINT IF EXISTS fk_payer_terms_hospital",
                "billing.price_list_items DROP CONSTRAINT IF EXISTS fk_price_list_item_hospital")) {
            jdbc.execute("ALTER TABLE " + fk);
        }
        hospitalA = UUID.randomUUID();
        hospitalB = UUID.randomUUID();
        actor = UUID.randomUUID();
        service = new PayerCatalogueService(payers, plans, rules, terms, items, mock(UserRepository.class), roles, scope,
            mock(AuditEventLogService.class), CLOCK, "Africa/Ouagadougou");
        tx = new TransactionTemplate(transactions);
        actAt(hospitalA);
        when(roles.getCurrentUserId()).thenReturn(actor);
        when(roles.canMaintainPayers(eq(actor), any())).thenReturn(true);
        when(roles.canReadPayers(eq(actor), any())).thenReturn(true);
    }

    @org.junit.jupiter.api.AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    private void actAt(UUID hospitalId) {
        when(scope.requirePinned()).thenReturn(hospitalId);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalId);
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

    private PriceListItem item(UUID hospitalId, String code, PriceListCategory category) {
        return inTx(() -> items.saveAndFlush(PriceListItem.builder().hospitalId(hospitalId).code(code).label(code)
            .category(category).active(true).build()));
    }

    private PayerDTO payer(String code, PayerType type) {
        return inTx(() -> service.createPayer(PayerRequestDTO.builder().code(code).name(code + " (FICTIVE)")
            .payerType(type).build()));
    }

    private static BigDecimal amount(String raw) {
        return raw == null || raw.isBlank() ? null : new BigDecimal(raw.trim());
    }

    // ───────────────────────── the test clinic's payers ─────────────────────────

    @Test
    @DisplayName("the test clinic's two fictional payers, their plans and rules load as payeurs-test.csv says")
    void theTestClinicsPayersLoad() throws IOException {
        Map<String, UUID> itemIds = new HashMap<>();
        for (String[] coded : List.of(new String[] {"LAB-WIDAL", "LAB_TEST"}, new String[] {"ACT-CIRC", "PROCEDURE"},
                new String[] {"ACC-CESAR", "DELIVERY"}, new String[] {"ADM-CERTIF", "OTHER_SERVICE"})) {
            itemIds.put(coded[0], item(hospitalA, coded[0], PriceListCategory.valueOf(coded[1])).getId());
        }
        List<String[]> rows = new ArrayList<>();
        try (InputStream in = getClass().getResourceAsStream("/billing/payeurs-test.csv")) {
            assertThat(in).as("the fixture copied from docs/plan/test-hospital").isNotNull();
            String[] lines = new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\r?\\n");
            for (int i = 1; i < lines.length; i++) {
                if (!lines[i].isBlank()) {
                    rows.add(lines[i].split(";", -1));
                }
            }
        }
        Map<String, UUID> payerIds = new LinkedHashMap<>();
        Map<String, UUID> planIds = new HashMap<>();
        for (String[] row : rows) {
            if ("SANS_PAYEUR".equals(row[2])) {
                continue; // "Patient non assuré" is the absence of a plan, never a payer (README A-9)
            }
            LocalDate from = LocalDate.parse(row[13], FR_DATE);
            UUID payerId = payerIds.computeIfAbsent(row[0], code -> inTx(() -> service.createPayer(
                PayerRequestDTO.builder().code(code).name(row[1]).payerType(PayerType.valueOf(row[2])).build())).getId());
            if ("FORMULE".equals(row[5])) {
                PayerDTO saved = inTx(() -> service.createPlan(payerId, PayerPlanRequestDTO.builder()
                    .code(row[3]).name(row[4]).defaultCoveragePercent(amount(row[8]))
                    .perInvoiceCeiling(amount(row[11])).annualCeilingPerBeneficiary(amount(row[12]))
                    .effectiveFrom(from).build()));
                planIds.put(row[3], saved.getPlans().stream().filter(p -> p.getCode().equals(row[3])).findFirst()
                    .orElseThrow().getId());
            } else {
                boolean onCategory = "CATEGORIE".equals(row[6]);
                inTx(() -> service.addRule(payerId, planIds.get(row[3]), PayerPlanRuleRequestDTO.builder()
                    .category(onCategory ? PriceListCategory.valueOf(row[7]) : null)
                    .priceListItemId(onCategory ? null : itemIds.get(row[7]))
                    .coveragePercent(amount(row[8])).excluded("OUI".equals(row[9]))
                    .perActCeiling(amount(row[10])).effectiveFrom(from).build()));
            }
        }

        List<PayerDTO> loaded = inTx(() -> service.list(false));
        assertThat(loaded).extracting(PayerDTO::getCode).containsExactly("ASS-TEST-B", "MUT-TEST-A");
        PayerPlanDTO essential = loaded.get(1).getPlans().get(0);
        assertThat(essential.getDefaultCoveragePercent()).isEqualTo(75);
        assertThat(essential.getPerInvoiceCeiling()).isEqualByComparingTo("30000");
        assertThat(essential.getAnnualCeilingPerBeneficiary()).isEqualByComparingTo("150000");
        assertThat(essential.getStatus()).isEqualTo(PayerCoverageStatus.SCHEDULED);
        assertThat(essential.isLinkable()).isTrue();
        assertThat(essential.getRules()).hasSize(4);
        assertThat(essential.getRules()).filteredOn(r -> r.getCategory() == PriceListCategory.IMAGING)
            .extracting(PayerPlanRuleDTO::getCoveragePercent).containsExactly(50);
        assertThat(essential.getRules()).filteredOn(r -> "LAB-WIDAL".equals(r.getPriceListItemCode()))
            .extracting(PayerPlanRuleDTO::isExcluded).containsExactly(true);
        PayerPlanDTO comfort = loaded.get(0).getPlans().get(0);
        assertThat(comfort.getDefaultCoveragePercent()).isEqualTo(90);
        assertThat(comfort.getRules()).filteredOn(r -> "ACC-CESAR".equals(r.getPriceListItemCode()))
            .extracting(PayerPlanRuleDTO::getPerActCeiling).singleElement()
            .satisfies(ceiling -> assertThat(ceiling).isEqualByComparingTo("120000"));
        assertThat(comfort.getRules()).allSatisfy(r -> assertThat(r.getEffectiveFrom()).isEqualTo(GO_LIVE));
    }

    @Test
    @DisplayName("Formule Base 80 %, laboratory excluded, annual ceiling 150 000 F; another hospital sees none of it")
    void aPlanWithAnExclusionAndHospitalBSeesNothing() {
        PayerDTO amu = payer("AMU-TEST", PayerType.AMU);
        PayerDTO withPlan = inTx(() -> service.createPlan(amu.getId(), PayerPlanRequestDTO.builder()
            .code("AMU-BASE").name("Formule Base").defaultCoveragePercent(new BigDecimal("80"))
            .annualCeilingPerBeneficiary(new BigDecimal("150000")).build()));
        UUID planId = withPlan.getPlans().get(0).getId();
        PayerDTO withRule = inTx(() -> service.addRule(amu.getId(), planId, PayerPlanRuleRequestDTO.builder()
            .category(PriceListCategory.LAB_TEST).excluded(true).build()));
        assertThat(withRule.getPlans().get(0).getRules()).singleElement()
            .satisfies(rule -> {
                assertThat(rule.isExcluded()).isTrue();
                assertThat(rule.getStatus()).isEqualTo(PayerCoverageStatus.IN_FORCE);
            });

        actAt(hospitalB);
        assertThat(inTx(() -> service.list(true))).isEmpty();
        assertThatThrownBy(() -> inTx(() -> service.get(amu.getId()))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> inTx(() -> service.addRule(amu.getId(), planId, PayerPlanRuleRequestDTO.builder()
            .category(PriceListCategory.IMAGING).excluded(true).build()))).isInstanceOf(ResourceNotFoundException.class);
        // A patient's coverage at hospital B cannot be linked to A's plan: it answers as a missing plan.
        assertThatThrownBy(() -> inTx(() -> service.requireLinkablePlan(planId, hospitalB)))
            .isInstanceOf(ResourceNotFoundException.class);
        actAt(hospitalA);
        assertThat(inTx(() -> service.requireLinkablePlan(planId, hospitalA)).getId()).isEqualTo(planId);
    }

    @Test
    @DisplayName("review of #1202: new terms add a dated version; the terms in force are closed, never rewritten")
    void newTermsAreADatedVersion() {
        PayerDTO payer = payer("MUT-TEST-A", PayerType.MUTUELLE);
        UUID planId = inTx(() -> service.createPlan(payer.getId(), PayerPlanRequestDTO.builder().code("MTA-ESS")
            .name("Formule Test Essentielle").defaultCoveragePercent(new BigDecimal("75")).build()))
            .getPlans().get(0).getId();
        PayerDTO changed = inTx(() -> service.addTerms(payer.getId(), planId, PayerPlanTermsRequestDTO.builder()
            .defaultCoveragePercent(new BigDecimal("70")).effectiveFrom(GO_LIVE).build()));
        PayerPlanDTO plan = changed.getPlans().get(0);
        assertThat(plan.getDefaultCoveragePercent()).as("today's terms").isEqualTo(75);
        assertThat(plan.getNextTerms().getDefaultCoveragePercent()).isEqualTo(70);
        assertThat(plan.getTerms()).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT effective_to FROM billing.payer_plan_terms WHERE plan_id = ? "
            + "AND default_coverage_percent = 75", LocalDate.class, planId)).isEqualTo(GO_LIVE.minusDays(1));
        assertThat(inTx(() -> service.termsOn(planId, hospitalA, GO_LIVE)).orElseThrow().getDefaultCoveragePercent())
            .isEqualTo(70);
        assertThat(inTx(() -> service.termsOn(planId, hospitalA, GO_LIVE.minusDays(1))).orElseThrow()
            .getDefaultCoveragePercent()).isEqualTo(75);
        // A planned version is replaced only on confirmation, and the partial unique indexes hold.
        PayerPlanTermsRequestDTO again = PayerPlanTermsRequestDTO.builder()
            .defaultCoveragePercent(new BigDecimal("80")).effectiveFrom(GO_LIVE).replaceScheduled(true).build();
        UUID payerId = payer.getId();
        assertThat(inTx(() -> service.addTerms(payerId, planId, again)).getPlans().get(0).getTerms()).hasSize(3);
    }

    // ───────────────────────── the constraints ─────────────────────────

    @Test
    @DisplayName("the database refuses a rule with two targets, an item of another hospital, and a cross-hospital plan")
    void theConstraintsHold() {
        PayerDTO payer = payer("MUT-TEST-A", PayerType.MUTUELLE);
        UUID planId = inTx(() -> service.createPlan(payer.getId(), PayerPlanRequestDTO.builder().code("MTA-ESS")
            .name("Formule Test Essentielle").defaultCoveragePercent(new BigDecimal("75")).build()))
            .getPlans().get(0).getId();
        UUID itemOfB = item(hospitalB, "ACT-CIRC", PriceListCategory.PROCEDURE).getId();
        UUID itemOfA = item(hospitalA, "ACT-CIRC", PriceListCategory.PROCEDURE).getId();
        String insert = "INSERT INTO billing.payer_plan_rules (hospital_id, plan_id, category, price_list_item_id, "
            + "coverage_percent, excluded, per_act_ceiling, effective_from) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        LocalDate day = LocalDate.of(2026, 12, 1);

        assertThatThrownBy(() -> jdbc.update(insert, hospitalA, planId, "LAB_TEST", itemOfA, null, true, null, day))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_payer_rule_target");
        assertThatThrownBy(() -> jdbc.update(insert, hospitalA, planId, null, itemOfB, null, true, null, day))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_payer_rule_item");
        assertThatThrownBy(() -> jdbc.update(insert, hospitalA, planId, "IMAGING", null, 101, false, null, day))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_payer_rule_percent");
        assertThatThrownBy(() -> jdbc.update(insert, hospitalA, planId, "IMAGING", null, null, true, 10000, day))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_payer_rule_terms");
        assertThatThrownBy(() -> jdbc.update(insert, hospitalB, planId, "IMAGING", null, 50, false, null, day))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_payer_rule_plan");
        UUID payerId = payer.getId();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO billing.payer_plan_terms (hospital_id, plan_id, "
                + "default_coverage_percent, per_invoice_ceiling, effective_from) VALUES (?, ?, 50, 100.5, ?)",
            hospitalA, planId, day))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_payer_terms_invoice_cap");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO billing.payer_plan_terms (hospital_id, plan_id, "
                + "default_coverage_percent, effective_from, effective_to) VALUES (?, ?, 50, ?, ?)", hospitalB, planId,
                day.plusDays(5), day.plusDays(6)))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_payer_terms_plan");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO billing.payer_plans (hospital_id, payer_id, code, name, "
                + "effective_from) VALUES (?, ?, 'Y', 'Y', ?)",
            hospitalB, payerId, day))
            .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_payer_plan_payer");

        Integer coverageFk = jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname = 'fk_pi_payer_plan'",
            Integer.class);
        assertThat(coverageFk).as("clinical.patient_insurances.payer_plan_id references billing.payer_plans")
            .isEqualTo(1);
    }

    // ───────────────────────── concurrency ─────────────────────────

    @Test
    @DisplayName("two concurrent rules for one target and day: one wins, the other is asked to confirm a replacement")
    void twoConcurrentRulesForOneTargetAreSerialised() throws InterruptedException, ExecutionException {
        PayerDTO payer = payer("ASS-TEST-B", PayerType.ASSUREUR_PRIVE);
        UUID planId = inTx(() -> service.createPlan(payer.getId(), PayerPlanRequestDTO.builder().code("ATB-CONF")
            .name("Formule Test Confort").defaultCoveragePercent(new BigDecimal("90")).build()))
            .getPlans().get(0).getId();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (String percent : List.of("50", "60")) {
                results.add(pool.submit(() -> {
                    start.await();
                    try {
                        inTx(() -> service.addRule(payer.getId(), planId, PayerPlanRuleRequestDTO.builder()
                            .category(PriceListCategory.IMAGING).coveragePercent(new BigDecimal(percent))
                            .effectiveFrom(GO_LIVE).build()));
                        return "saved";
                    } catch (FieldBusinessException refused) {
                        return refused.getField();
                    }
                }));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }
            assertThat(outcomes).containsExactlyInAnyOrder("saved", "replaceScheduled");
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.payer_plan_rules WHERE plan_id = ?",
            Integer.class, planId)).isEqualTo(1);
    }
}
