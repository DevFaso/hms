package com.example.hms.service.impl;

import com.example.hms.HmsApplication;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.model.Admission;
import com.example.hms.model.Prescription;
import com.example.hms.payload.dto.PrescriptionResponseDTO;
import com.example.hms.payload.dto.nurse.NurseMedicationTaskResponseDTO;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.NurseTaskService;
import com.example.hms.service.PrescriptionService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1314: the ward's two heaviest reads, measured on a real PostgreSQL at
 * the scale the dev hospital reached (a real hospital reaches it within a
 * year): 15,000 prescriptions, 1,000 registered patients, 300 of them in a
 * bed, five of those with 240 live orders each.
 *
 * <p>Each measurement counts the JDBC statements Hibernate prepared, the wall
 * time and the bytes the calling thread allocated, and prints one
 * {@code LOAD|...} line, so the numbers in the PR body can be re-run. The
 * assertions are the guard: they fail when an N+1 returns. The other tests run
 * the queries D1314 wrote, every shape, and compare the batch reads with the
 * order-by-order rule they replaced: no row hidden, no row changed.
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@DisplayName("D1314: the ward's reads at dev's scale")
class WardLoadPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_load")
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
        registry.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
            StatementShapes.class::getName);
    }

    static final int PRESCRIPTIONS = 15_000;
    static final int REGISTERED_PATIENTS = 1_000;
    static final int WARD_PATIENTS = 300;
    static final int HEAVY_WARD_PATIENTS = 5;
    static final int ORDERS_OF_A_HEAVY_PATIENT = 240;
    static final int DOCTORS = 30;
    static final int NURSES = 20;
    static final int CATALOG_ITEMS = 100;
    /*
     * The guard. Each limit is what the read spends now, with a little room: none
     * of them grows with the rows, the patients or the ward. Before D1314 they were
     * 10,812 (MAR), 13,697 (tiles), 5,624 (vitals), 97 (a page) and 26,706 (every page).
     */
    static final int MAR_STATEMENTS = 15;
    static final int SUMMARY_STATEMENTS = 25;
    static final int VITALS_STATEMENTS = 6;
    static final int RX_PAGE_STATEMENTS = 6;
    static final int WORKBOARD_STATEMENTS = 25;
    static final int WHOLE_TAB_STATEMENTS = 1_500;
    /** The orders of {@code shapesTheRuleTellsApart} written at this hospital. */
    static final int SHAPE_ORDERS = 10;

    private static final UUID HOSPITAL = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID OTHER_HOSPITAL = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID ORGANIZATION = UUID.fromString("bbbbbbbb-0000-0000-0000-00000000000a");
    private static final Sort NEWEST_FIRST = Sort.by(Sort.Direction.DESC, "createdAt", "id");
    private static final List<String> FREQUENCIES = List.of("q8h", "3 fois par jour", "q12h", "toutes les 6 heures", "q24h");

    private static boolean seeded;
    private static UUID nurseUser;
    private static int wardRowsExpected;

    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManagerFactory emf;
    @Autowired private NurseTaskService nurseTasks;
    @Autowired private PrescriptionService prescriptions;
    @Autowired private com.example.hms.service.NurseDashboardService nurseDashboard;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private com.example.hms.repository.PrescriptionRepository prescriptionRepository;
    @Autowired private com.example.hms.repository.AdmissionRepository admissions;
    @Autowired private com.example.hms.repository.EncounterRepository encounters;
    @Autowired private com.example.hms.repository.PatientHospitalRegistrationRepository registrations;
    @Autowired private com.example.hms.repository.MedicationAdministrationRecordRepository doseRows;
    @Autowired private com.example.hms.repository.PatientVitalSignRepository vitals;
    @Autowired private com.example.hms.repository.MedicationCatalogItemRepository catalog;
    @Autowired private com.example.hms.service.pharmacy.PrescriptionControlledResolver controlledResolver;
    @Autowired private com.example.hms.service.emar.MarWardReader wardReader;

    /* ── the measurement ─────────────────────────────────────────────── */

    /** Which statements a read spent, by the table it selects from: how an N+1 shows itself. */
    public static final class StatementShapes implements org.hibernate.resource.jdbc.spi.StatementInspector {
        static final Map<String, java.util.concurrent.atomic.LongAdder> SEEN = new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.regex.Pattern FROM = java.util.regex.Pattern.compile("\\bfrom\\s++([\\w.\"]++)");

        @Override
        public String inspect(String sql) {
            java.util.regex.Matcher from = FROM.matcher(sql);
            SEEN.computeIfAbsent(from.find() ? from.group(1) : sql.substring(0, Math.min(40, sql.length())),
                k -> new java.util.concurrent.atomic.LongAdder()).increment();
            return sql;
        }

        static void printTop(String read) {
            SEEN.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue().sum(), a.getValue().sum()))
                .limit(8)
                .forEach(e -> System.out.printf(Locale.ROOT, "LOAD|  %s|%s=%d%n", read, e.getKey(), e.getValue().sum()));
        }
    }

    record Measured(String name, long statements, long millis, long allocatedMb, long rows) {
        void print() {
            System.out.printf(Locale.ROOT, "LOAD|%s|statements=%d|ms=%d|allocatedMb=%d|rows=%d%n",
                name, statements, millis, allocatedMb, rows);
        }
    }

    /** The second of two runs: the first pays for the JIT and the plan caches. */
    private Measured measure(String name, Supplier<Long> read) {
        read.get();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.clear();
        StatementShapes.SEEN.clear();
        com.sun.management.ThreadMXBean threads =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long thread = Thread.currentThread().threadId();
        long allocatedBefore = threads.getThreadAllocatedBytes(thread);
        long started = System.nanoTime();
        long rows = read.get();
        long millis = (System.nanoTime() - started) / 1_000_000;
        long allocated = threads.getThreadAllocatedBytes(thread) - allocatedBefore;
        Measured measured = new Measured(name, stats.getPrepareStatementCount(), millis, allocated / (1024 * 1024), rows);
        measured.print();
        StatementShapes.printTop(name);
        return measured;
    }

    @BeforeEach
    void seedOnceAndAct() {
        if (!seeded) {
            seed();
            seeded = true;
        }
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(nurseUser)
            .principalUsername("nurse0")
            .activeHospitalId(HOSPITAL)
            .permittedHospitalIds(Set.of(HOSPITAL))
            .build());
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "nurse0", "n/a", List.of(new SimpleGrantedAuthority("ROLE_NURSE"))));
    }

    @AfterEach
    void leave() {
        HospitalContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    /* ── the reads ───────────────────────────────────────────────────── */

    @Test
    @DisplayName("the MAR list of the whole ward")
    void marList() {
        Measured m = measure("mar-list", () -> {
            List<NurseMedicationTaskResponseDTO> rows = nurseTasks.getMedicationTasks(nurseUser, HOSPITAL, null);
            return (long) rows.size();
        });
        assertThat(m.rows()).isEqualTo(wardRowsExpected);
        assertThat(m.statements()).as("the N+1 over the ward is back").isLessThanOrEqualTo(MAR_STATEMENTS);
    }

    @Test
    @DisplayName("one page of the prescription list")
    void prescriptionPage() {
        Measured m = measure("rx-page-50", () -> page(0, 50).getNumberOfElements() + 0L);
        assertThat(m.rows()).isEqualTo(50);
        assertThat(m.statements()).as("the page reads per row again").isLessThanOrEqualTo(RX_PAGE_STATEMENTS);
    }

    @Test
    @DisplayName("the search box: one page of matches, read on the server")
    void prescriptionSearch() {
        Measured m = measure("rx-search", () ->
            prescriptions.search("paracetamol 7", List.of(), PageRequest.of(0, 50, NEWEST_FIRST), Locale.FRENCH)
                .getTotalElements());
        assertThat(m.rows()).isPositive();
        assertThat(m.statements()).as("the search reads per row again").isLessThanOrEqualTo(RX_PAGE_STATEMENTS);
    }

    @Test
    @DisplayName("the unsigned orders' tab: a prescriber is looked up once, not per order")
    void prescriptionDraftTab() {
        Measured m = measure("rx-draft-tab", () -> prescriptions.list(null, null, null, List.of(PrescriptionStatus.DRAFT),
            PageRequest.of(0, 50, NEWEST_FIRST), Locale.FRENCH).getNumberOfElements() + 0L);
        assertThat(m.rows()).isEqualTo(50);
        assertThat(m.statements()).isLessThanOrEqualTo(RX_PAGE_STATEMENTS + DOCTORS);
    }

    @Test
    @DisplayName("the workboard")
    void workboard() {
        Measured m = measure("workboard", () -> nurseTasks.getWorkboard(nurseUser, HOSPITAL).size() + 0L);
        assertThat(m.rows()).isEqualTo(WARD_PATIENTS + 1L);
        assertThat(m.statements()).isLessThanOrEqualTo(WORKBOARD_STATEMENTS);
    }

    @Test
    @DisplayName("the nurse's patient list, with its vitals and allergies")
    void nursePatients() {
        Measured m = measure("nurse-patients", () -> nurseDashboard.getPatientsForNurse(nurseUser, HOSPITAL, null).size() + 0L);
        assertThat(m.rows()).isEqualTo(REGISTERED_PATIENTS + 0L);
    }

    @Test
    @DisplayName("the day's visits (the tracker board's read)")
    void visitsOfTheDay() {
        Measured m = measure("visits-day", () -> new org.springframework.transaction.support.TransactionTemplate(transactions)
            .execute(tx -> encounters.findAllByHospitalAndDateRange(HOSPITAL, LocalDateTime.now().minusDays(2), LocalDateTime.now()).size() + 0L));
        assertThat(m.rows()).isPositive();
    }

    /* ── no row hidden, no row changed ───────────────────────────────── */

    /** The rule read order by order, as the list did before D1314: the batch read must say the same. */
    @Test
    @DisplayName("the MAR's rows are exactly what the order-by-order rule says, each with the same reading")
    void marListAgreesWithTheRuleOrderByOrder() {
        List<NurseMedicationTaskResponseDTO> listed = nurseTasks.getMedicationTasks(nurseUser, HOSPITAL, null);
        LocalDateTime now = LocalDateTime.now();

        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            Map<UUID, Admission> stays = new java.util.HashMap<>();
            for (Admission stay : admissions.findActiveAdmissionsByHospital(HOSPITAL)) {
                stays.putIfAbsent(stay.getPatient().getId(), stay);
            }
            Map<UUID, Prescription> expected = new java.util.HashMap<>();
            for (var row : registrations.findActiveNamesForHospital(HOSPITAL)) {
                List<Admission> closed = admissions.findByPatient_IdAndHospital_Id(row.patientId(), HOSPITAL).stream()
                    .filter(a -> a.getActualDischargeDateTime() != null).toList();
                for (Prescription rx : prescriptionRepository.findByPatient_IdAndHospital_Id(row.patientId(), HOSPITAL)) {
                    if (NurseTaskServiceImpl.onWardMar(rx, closed, stays.get(row.patientId()))) {
                        expected.put(rx.getId(), rx);
                    }
                }
            }
            assertThat(listed).extracting(NurseMedicationTaskResponseDTO::getId)
                .containsExactlyInAnyOrderElementsOf(expected.keySet());
            for (NurseMedicationTaskResponseDTO task : listed) {
                Prescription rx = expected.get(task.getId());
                var rows = doseRows.findByPrescription_IdOrderByScheduledTimeDesc(rx.getId());
                var due = MarNextDose.of(rx, now, rows, NurseTaskServiceImpl.EARLY_ADMINISTRATION_WINDOW);
                assertThat(task.getDueTime()).as("due time of %s", rx.getId()).isEqualTo(due.dueTime());
                assertThat(task.getStatus()).isEqualTo(NurseTaskServiceImpl.resolveMarStatus(due, now));
                assertThat(task.isControlledSubstance()).isEqualTo(controlledResolver.isControlled(rx));
                assertThat(task.getDoseHistory()).extracting(h -> h.getId()).containsExactlyElementsOf(
                    rows.stream().filter(r -> r.getStatus() != com.example.hms.enums.MedicationAdministrationStatus.PENDING)
                        .limit(5).map(r -> r.getId()).toList());
                assertThat(task.getDoseHistory()).allSatisfy(h -> assertThat(h.getAdministeredBy()).isNotBlank());
                assertThat(task.getCdsAdvisories()).isNotNull();
            }
            // Soonest due first, and a finished course's row keeps its place at the end.
            assertThat(listed).extracting(NurseMedicationTaskResponseDTO::getDueTime)
                .isSortedAccordingTo(java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder()));
        });
    }

    @Test
    @DisplayName("the names are the full read's names, for the same patients")
    void namesAgreeWithTheFullRead() {
        var full = nurseDashboard.getPatientsForNurse(nurseUser, HOSPITAL, null);
        var names = nurseDashboard.getPatientNamesForNurse(nurseUser, HOSPITAL);
        assertThat(names).hasSameSizeAs(full);
        assertThat(names).extracting(n -> n.getId() + "=" + n.getDisplayName())
            .containsExactlyInAnyOrderElementsOf(full.stream().map(f -> f.getId() + "=" + f.getDisplayName()).toList());
    }

    /* ── every query, every combination, on PostgreSQL ───────────────── */

    private static final String ACCENTED = com.example.hms.utility.PatientMatchKeys.nameContainsPattern("MORPHINE 100%_x");
    private static final PageRequest FIRST = PageRequest.of(0, 50, NEWEST_FIRST);

    @Test
    @DisplayName("the four search queries: found at this hospital only, by status, accents and wildcards as typed")
    void searchQueries() {
        Runnable inTx = () -> {
            assertThat(prescriptionRepository.searchAtHospital(HOSPITAL, ACCENTED, FIRST).getTotalElements()).isEqualTo(1);
            assertThat(prescriptionRepository.searchEveryHospital(ACCENTED, FIRST).getTotalElements()).isEqualTo(2);
            assertThat(prescriptionRepository.searchAtHospitalInStatuses(HOSPITAL, List.of(PrescriptionStatus.DISPENSED), ACCENTED, FIRST)
                .getTotalElements()).isEqualTo(1);
            assertThat(prescriptionRepository.searchAtHospitalInStatuses(HOSPITAL, List.of(PrescriptionStatus.DRAFT), ACCENTED, FIRST)
                .getTotalElements()).isZero();
            assertThat(prescriptionRepository.searchEveryHospitalInStatuses(List.of(PrescriptionStatus.DISPENSED), ACCENTED, FIRST)
                .getTotalElements()).isEqualTo(2);
            assertThat(prescriptionRepository.searchEveryHospitalInStatuses(List.of(PrescriptionStatus.DRAFT), ACCENTED, FIRST)
                .getTotalElements()).isZero();
            // A typed % or _ is that character, not a wildcard.
            assertThat(prescriptionRepository.searchAtHospital(HOSPITAL,
                com.example.hms.utility.PatientMatchKeys.nameContainsPattern("100%x"), FIRST).getTotalElements()).isZero();
            // Nobody matches.
            assertThat(prescriptionRepository.searchAtHospital(HOSPITAL,
                com.example.hms.utility.PatientMatchKeys.nameContainsPattern("zzzz-nothing"), FIRST).getTotalElements()).isZero();
        };
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> inTx.run());
    }

    @Test
    @DisplayName("the search box finds a patient's name, the name in either order, a prescriber's, accents ignored")
    void searchFindsPeople() {
        assertThat(found("kiemde0")).as("patient, accent ignored").isPositive();
        assertThat(found("PÉLAGIE KIEMDÉ0")).as("first then last").isPositive();
        assertThat(found("kiemde0 pelagie")).as("last then first").isPositive();
        assertThat(found("awa docteur3")).as("the prescriber").isPositive();
        assertThat(found("paracetamol")).as("the drug, accent ignored").isPositive();
        assertThat(found("nadia")).as("a nurse wrote none").isZero();
        // A blank search is the list.
        assertThat(found("   ")).isEqualTo(page(0, 1).getTotalElements());
        // The rows carry the same fields as the list's.
        PrescriptionResponseDTO row = prescriptions.search("mörphine", List.of(), FIRST, Locale.FRENCH).getContent().get(0);
        assertThat(row.getPatientFullName()).isNotBlank();
        assertThat(row.getStaffFullName()).startsWith("Awa");
        assertThat(row.getHospitalId()).isEqualTo(HOSPITAL);
    }

    private long found(String term) {
        return prescriptions.search(term, List.of(), FIRST, Locale.FRENCH).getTotalElements();
    }

    @Test
    @DisplayName("the catalogue's flag: item, code, nothing to ask, another hospital's line")
    void controlledAmong() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            List<Prescription> sample = prescriptionRepository.findByHospital_Id(HOSPITAL, PageRequest.of(0, 500, NEWEST_FIRST)).getContent();
            java.util.Set<UUID> batch = controlledResolver.controlledAmong(sample);
            assertThat(batch).isNotEmpty();
            for (Prescription rx : sample) {
                assertThat(batch.contains(rx.getId())).as("order %s", rx.getId()).isEqualTo(controlledResolver.isControlled(rx));
            }
            assertThat(controlledResolver.controlledAmong(List.of())).isEmpty();
        });
        // Each list empty on its own, and both.
        List<UUID> none = List.of(UUID.randomUUID());
        assertThat(catalog.findControlledAmong(List.of(HOSPITAL), List.of(), List.of("nothing"))).isEmpty();
        assertThat(catalog.findControlledAmong(List.of(HOSPITAL), none, List.of())).isEmpty();
        assertThat(catalog.findControlledAmong(List.of(HOSPITAL), List.of(), List.of())).isEmpty();
        // A code only reads this hospital's line.
        assertThat(catalog.findControlledAmong(List.of(HOSPITAL), List.of(), List.of("CAT-0"))).hasSize(1);
        assertThat(catalog.findControlledAmong(List.of(HOSPITAL), List.of(), List.of("CAT-1"))).isEmpty();
        assertThat(catalog.findControlledAmong(List.of(OTHER_HOSPITAL), List.of(), List.of("CAT-1"))).hasSize(1);
    }

    @Test
    @DisplayName("the latest observation sets: not a weight alone, not another hospital's, none is no entry")
    void latestObservationSets() {
        new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(tx -> {
            List<UUID> patients = registrations.findActiveNamesForHospital(HOSPITAL).stream()
                .map(r -> r.patientId()).toList();
            Map<UUID, com.example.hms.model.PatientVitalSign> batch = wardReader.latestObservationSets(HOSPITAL, patients);
            for (UUID patient : patients) {
                var single = vitals.findLatestObservationSet(patient, HOSPITAL);
                assertThat(batch.containsKey(patient)).isEqualTo(single.isPresent());
                single.ifPresent(s -> assertThat(batch.get(patient).getRecordedAt()).isEqualTo(s.getRecordedAt()));
            }
            assertThat(wardReader.latestObservationSets(HOSPITAL, List.of())).isEmpty();
        });
    }

    /** What the JVM's native parts hold with the whole application booted and these reads done: the runbook's budget rests on it. */
    @Test
    @DisplayName("the native memory the booted application holds, printed")
    void nativeUse() {
        nurseTasks.getMedicationTasks(nurseUser, HOSPITAL, null);
        page(0, 50);
        for (var pool : ManagementFactory.getMemoryPoolMXBeans()) {
            if (pool.getType() == java.lang.management.MemoryType.NON_HEAP) {
                System.out.printf(Locale.ROOT, "LOAD|jvm|%s|usedMb=%d|committedMb=%d%n", pool.getName(),
                    pool.getUsage().getUsed() / (1024 * 1024), pool.getUsage().getCommitted() / (1024 * 1024));
            }
        }
        for (var buffers : ManagementFactory.getPlatformMXBeans(java.lang.management.BufferPoolMXBean.class)) {
            System.out.printf(Locale.ROOT, "LOAD|jvm|buffers %s|usedMb=%d%n", buffers.getName(), buffers.getMemoryUsed() / (1024 * 1024));
        }
        int threads = ManagementFactory.getThreadMXBean().getPeakThreadCount();
        System.out.printf(Locale.ROOT, "LOAD|jvm|peakThreads=%d%n", threads);
        assertThat(threads).isPositive();
    }

    @Test
    @DisplayName("the plans the ward and the list run on, printed")
    void plans() {
        for (String sql : List.of(
            "SELECT * FROM clinical.prescriptions WHERE hospital_id = '" + HOSPITAL + "' ORDER BY created_at DESC, id DESC LIMIT 50",
            "SELECT count(*) FROM clinical.prescriptions WHERE hospital_id = '" + HOSPITAL + "'",
            "SELECT * FROM clinical.prescriptions WHERE hospital_id = '" + HOSPITAL + "' AND status IN ('SIGNED','TRANSMITTED') ORDER BY created_at DESC, id DESC LIMIT 50",
            "SELECT p.* FROM clinical.prescriptions p LEFT JOIN clinical.encounters e ON e.id = p.encounter_id WHERE p.hospital_id = '" + HOSPITAL
                + "' AND p.status IN ('SIGNED','TRANSMITTED') AND (p.patient_id IN (SELECT a.patient_id FROM public.admissions a WHERE a.hospital_id = '" + HOSPITAL
                + "' AND a.status IN ('ACTIVE','ON_LEAVE')) OR (e.encounter_type = 'EMERGENCY' AND e.status NOT IN ('COMPLETED','CANCELLED','LEFT_WITHOUT_BEING_SEEN')))")) {
            List<String> plan = jdbc.queryForList("EXPLAIN (ANALYZE, COSTS OFF, TIMING OFF, SUMMARY ON) " + sql, String.class);
            System.out.println("LOAD|plan|" + sql.substring(0, Math.min(90, sql.length())));
            plan.forEach(line -> System.out.println("EXPLAIN| " + line));
        }
        assertThat(jdbc.queryForList("SELECT indexname FROM pg_indexes WHERE schemaname = 'clinical' AND tablename = 'prescriptions'", String.class))
            .isNotEmpty()
            .allSatisfy(name -> System.out.println("EXPLAIN| index " + name));
    }

    @Test
    @DisplayName("the search box's read: every page of the tab")
    void prescriptionWholeTabRead() {
        Measured m = measure("rx-whole-tab", () -> {
            long read = 0;
            int number = 0;
            Page<PrescriptionResponseDTO> rows;
            do {
                rows = page(number++, 200);
                read += rows.getNumberOfElements();
            } while (rows.hasNext());
            return read;
        });
        assertThat(m.rows()).isEqualTo(PRESCRIPTIONS + 0L);
        assertThat(m.statements()).as("a page reads per row again").isLessThanOrEqualTo(WHOLE_TAB_STATEMENTS);
    }

    @Test
    @DisplayName("the nurse station's tiles")
    void dashboardSummary() {
        Measured m = measure("nurse-summary", () -> nurseTasks.getDashboardSummary(nurseUser, HOSPITAL).getMedicationsDue() + 0L);
        assertThat(m.statements()).as("the tiles read per patient again").isLessThanOrEqualTo(SUMMARY_STATEMENTS);
    }

    @Test
    @DisplayName("the vitals round list")
    void vitalsDue() {
        Measured m = measure("vitals-due", () -> nurseTasks.getDueVitals(nurseUser, HOSPITAL, null).size() + 0L);
        assertThat(m.statements()).as("the round reads per patient again").isLessThanOrEqualTo(VITALS_STATEMENTS);
    }

    private Page<PrescriptionResponseDTO> page(int number, int size) {
        return prescriptions.list(null, null, null, List.of(), PageRequest.of(number, size, NEWEST_FIRST), Locale.FRENCH);
    }

    /* ── the seed ────────────────────────────────────────────────────── */

    private void seed() {
        long started = System.nanoTime();
        jdbc.execute((ConnectionCallback<Void>) conn -> {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (var st = conn.createStatement()) {
                st.execute("SET session_replication_role = replica");
            }
            new Seed(conn).run();
            try (var st = conn.createStatement()) {
                st.execute("SET session_replication_role = origin");
            }
            conn.commit();
            conn.setAutoCommit(auto);
            return null;
        });
        jdbc.execute("ANALYZE");
        System.out.printf(Locale.ROOT, "LOAD|seed|ms=%d%n", (System.nanoTime() - started) / 1_000_000);
    }

    /** Rows by hand, every NOT NULL column the row does not name filled by its type. */
    private final class Seed {
        private final Connection conn;
        private final Map<String, Table> tables = new LinkedHashMap<>();
        private final LocalDateTime now = LocalDateTime.now();
        private long counter;

        private final List<UUID> doctors = new ArrayList<>();
        private final List<UUID> doctorAssignments = new ArrayList<>();
        private final List<UUID> nurses = new ArrayList<>();
        private final List<UUID> catalogItems = new ArrayList<>();
        private final List<UUID> patients = new ArrayList<>();
        private final UUID nurseRole = UUID.randomUUID();
        private final UUID doctorRole = UUID.randomUUID();

        Seed(Connection conn) {
            this.conn = conn;
        }

        void run() throws SQLException {
            hospitals();
            roles();
            staff();
            catalog();
            patientsAndRegistrations();
            wardAndHistory();
            for (Table table : tables.values()) {
                table.flush();
            }
        }

        private void hospitals() throws SQLException {
            insert("hospital.organizations", Map.of("id", ORGANIZATION, "name", "Org", "code", "ORG-LOAD",
                "type", "COMMUNITY_HOSPITAL"));
            for (UUID id : List.of(HOSPITAL, OTHER_HOSPITAL)) {
                insert("hospital.hospitals", Map.of("id", id, "name", "Hospital " + id, "code", "H-" + id,
                    "organization_id", ORGANIZATION));
            }
        }

        private void roles() throws SQLException {
            jdbcUpdateRole(nurseRole, "ROLE_NURSE");
            jdbcUpdateRole(doctorRole, "ROLE_DOCTOR");
        }

        private void jdbcUpdateRole(UUID id, String code) throws SQLException {
            try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM \"security\".roles WHERE code = ?")) {
                ps.setString(1, code);
                try (var rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if ("ROLE_NURSE".equals(code)) {
                            seededNurseRole = (UUID) rs.getObject(1);
                        } else {
                            seededDoctorRole = (UUID) rs.getObject(1);
                        }
                        return;
                    }
                }
            }
            insert("\"security\".roles", Map.of("id", id, "code", code, "name", code));
            if ("ROLE_NURSE".equals(code)) {
                seededNurseRole = id;
            } else {
                seededDoctorRole = id;
            }
        }

        private UUID seededNurseRole;
        private UUID seededDoctorRole;

        private void staff() throws SQLException {
            for (int i = 0; i < DOCTORS + NURSES; i++) {
                boolean doctor = i < DOCTORS;
                UUID user = UUID.randomUUID();
                UUID role = doctor ? seededDoctorRole : seededNurseRole;
                String label = (doctor ? "doctor" : "nurse") + (doctor ? i : i - DOCTORS);
                insert("\"security\".users", Map.of("id", user, "username", label, "email", label + "@load.test",
                    "first_name", doctor ? "Awa" : "Nadia", "last_name", (doctor ? "Docteur" : "Infirmière") + i,
                    "is_active", true));
                insert("\"security\".user_roles", Map.of("user_id", user, "role_id", role));
                UUID assignment = UUID.randomUUID();
                insert("\"security\".user_role_hospital_assignment", Map.of("id", assignment, "user_id", user,
                    "role_id", role, "hospital_id", HOSPITAL, "is_active", true));
                UUID staff = UUID.randomUUID();
                insert("hospital.staff", Map.of("id", staff, "user_id", user, "hospital_id", HOSPITAL,
                    "assignment_id", assignment, "active", true, "employment_type", "FULL_TIME",
                    "job_title", doctor ? "DOCTOR" : "NURSE"));
                if (doctor) {
                    doctors.add(staff);
                    doctorAssignments.add(assignment);
                } else {
                    nurses.add(staff);
                    if (nurseUser == null) {
                        nurseUser = user;
                    }
                }
            }
        }

        private void catalog() throws SQLException {
            for (int i = 0; i < CATALOG_ITEMS; i++) {
                UUID id = UUID.randomUUID();
                catalogItems.add(id);
                insert("clinical.medication_catalog_items", Map.of("id", id, "hospital_id", HOSPITAL,
                    "name_fr", "Médicament " + i, "code", "CAT-" + i, "controlled", i % 10 == 0, "active", true));
            }
        }

        private void patientsAndRegistrations() throws SQLException {
            for (int i = 0; i < REGISTERED_PATIENTS; i++) {
                UUID patient = UUID.randomUUID();
                patients.add(patient);
                UUID user = UUID.randomUUID();
                insert("\"security\".users", Map.of("id", user, "username", "patient" + i, "email", "patient" + i + "@load.test",
                    "first_name", i % 7 == 0 ? "Pélagie" : "Prénom" + i, "last_name", "Kiemdé" + i, "is_active", true));
                insert("clinical.patients", Map.of("id", patient, "user_id", user,
                    "first_name", i % 7 == 0 ? "Pélagie" : "Prénom" + i, "last_name", "Kiemdé" + i,
                    "date_of_birth", LocalDate.of(1980, 1, 1).plusDays(i), "email", "patient" + i + "@load.test",
                    "phone_number_primary", "+2267" + (1000000 + i)));
                insert("clinical.patient_hospital_registrations", Map.of("id", UUID.randomUUID(), "patient_id", patient,
                    "hospital_id", HOSPITAL, "mrn", "MRN" + i, "is_active", true, "stay_status", "REGISTERED",
                    "registration_date", now.toLocalDate().minusDays(i % 300)));
            }
        }

        private void wardAndHistory() throws SQLException {
            int written = 0;
            wardRowsExpected = 0;
            for (int i = 0; i < WARD_PATIENTS; i++) {
                UUID patient = patients.get(i);
                UUID stay = admission(patient, "ACTIVE", now.minusDays(3), null);
                UUID encounter = encounter(patient, HOSPITAL, stay, "INPATIENT", "IN_PROGRESS");
                // Vitals behind the "last vitals" of a ward row.
                for (int v = 0; v < 3; v++) {
                    insert("clinical.patient_vital_signs", Map.of("id", UUID.randomUUID(), "patient_id", patient,
                        "hospital_id", HOSPITAL, "recorded_at", now.minusHours(v * 6L + 1), "heart_rate_bpm", 80,
                        "spo2_percent", 97));
                }
                if (i % 5 == 0) {
                    insert("clinical.patient_allergies", Map.of("id", UUID.randomUUID(), "patient_id", patient,
                        "hospital_id", HOSPITAL, "allergen_display", "Pénicilline", "severity", "MODERATE",
                        "verification_status", "CONFIRMED", "active", true));
                }
                int orders = i < HEAVY_WARD_PATIENTS ? ORDERS_OF_A_HEAVY_PATIENT : 2;
                for (int o = 0; o < orders; o++) {
                    UUID rx = prescription(patient, HOSPITAL, encounter, written, "SIGNED", now.minusDays(2));
                    doses(rx, patient, 6);
                    written++;
                    wardRowsExpected++;
                }
                // An earlier stay of one patient in three: its orders ended with it, some left SIGNED.
                if (i % 3 == 0) {
                    UUID oldStay = admission(patient, "DISCHARGED", now.minusDays(60), now.minusDays(55));
                    UUID oldEncounter = encounter(patient, HOSPITAL, oldStay, "INPATIENT", "COMPLETED");
                    for (int o = 0; o < 4; o++) {
                        prescription(patient, HOSPITAL, oldEncounter, written++, "SIGNED", now.minusDays(58));
                    }
                }
            }
            // Twenty patients in the emergency department, no bed: their orders are on the MAR too.
            for (int i = WARD_PATIENTS; i < WARD_PATIENTS + 20; i++) {
                UUID patient = patients.get(i);
                UUID encounter = encounter(patient, HOSPITAL, null, "EMERGENCY", "IN_PROGRESS");
                for (int o = 0; o < 2; o++) {
                    UUID rx = prescription(patient, HOSPITAL, encounter, written++, "SIGNED", now.minusHours(3));
                    doses(rx, patient, 2);
                    wardRowsExpected++;
                }
            }
            shapesTheRuleTellsApart();
            written += SHAPE_ORDERS;
            // The rest of the year: outpatient visits, signed take-home orders and closed ones.
            List<UUID> outpatientVisits = new ArrayList<>();
            for (int i = WARD_PATIENTS + 20; i < REGISTERED_PATIENTS; i++) {
                outpatientVisits.add(encounter(patients.get(i), HOSPITAL, null, "OUTPATIENT", "COMPLETED"));
            }
            List<String> statuses = List.of(PrescriptionStatus.SIGNED.name(), PrescriptionStatus.DISPENSED.name(),
                PrescriptionStatus.DISPENSED.name(), PrescriptionStatus.CANCELLED.name(), PrescriptionStatus.DRAFT.name());
            int visit = 0;
            while (written < PRESCRIPTIONS) {
                int owner = WARD_PATIENTS + 20 + visit % outpatientVisits.size();
                prescription(patients.get(owner), HOSPITAL, outpatientVisits.get(visit % outpatientVisits.size()),
                    written, statuses.get(written % statuses.size()), now.minusDays(written % 365L));
                written++;
                visit++;
            }
            // Another hospital's order for a patient in a bed here: never on this hospital's lists.
            UUID elsewhere = encounter(patients.get(0), OTHER_HOSPITAL, null, "OUTPATIENT", "IN_PROGRESS");
            for (int o = 0; o < 20; o++) {
                foreignRx(patients.get(0), elsewhere);
            }
        }

        /**
         * The orders onWardMar tells apart: what the SQL narrows to must keep
         * every order it lists and need not keep the others. {@link #SHAPE_ORDERS} rows.
         */
        private void shapesTheRuleTellsApart() throws SQLException {
            // In a bed: an order written since the admission on a visit with no stay link.
            UUID inBed = patients.get(10);
            UUID openOutpatient = encounter(inBed, HOSPITAL, null, "OUTPATIENT", "IN_PROGRESS");
            prescription(inBed, HOSPITAL, openOutpatient, 1, "SIGNED", now.minusDays(1));
            UUID inpatientUnlinked = encounter(inBed, HOSPITAL, null, "INPATIENT", "IN_PROGRESS");
            prescription(inBed, HOSPITAL, inpatientUnlinked, 3, "TRANSMITTED", now.minusDays(1));
            wardRowsExpected++;
            // A live order written before this stay, on no stay's encounter: no ward dose (D702).
            prescription(inBed, HOSPITAL, inpatientUnlinked, 5, "SIGNED", now.minusDays(20));
            // An active order on a stay's own closed encounter, marked take-home (D757): not ended, but not this stay's.
            UUID closedStay = admission(inBed, "DISCHARGED", now.minusDays(40), now.minusDays(35));
            UUID closedEncounter = encounter(inBed, HOSPITAL, closedStay, "INPATIENT", "COMPLETED");
            Map<String, Object> takeHome = rxRow(inBed, HOSPITAL, closedEncounter, 7, "SIGNED", now.minusDays(38));
            takeHome.put("take_home", true);
            insert("clinical.prescriptions", takeHome);
            // On leave is still a stay.
            UUID onLeave = patients.get(WARD_PATIENTS + 20);
            UUID leaveStay = admission(onLeave, "ON_LEAVE", now.minusDays(2), null);
            UUID leaveEncounter = encounter(onLeave, HOSPITAL, leaveStay, "INPATIENT", "IN_PROGRESS");
            prescription(onLeave, HOSPITAL, leaveEncounter, 9, "SIGNED", now.minusDays(1));
            wardRowsExpected++;
            // No bed: a closed emergency visit's order, and a live outpatient order, are nobody's dose.
            UUID noBed = patients.get(WARD_PATIENTS + 21);
            UUID closedEmergency = encounter(noBed, HOSPITAL, null, "EMERGENCY", "COMPLETED");
            prescription(noBed, HOSPITAL, closedEmergency, 11, "SIGNED", now.minusDays(1));
            UUID outpatient = encounter(noBed, HOSPITAL, null, "OUTPATIENT", "IN_PROGRESS");
            prescription(noBed, HOSPITAL, outpatient, 13, "SIGNED", now.minusDays(1));
            // A stay in another hospital is no bed here.
            UUID elsewhereOnly = patients.get(WARD_PATIENTS + 22);
            UUID foreignStay = UUID.randomUUID();
            insert("public.admissions", Map.of("id", foreignStay, "patient_id", elsewhereOnly, "hospital_id", OTHER_HOSPITAL,
                "admitting_provider_id", doctors.get(0), "admission_date_time", now.minusDays(2), "status", "ACTIVE",
                "chief_complaint", "load", "acuity_level", "LEVEL_3_MAJOR", "admission_type", "EMERGENCY"));
            UUID outHere = encounter(elsewhereOnly, HOSPITAL, null, "OUTPATIENT", "IN_PROGRESS");
            prescription(elsewhereOnly, HOSPITAL, outHere, 15, "SIGNED", now.minusDays(1));
            // A cancelled order of a patient in a bed.
            prescription(inBed, HOSPITAL, inpatientUnlinked, 17, "CANCELLED", now.minusDays(1));
            // A weight alone does not restart the round's clock (D1181); the other hospital's set is not this one's.
            insert("clinical.patient_vital_signs", Map.of("id", UUID.randomUUID(), "patient_id", inBed,
                "hospital_id", HOSPITAL, "recorded_at", now.minusMinutes(1), "weight_kg", 70));
            insert("clinical.patient_vital_signs", Map.of("id", UUID.randomUUID(), "patient_id", inBed,
                "hospital_id", OTHER_HOSPITAL, "recorded_at", now.minusSeconds(30), "heart_rate_bpm", 90));
            // The catalogue line with a code only, at another hospital: its flag is not this hospital's.
            insert("clinical.medication_catalog_items", Map.of("id", UUID.randomUUID(), "hospital_id", OTHER_HOSPITAL,
                "name_fr", "Autre", "code", "CAT-1", "controlled", true, "active", true));
            // Found by the search box only through its prescriber's name, patient's name or an accent.
            UUID searched = patients.get(WARD_PATIENTS + 23);
            UUID searchVisit = encounter(searched, HOSPITAL, null, "OUTPATIENT", "COMPLETED");
            Map<String, Object> morphine = rxRow(searched, HOSPITAL, searchVisit, 19, "DISPENSED", now.minusDays(1));
            morphine.put("medication_name", "Mörphine 100%_x");
            insert("clinical.prescriptions", morphine);
            // And an order of another hospital the search must not find.
            Map<String, Object> foreignMorphine = rxRow(searched, OTHER_HOSPITAL, searchVisit, 21, "DISPENSED", now.minusDays(1));
            foreignMorphine.put("medication_name", "Mörphine 100%_x");
            insert("clinical.prescriptions", foreignMorphine);
        }

        private void foreignRx(UUID patient, UUID encounter) throws SQLException {
            Map<String, Object> row = rxRow(patient, OTHER_HOSPITAL, encounter, 0, "SIGNED", now.minusDays(1));
            insert("clinical.prescriptions", row);
        }

        private UUID admission(UUID patient, String status, LocalDateTime from, LocalDateTime to) throws SQLException {
            UUID id = UUID.randomUUID();
            Map<String, Object> row = new LinkedHashMap<>(Map.of("id", id, "patient_id", patient, "hospital_id", HOSPITAL,
                "admitting_provider_id", doctors.get(0), "admission_date_time", from, "status", status,
                "chief_complaint", "load", "acuity_level", "LEVEL_3_MAJOR", "admission_type", "EMERGENCY"));
            if (to != null) {
                row.put("actual_discharge_date_time", to);
            }
            insert("public.admissions", row);
            return id;
        }

        private UUID encounter(UUID patient, UUID hospital, UUID stay, String type, String status) throws SQLException {
            UUID id = UUID.randomUUID();
            Map<String, Object> row = new LinkedHashMap<>(Map.of("id", id, "patient_id", patient, "hospital_id", hospital,
                "staff_id", doctors.get(0), "assignment_id", doctorAssignments.get(0), "code", "ENC-" + id,
                "encounter_type", type, "status", status, "encounter_date", now.minusDays(1)));
            if (stay != null) {
                row.put("admission_id", stay);
            }
            insert("clinical.encounters", row);
            return id;
        }

        private UUID prescription(UUID patient, UUID hospital, UUID encounter, int index, String status,
                                  LocalDateTime written) throws SQLException {
            Map<String, Object> row = rxRow(patient, hospital, encounter, index, status, written);
            insert("clinical.prescriptions", row);
            return (UUID) row.get("id");
        }

        private Map<String, Object> rxRow(UUID patient, UUID hospital, UUID encounter, int index, String status,
                                          LocalDateTime written) {
            int prescriber = index % doctors.size();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", UUID.randomUUID());
            row.put("patient_id", patient);
            row.put("hospital_id", hospital);
            row.put("encounter_id", encounter);
            row.put("staff_id", doctors.get(prescriber));
            row.put("assignment_id", doctorAssignments.get(prescriber));
            row.put("medication_name", "Paracétamol " + index % 50);
            row.put("medication_display_name", "Paracétamol " + index % 50);
            row.put("dosage", "500 mg");
            row.put("route", "ORAL");
            row.put("frequency", FREQUENCIES.get(index % FREQUENCIES.size()));
            row.put("status", status);
            row.put("created_at", written);
            row.put("updated_at", written);
            if (!"DRAFT".equals(status)) {
                row.put("signed_at", written);
            }
            if (index % 2 == 0) {
                row.put("medication_catalog_item_id", catalogItems.get(index % catalogItems.size()));
            } else if (index % 5 == 0) {
                row.put("medication_code", "CAT-" + index % CATALOG_ITEMS);
            }
            return row;
        }

        private void doses(UUID rx, UUID patient, int count) throws SQLException {
            for (int d = 0; d < count; d++) {
                boolean pending = d == count - 1;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", UUID.randomUUID());
                row.put("prescription_id", rx);
                row.put("patient_id", patient);
                row.put("hospital_id", HOSPITAL);
                row.put("medication_name", "Paracétamol");
                row.put("scheduled_time", now.minusHours((count - d) * 8L));
                row.put("status", pending ? "PENDING" : "GIVEN");
                if (!pending) {
                    row.put("administered_at", now.minusHours((count - d) * 8L));
                    row.put("administered_by_staff_id", nurses.get((int) (counter % nurses.size())));
                }
                row.put("created_at", now.minusHours((count - d) * 8L));
                row.put("updated_at", now.minusHours((count - d) * 8L));
                insert("clinical.medication_administration_records", row);
            }
        }

        private void insert(String table, Map<String, Object> row) throws SQLException {
            counter++;
            tables.computeIfAbsent(table, Table::new).row(row);
        }

        /** One table's pending rows, written in batches. */
        private final class Table {
            private final String name;
            private final Map<String, String> required = new TreeMap<>();
            private final List<Map<String, Object>> pending = new ArrayList<>();

            Table(String name) {
                this.name = name;
                String[] parts = name.replace("\"", "").split("\\.");
                jdbc.query("SELECT column_name, data_type FROM information_schema.columns WHERE table_schema = ? "
                        + "AND table_name = ? AND is_nullable = 'NO' AND column_default IS NULL",
                    rs -> {
                        required.put(rs.getString(1), rs.getString(2));
                    }, parts[0], parts[1]);
            }

            void row(Map<String, Object> row) throws SQLException {
                Map<String, Object> full = new TreeMap<>(row);
                required.forEach((column, type) -> full.computeIfAbsent(column, c -> filler(type)));
                pending.add(full);
                if (pending.size() >= 1_000) {
                    flush();
                }
            }

            private Object filler(String type) {
                return switch (type) {
                    case "uuid" -> UUID.randomUUID();
                    case "character varying", "text" -> "f" + Long.toString(counter++, 36);
                    case "boolean" -> Boolean.FALSE;
                    case "integer", "bigint", "smallint", "numeric", "double precision" -> 0;
                    case "timestamp without time zone", "timestamp with time zone" -> now;
                    case "date" -> now.toLocalDate();
                    case "jsonb" -> new JsonText("{}");
                    default -> throw new IllegalStateException(name + ": no filler for " + type);
                };
            }

            void flush() throws SQLException {
                Map<List<String>, List<Map<String, Object>>> byShape = new LinkedHashMap<>();
                for (Map<String, Object> row : pending) {
                    byShape.computeIfAbsent(List.copyOf(row.keySet()), k -> new ArrayList<>()).add(row);
                }
                pending.clear();
                for (var shape : byShape.entrySet()) {
                    List<String> columns = shape.getKey();
                    String sql = "INSERT INTO " + name + " (" + String.join(", ", columns) + ") VALUES ("
                        + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
                    try (PreparedStatement ps = conn.prepareStatement(sql)) {
                        for (Map<String, Object> row : shape.getValue()) {
                            int i = 1;
                            for (String column : columns) {
                                bind(ps, i++, row.get(column));
                            }
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
            }

            private void bind(PreparedStatement ps, int index, Object value) throws SQLException {
                if (value instanceof LocalDateTime time) {
                    ps.setTimestamp(index, Timestamp.valueOf(time));
                } else if (value instanceof LocalDate date) {
                    ps.setDate(index, java.sql.Date.valueOf(date));
                } else if (value instanceof JsonText json) {
                    ps.setObject(index, json.text(), Types.OTHER);
                } else {
                    ps.setObject(index, value);
                }
            }
        }
    }

    private record JsonText(String text) {
    }
}
