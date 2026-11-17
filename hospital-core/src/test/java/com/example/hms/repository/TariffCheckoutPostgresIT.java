package com.example.hms.repository;

import com.example.hms.enums.ImagingModality;
import com.example.hms.enums.InvoiceStatus;
import com.example.hms.enums.PriceListCategory;
import com.example.hms.enums.PriceListLinkType;
import com.example.hms.event.EncounterCheckedOutEvent;
import com.example.hms.exception.FieldBusinessException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Admission;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.ImagingOrder;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Patient;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.billing.PriceListItemDTO;
import com.example.hms.payload.dto.billing.PriceListItemRequestDTO;
import com.example.hms.payload.dto.billing.PriceListPriceRequestDTO;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.InvoiceNumberAllocator;
import com.example.hms.service.billing.BedDaysChargeSource;
import com.example.hms.service.billing.ConsultationChargeSource;
import com.example.hms.service.billing.EncounterCheckoutChargeListener;
import com.example.hms.service.billing.ImagingOrderChargeSource;
import com.example.hms.service.billing.LabOrderChargeSource;
import com.example.hms.service.billing.PriceListLinks;
import com.example.hms.service.billing.PriceListService;
import com.example.hms.service.billing.ProcedureOrderChargeSource;
import com.example.hms.service.billing.TariffResolver;
import com.example.hms.service.billing.TransfusionChargeSource;
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

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Billing-money plan PR 3 on real PostgreSQL built by Liquibase (V302 + V303): a checkout prices its draft from the
 * hospital's price list through the share-locked lookup, a replayed checkout event opens nothing new, a later price
 * never touches a posted line and prices the next day's visit, a version an invoice line copied is never corrected,
 * and the V303 constraints hold. The clinical side of the visit (encounter, orders, stay) is in memory; foreign keys
 * are off for the billing rows, whose patient and hospital say nothing here.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@org.springframework.context.annotation.Import(com.example.hms.security.EncryptionKeyHolder.class)
@org.junit.jupiter.api.Timeout(180)
class TariffCheckoutPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_tariff_checkout")
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

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-15T09:00:00Z"), ZoneOffset.UTC);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 15);

    @Autowired private PriceListItemRepository items;
    @Autowired private PriceListPriceRepository prices;
    @Autowired private InvoiceItemRepository invoiceLines;
    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactions;

    private final LabTestDefinitionRepository labTests = mock(LabTestDefinitionRepository.class);
    private final TreatmentRepository treatments = mock(TreatmentRepository.class);
    private final RoleValidator roles = mock(RoleValidator.class);
    private final ActingScopeResolver scope = mock(ActingScopeResolver.class);
    private final AuditEventLogService audit = mock(AuditEventLogService.class);
    private final EncounterRepository encounters = mock(EncounterRepository.class);
    private final EncounterTreatmentRepository given = mock(EncounterTreatmentRepository.class);
    private final LabOrderRepository labOrders = mock(LabOrderRepository.class);
    private final ImagingOrderRepository imaging = mock(ImagingOrderRepository.class);
    private final ProcedureOrderRepository procedures = mock(ProcedureOrderRepository.class);
    private final TransfusionRequestRepository transfusions = mock(TransfusionRequestRepository.class);
    private final BloodUnitRepository units = mock(BloodUnitRepository.class);
    private final AdmissionRepository admissions = mock(AdmissionRepository.class);
    private final UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
    private final InvoiceNumberAllocator numbers = mock(InvoiceNumberAllocator.class);

    private PriceListService service;
    private TariffResolver resolver;
    private EncounterCheckoutChargeListener listener;
    private TransactionTemplate tx;
    private UUID hospitalA;
    private UUID hospitalB;
    private UUID actor;
    private UUID tdrId;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.FRENCH);
        for (String table : List.of("billing.price_list_items", "billing.price_list_prices",
                "billing.billing_invoices", "billing.invoice_items", "security.user_role_hospital_assignment")) {
            jdbc.execute("ALTER TABLE " + table + " DISABLE TRIGGER ALL");
        }
        hospitalA = UUID.randomUUID();
        hospitalB = UUID.randomUUID();
        actor = UUID.randomUUID();
        tdrId = UUID.nameUUIDFromBytes(("TWP-TDR" + hospitalA).getBytes(StandardCharsets.UTF_8));
        tx = new TransactionTemplate(transactions);
        service = new PriceListService(items, prices, new PriceListLinks(labTests, treatments), invoiceLines, labTests,
            treatments, mock(UserRepository.class), roles, scope, audit, CLOCK, "Africa/Ouagadougou");
        resolver = new TariffResolver(items, prices, CLOCK, "Africa/Ouagadougou");
        listener = new EncounterCheckoutChargeListener(encounters, invoices, treatments, assignments, numbers, resolver,
            List.of(new ConsultationChargeSource(given), new LabOrderChargeSource(labOrders),
                new ImagingOrderChargeSource(imaging), new ProcedureOrderChargeSource(procedures),
                new TransfusionChargeSource(transfusions, units), new BedDaysChargeSource(admissions)));
        when(scope.requirePinned()).thenReturn(hospitalA);
        when(roles.requireActiveHospitalId()).thenReturn(hospitalA);
        when(roles.getCurrentUserId()).thenReturn(actor);
        when(roles.canMaintainPriceList(eq(actor), any())).thenReturn(true);
        when(roles.canReadPriceList(eq(actor), any())).thenReturn(true);
        when(labTests.findLiveGlobalByCode(anyString())).thenReturn(List.of());
        when(labTests.findLiveByCodeAtHospital(anyString(), any())).thenReturn(List.of());
        LabTestDefinition tdr = new LabTestDefinition();
        tdr.setId(tdrId);
        tdr.setTestCode("TWP-TDR");
        tdr.setName("TDR paludisme");
        tdr.setActive(true);
        tdr.setHospital(hospital(hospitalA));
        when(labTests.findLiveByCodeAtHospital("TWP-TDR", hospitalA)).thenReturn(List.of(tdr));
        when(treatments.findByHospital_Id(any())).thenReturn(List.of());
        when(given.findByEncounter_Id(any())).thenReturn(List.of());
        when(numbers.next(any(), any())).thenAnswer(call -> "TWP-" + UUID.randomUUID().toString().substring(0, 12));
    }

    private static Hospital hospital(UUID id) {
        Hospital hospital = new Hospital();
        hospital.setId(id);
        return hospital;
    }

    private <T> T inTx(Supplier<T> call) {
        return tx.execute(status -> call.get());
    }

    private PriceListItemDTO item(String code, PriceListCategory category, String price, PriceListLinkType link,
                                  String key) {
        return inTx(() -> service.create(PriceListItemRequestDTO.builder().code(code).label(code + " (grille)")
            .category(category).unitPrice(new BigDecimal(price)).linkType(link).linkKey(key).build()));
    }

    /** The test clinic's grid at hospital A (plan, PR 3 acceptance 1). */
    private void grid() {
        item("CS-GEN", PriceListCategory.CONSULTATION, "2000", PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT");
        item("LAB-TDR", PriceListCategory.LAB_TEST, "1500", PriceListLinkType.LAB_TEST_DEFINITION, "TWP-TDR");
        item("RX-THORAX", PriceListCategory.IMAGING, "7500", PriceListLinkType.IMAGING_STUDY, "xray:Radio Thorax");
        item("HOSP-JOUR", PriceListCategory.BED_DAY, "5000", PriceListLinkType.SYSTEM, "BED_DAY");
    }

    /** A visit at {@code hospitalId}: one consultation, a TDR, a chest X-ray and three nights. */
    private Encounter visit(UUID hospitalId) {
        Hospital hospital = hospital(hospitalId);
        Patient patient = new Patient();
        patient.setId(UUID.randomUUID());
        Encounter encounter = new Encounter();
        encounter.setId(UUID.randomUUID());
        // A row read from the database carries its version; this one is detached, as the listener's re-read is.
        encounter.setVersion(0L);
        encounter.setHospital(hospital);
        encounter.setPatient(patient);
        when(encounters.findByIdAndHospital_Id(encounter.getId(), hospitalId)).thenReturn(Optional.of(encounter));
        // The line's owner is merged with the line, so its row exists (with a role row; FK checks are off).
        UUID role = UUID.randomUUID();
        jdbc.update("INSERT INTO security.roles (id, code, name, created_at, updated_at) VALUES (?, ?, ?, now(), now())",
            role, "ROLE_T_" + role.toString().substring(0, 8), "Caisse " + role.toString().substring(0, 8));
        UserRoleHospitalAssignment desk = new UserRoleHospitalAssignment();
        desk.setId(UUID.randomUUID());
        desk.setHospital(hospital);
        jdbc.update("""
            INSERT INTO security.user_role_hospital_assignment (id, user_id, role_id, hospital_id, is_active,
                assigned_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, true, now(), now(), now())
            """, desk.getId(), UUID.randomUUID(), role, hospitalId);
        when(assignments.findFirstByHospital_IdAndRole_CodeInAndActiveTrueOrderByCreatedAtAsc(eq(hospitalId), anyList()))
            .thenReturn(Optional.of(desk));

        LabTestDefinition tdr = new LabTestDefinition();
        tdr.setId(tdrId);
        tdr.setName("TDR paludisme");
        LabOrder order = new LabOrder();
        order.setId(UUID.randomUUID());
        order.setLabTestDefinition(tdr);
        when(labOrders.findIdsByEncounterIdAndStatusNotIn(eq(encounter.getId()), any())).thenReturn(List.of(order.getId()));
        when(labOrders.findAllById(List.of(order.getId()))).thenReturn(List.of(order));
        ImagingOrder chest = new ImagingOrder();
        chest.setModality(ImagingModality.XRAY);
        chest.setStudyType("Radio thorax");
        when(imaging.findByEncounterIdAndStatusNotIn(eq(encounter.getId()), any())).thenReturn(List.of(chest));
        UUID admissionId = UUID.randomUUID();
        encounter.setAdmissionId(admissionId);
        Admission stay = new Admission();
        stay.setHospital(hospital);
        stay.setAdmissionDateTime(LocalDateTime.of(2026, 11, 12, 10, 0));
        stay.setActualDischargeDateTime(LocalDateTime.of(2026, 11, 15, 8, 0));
        when(admissions.findById(admissionId)).thenReturn(Optional.of(stay));
        return encounter;
    }

    private Optional<UUID> checkOut(Encounter encounter, LocalDateTime at) {
        EncounterCheckedOutEvent event = new EncounterCheckedOutEvent(encounter.getId(), "ENC-" + at.toLocalDate(),
            encounter.getPatient().getId(), encounter.getHospital().getId(), null, null, "INPATIENT", at);
        return inTx(() -> listener.chargeVisit(event).map(com.example.hms.model.BaseEntity::getId));
    }

    private List<Map<String, Object>> linesOf(UUID invoiceId) {
        return jdbc.queryForList("""
            SELECT item_description, quantity, unit_price, total_price, price_source, price_list_item_id,
                   price_list_price_id, text_code
              FROM billing.invoice_items WHERE billing_invoice_id = ? ORDER BY item_description
            """, invoiceId);
    }

    @Test
    @DisplayName("acceptance 1: the checkout draft is priced from the list; a replay opens nothing; a later price leaves it and prices the next visit")
    void aCheckoutIsPricedFromTheList() {
        grid();
        Encounter first = visit(hospitalA);

        UUID draft = checkOut(first, LocalDateTime.of(2026, 11, 15, 12, 0)).orElseThrow();

        List<Map<String, Object>> lines = linesOf(draft);
        assertThat(lines).extracting(row -> row.get("item_description"), row -> row.get("price_source"))
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("CS-GEN (grille)", "TARIFF"),
                org.assertj.core.groups.Tuple.tuple("HOSP-JOUR (grille)", "TARIFF"),
                org.assertj.core.groups.Tuple.tuple("LAB-TDR (grille)", "TARIFF"),
                org.assertj.core.groups.Tuple.tuple("RX-THORAX (grille)", "TARIFF"));
        assertThat(lines).allSatisfy(row -> {
            assertThat(row.get("price_list_price_id")).isNotNull();
            assertThat(row.get("text_code")).isNull();
        });
        BigDecimal total = lines.stream().map(row -> (BigDecimal) row.get("total_price"))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(total).isEqualByComparingTo("26000");
        assertThat(jdbc.queryForObject("SELECT status FROM billing.billing_invoices WHERE id = ?", String.class, draft))
            .isEqualTo(InvoiceStatus.DRAFT.name());

        // The checkout event delivered again: still one invoice, the same prices.
        assertThat(checkOut(first, LocalDateTime.of(2026, 11, 15, 12, 0))).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.billing_invoices WHERE encounter_id = ?",
            Integer.class, first.getId())).isEqualTo(1);
        assertThat(linesOf(draft)).isEqualTo(lines);

        // D-4: the consultation goes to 2 500 from tomorrow. The posted draft keeps 2 000 ...
        UUID consultation = jdbc.queryForObject(
            "SELECT id FROM billing.price_list_items WHERE hospital_id = ? AND code = 'CS-GEN'", UUID.class, hospitalA);
        inTx(() -> service.addPrice(consultation, PriceListPriceRequestDTO.builder().unitPrice(new BigDecimal("2500"))
            .effectiveFrom(TODAY.plusDays(1)).changeReason("Hausse").build()));
        assertThat(linesOf(draft)).isEqualTo(lines);
        // ... and the next day's visit takes 2 500.
        UUID second = checkOut(visit(hospitalA), LocalDateTime.of(2026, 11, 16, 12, 0)).orElseThrow();
        assertThat(linesOf(second)).filteredOn(row -> "CS-GEN (grille)".equals(row.get("item_description")))
            .extracting(row -> (BigDecimal) row.get("unit_price"))
            .singleElement().satisfies(price -> assertThat(price).isEqualByComparingTo("2500"));
    }

    @Test
    @DisplayName("hospital B's visit is never priced by A's list, and a null hospital reads no list")
    void anotherHospitalIsNeverPricedByThisList() {
        grid();

        UUID draftB = checkOut(visit(hospitalB), LocalDateTime.of(2026, 11, 15, 12, 0)).orElseThrow();

        assertThat(linesOf(draftB)).extracting(row -> row.get("price_source")).containsOnly("UNPRICED");
        assertThat(inTx(() -> resolver.resolve(null, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT", TODAY)))
            .isEmpty();
        assertThat(inTx(() -> resolver.resolve(hospitalA, PriceListLinkType.SYSTEM, "CONSULTATION_DEFAULT", TODAY)))
            .isPresent();
    }

    @Test
    @DisplayName("#1179 review: a PROCEDURE_CODE link is stored in upper case and found from the order's own spelling")
    void aProcedureCodeIsNormalisedOnBothSides() {
        item("PROC-SUT", PriceListCategory.PROCEDURE, "3000", PriceListLinkType.PROCEDURE_CODE, "  sut-01 ");

        assertThat(jdbc.queryForObject("SELECT link_key FROM billing.price_list_items WHERE code = 'PROC-SUT' "
            + "AND hospital_id = ?", String.class, hospitalA)).isEqualTo("SUT-01");
        assertThat(inTx(() -> resolver.resolve(hospitalA, PriceListLinkType.PROCEDURE_CODE, "Sut-01", TODAY)))
            .isPresent();
    }

    @Test
    @DisplayName("#1179 review: a same-day correction is refused once an invoice line copied the version")
    void anInvoicedVersionIsNeverCorrected() {
        TariffResolver liveResolver = new TariffResolver(items, prices, Clock.systemDefaultZone(), "Africa/Ouagadougou");
        PriceListService liveService = new PriceListService(items, prices, new PriceListLinks(labTests, treatments),
            invoiceLines, labTests, treatments, mock(UserRepository.class), roles, scope, audit,
            Clock.systemDefaultZone(), "Africa/Ouagadougou");
        PriceListItemDTO item = inTx(() -> liveService.create(PriceListItemRequestDTO.builder().code("CS-GEN")
            .label("Consultation").category(PriceListCategory.CONSULTATION).unitPrice(new BigDecimal("2000"))
            .linkType(PriceListLinkType.SYSTEM).linkKey("CONSULTATION_DEFAULT").build()));
        TariffResolver.Tariff tariff = inTx(() -> liveResolver.resolve(hospitalA, PriceListLinkType.SYSTEM,
            "CONSULTATION_DEFAULT", liveResolver.today())).orElseThrow();
        UUID invoice = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, invoice_number, invoice_date, due_date,
                total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, current_date, current_date + 30, 2000, 0, 'DRAFT', false, now(), now())
            """, invoice, UUID.randomUUID(), hospitalA, "TWP-" + invoice.toString().substring(0, 8));
        jdbc.update("""
            INSERT INTO billing.invoice_items (id, billing_invoice_id, item_description, quantity, item_category,
                unit_price, total_price, assignment_id, price_source, price_list_item_id, price_list_price_id,
                created_at, updated_at)
            VALUES (?, ?, 'Consultation', 1, 'SERVICE', 2000, 2000, ?, 'TARIFF', ?, ?, now(), now())
            """, UUID.randomUUID(), invoice, UUID.randomUUID(), tariff.itemId(), tariff.priceId());

        assertThatThrownBy(() -> inTx(() -> liveService.addPrice(item.getId(), PriceListPriceRequestDTO.builder()
            .unitPrice(new BigDecimal("2200")).changeReason("Erreur de saisie").replaceScheduled(true).build())))
            .isInstanceOfSatisfying(FieldBusinessException.class, e -> {
                assertThat(e.getField()).isEqualTo("effectiveFrom");
                assertThat(e.getMessage()).contains("déjà été facturé");
            });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM billing.price_list_prices WHERE item_id = ?",
            Integer.class, item.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("V303: the provenance columns hold their rules in the database")
    void theV303ConstraintsHold() {
        UUID invoice = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.billing_invoices (id, patient_id, hospital_id, invoice_number, invoice_date, due_date,
                total_amount, amount_paid, status, check_in_copay, created_at, updated_at)
            VALUES (?, ?, ?, ?, current_date, current_date + 30, 0, 0, 'DRAFT', false, now(), now())
            """, invoice, UUID.randomUUID(), hospitalA, "TWP-" + invoice.toString().substring(0, 8));
        String line = """
            INSERT INTO billing.invoice_items (id, billing_invoice_id, item_description, quantity, item_category,
                unit_price, total_price, assignment_id, price_source, price_list_item_id, price_list_price_id,
                tariff_unit_price, override_reason, created_at, updated_at)
            VALUES (?, ?, 'Ligne', 1, 'SERVICE', 1000, 1000, ?, ?, ?, ?, ?, ?, now(), now())
            """;
        UUID item = UUID.randomUUID();
        UUID version = UUID.randomUUID();
        // A line written without a source is LEGACY (the column default for rows before V303).
        UUID legacy = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO billing.invoice_items (id, billing_invoice_id, item_description, quantity, item_category,
                unit_price, total_price, assignment_id, created_at, updated_at)
            VALUES (?, ?, 'Ancienne', 1, 'SERVICE', 0, 0, ?, now(), now())
            """, legacy, invoice, UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT price_source FROM billing.invoice_items WHERE id = ?", String.class,
            legacy)).isEqualTo("LEGACY");
        assertThatThrownBy(() -> jdbc.update(line, UUID.randomUUID(), invoice, UUID.randomUUID(), "BOGUS",
            null, null, null, null)).as("an unknown source").isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(line, UUID.randomUUID(), invoice, UUID.randomUUID(), "TARIFF",
            null, null, null, null)).as("a TARIFF line without its version")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(line, UUID.randomUUID(), invoice, UUID.randomUUID(), "TARIFF",
            item, null, null, null)).as("an item without its version")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(line, UUID.randomUUID(), invoice, UUID.randomUUID(), "MANUAL",
            item, version, new BigDecimal("2000"), " ")).as("an override without a reason")
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(line, UUID.randomUUID(), invoice, UUID.randomUUID(), "MANUAL",
            item, version, new BigDecimal("2000.50"), "Remise")).as("a tariff in decimals")
            .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update(line, UUID.randomUUID(), invoice, UUID.randomUUID(), "MANUAL", item, version,
            new BigDecimal("2000"), "Remise accordée");
        // The version a line names belongs to the item it names (FK triggers are off for this seed, so the
        // catalogue is read rather than the insert attempted).
        assertThat(jdbc.queryForObject(
            "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c WHERE c.conname = 'fk_item_price_list_price'",
            String.class)).isEqualTo("FOREIGN KEY (price_list_price_id, price_list_item_id) "
            + "REFERENCES billing.price_list_prices(id, item_id)");
    }
}
