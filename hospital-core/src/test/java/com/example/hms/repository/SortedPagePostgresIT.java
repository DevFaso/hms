package com.example.hms.repository;

import com.example.hms.config.StablePageOrderConfig;
import com.example.hms.fhir.provider.MedicationRequestFhirResourceProvider;
import com.example.hms.fhir.provider.ObservationFhirResourceProvider;
import com.example.hms.model.Notification;
import com.example.hms.payload.dto.PermissionFilterDTO;
import com.example.hms.security.tenant.TenantContextAccessor;
import com.example.hms.service.ReceptionServiceImpl;
import com.example.hms.service.impl.NurseTaskServiceImpl;
import com.example.hms.service.impl.SuperAdminOrganizationOverviewServiceImpl;
import com.example.hms.specification.PermissionSpecification;
import com.example.hms.specification.UserRoleHospitalAssignmentSpecification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.repository.Repository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * QA D1295 (round 28) on a real PostgreSQL built by Liquibase: every query
 * this round gave an order (the nurse inbox, and the D1295 class sweep of
 * {@code UnsortedPageGuardTest}) runs with the exact page or sort its caller
 * passes, for every parameter combination its callers use (nulls included).
 * The inbox is seeded and its order asserted; the others are run to prove the
 * ORDER BY the sort appends is valid SQL on their query (a {@code SELECT
 * DISTINCT} included).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StablePageOrderConfig.class, TenantContextAccessor.class})
@DisplayName("D1295: the ordered pages run on PostgreSQL")
class SortedPagePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_sorted_page")
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
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector",
            CapturedSql.class::getName);
    }

    @Autowired private NotificationRepository notifications;
    @Autowired private PrescriptionRepository prescriptions;
    @Autowired private LabResultRepository labResults;
    @Autowired private BillingInvoiceRepository invoices;
    @Autowired private EncounterRepository encounters;
    @Autowired private StaffRepository staff;
    @Autowired private PatientRepository patients;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private PermissionRepository permissions;
    @Autowired private LabOrderRepository labOrders;
    @Autowired private ApplicationContext context;
    @Autowired private ChatMessageRepository chatMessages;
    // QA D1379 / D1380 / D1390: the reads behind the screens that now page on the server.
    @Autowired private PatientVitalSignRepository vitals;
    @Autowired private ImagingOrderRepository imagingOrders;
    @Autowired private NursingNoteRepository nursingNotes;
    @Autowired private com.example.hms.repository.scheduling.AppointmentSlotRepository slots;
    @Autowired private TreatmentPlanRepository treatmentPlans;
    @Autowired private PatientUploadedDocumentRepository documents;
    @Autowired private ObgynReferralRepository obgynReferrals;

    private final List<UUID> savedNotifications = new ArrayList<>();

    @AfterEach
    void clear() {
        notifications.deleteAllById(savedNotifications);
    }

    @Test
    @DisplayName("the nurse inbox: every unread row newest first, then the read history newest first, capped")
    void nurseInboxIsOrdered() {
        LocalDateTime now = LocalDateTime.of(2026, 11, 2, 15, 28);
        // Written oldest last, so insertion order cannot pass for the sort.
        for (int i = 0; i < 25; i++) {
            save("nurse.d1295", "unread " + i, false, now.minusHours(48L - i));
        }
        save("nurse.d1295", "NEWS2 16", false, now);
        for (int i = 0; i < 30; i++) {
            save("nurse.d1295", "read " + i, true, now.minusHours(60L - i));
        }
        save("someone.else", "not mine", false, now.plusMinutes(1));

        List<Notification> unread = notifications.findByRecipientUsernameAndReadOrderByCreatedAtDesc("nurse.d1295", false);
        assertThat(unread).hasSize(26);
        assertThat(unread.get(0).getMessage()).isEqualTo("NEWS2 16");
        assertThat(unread).isSortedAccordingTo((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));

        List<Notification> history = notifications.findByRecipientUsernameAndRead("nurse.d1295", true,
            PageRequest.of(0, 20, NurseTaskServiceImpl.INBOX_NEWEST_FIRST)).getContent();
        assertThat(history).hasSize(20).allMatch(Notification::isRead);
        assertThat(history.get(0).getMessage()).isEqualTo("read 29");
        assertThat(history).isSortedAccordingTo((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
    }

    @Test
    @DisplayName("every other ordered page of the D1295 sweep is valid SQL on its query")
    void everySweptQueryRunsWithItsSort() {
        UUID any = UUID.randomUUID();
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);
        assertThatCode(() -> {
            prescriptions.findByEncounter_Id(any, PrescriptionRepository.EVERY_ORDER_OF_THE_VISIT);
            prescriptions.findByPatient_Id(any, PageRequest.of(0, 200, MedicationRequestFhirResourceProvider.NEWEST_FIRST));
            labResults.findPageByLabOrder_Patient_IdAndLabOrder_Hospital_Id(any, any,
                PageRequest.of(0, 250, ObservationFhirResourceProvider.LAB_NEWEST_FIRST));
            invoices.findByPatient_IdAndHospital_Id(any, any, ReceptionServiceImpl.EVERY_INVOICE_NEWEST_FIRST);
            encounters.findVisitsByHospitalAndDateBetween(any, from, to, PageRequest.of(0, 500, Sort.by("id")));
            Sort oldest = SuperAdminOrganizationOverviewServiceImpl.OLDEST_FIRST;
            staff.findByHospitalIdExcludingDeletedUsers(any, PageRequest.of(0, 5, oldest));
            staff.findByHospitalIdAndActiveTrueExcludingDeletedUsers(any, PageRequest.of(0, 5, oldest));
            for (Boolean active : new Boolean[]{null, Boolean.TRUE, Boolean.FALSE}) {
                patients.findPageByHospitalIdAndActive(any, active, PageRequest.of(0, 5, oldest));
            }
            PageRequest newestAssignment = PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "assignedAt", "id"));
            assignments.findAll(newestAssignment);
            assignments.findAll(UserRoleHospitalAssignmentSpecification.belongsToHospital(any)
                .and(UserRoleHospitalAssignmentSpecification.hasActive(true))
                .and(UserRoleHospitalAssignmentSpecification.matchesSearch("awa"))
                .and(UserRoleHospitalAssignmentSpecification.hasAssignmentCode("A-1")), newestAssignment);
            assignments.findAll(UserRoleHospitalAssignmentSpecification.hasActive(false), newestAssignment);
            PageRequest byName = PageRequest.of(0, 20, Sort.by("name", "id"));
            permissions.findAll(PermissionSpecification.build(new PermissionFilterDTO(null, null)), byName);
            permissions.findAll(PermissionSpecification.build(new PermissionFilterDTO(any, "read")), byName);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("QA D1347: the lab-order worklist and the encounter list page on a total order, every filter combination")
    void worklistsPageOnATotalOrder() {
        UUID any = UUID.randomUUID();
        LocalDateTime from = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime to = from.plusMonths(1);
        PageRequest page = PageRequest.of(1, 200);
        assertThatCode(() -> {
            // The portal reads every page of GET /lab-orders (hospital scoped,
            // optionally one patient; super-admin global view has no hospital).
            for (UUID hospital : new UUID[]{null, any}) {
                for (UUID patient : new UUID[]{null, any}) {
                    for (LocalDateTime[] range : new LocalDateTime[][]{{null, null}, {from, null}, {null, to}, {from, to}}) {
                        assertThat(labOrders.search(hospital, patient, range[0], range[1], page).getContent()).isEmpty();
                    }
                }
            }
            // EncounterService.list / listForPatient send sort=encounterDate,desc&sort=id,desc.
            encounters.findAll(PageRequest.of(1, 200, Sort.by(Sort.Direction.DESC, "encounterDate", "id")));
            // The desk's payment panel sends sort=createdAt,desc&sort=id,desc.
            PageRequest newestInvoice = PageRequest.of(1, 200, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
            invoices.findByPatient_IdAndHospital_Id(any, any, newestInvoice);
            invoices.findByPatient_Id(any, newestInvoice);
            // StaffService.list(hospitalId) sends sort=id.
            staff.findByHospitalIdAndActiveTrueExcludingDeletedUsers(any, PageRequest.of(1, 200, Sort.by("id")));
        }).doesNotThrowAnyException();
    }

    /**
     * Entities whose primary key is not called {@code id}: their pages end on
     * the WHOLE key instead (an {@code @IdClass} or {@code @EmbeddedId}, or a
     * natural key), which is just as total. Each entry is the ORDER BY its
     * SQL must carry ({@code Repository#method}); shrinks only.
     */
    private static final Map<String, String> ENDED_ON_ANOTHER_KEY = Map.of(
        "ImagingReportAttachmentRepository#findAll",
        "order by ira1_0.created_at desc,ira1_0.report_id desc,ira1_0.position desc offset",
        "RegionPolicyRepository#findAll", "order by rp1_0.region offset",
        "UserRoleRepository#findAll", "order by ur1_0.role_id,ur1_0.user_id offset");

    private static final Pattern LAST_KEY_IS_ID = Pattern.compile(
        "\\border by\\b(?:(?!\\border by\\b).)*[.\\s,]id(?: asc| desc)?(?: nulls (?:first|last))?\\s*+(?:offset|fetch|limit)\\b");

    /**
     * QA D1361 (round 31, the D1347 class): {@code GET /lab-results} paged a
     * query with no order, so a page-by-page read repeated 16 of 202 results
     * and never returned 16 others. Every Spring Data repository now runs its
     * paged reads through {@code StablePageOrderConfig}; this drives EVERY
     * repository method that takes a {@code Pageable} - derived, JPQL,
     * Specification, inherited and custom - on PostgreSQL with a second page
     * and no sort (the D1361 call), then with a sort that does not name
     * {@code id}, and requires the SQL each one sends to end its ORDER BY on
     * an {@code id}. A new paged query that is not totally ordered, or a
     * repository that escapes the choke point, fails here.
     */
    @Test
    @DisplayName("QA D1361: every paged repository read, every repository, ends its order on the unique id")
    void everyPagedRepositoryReadEndsOnId() {
        Map<String, String> failures = new TreeMap<>();
        Set<String> excused = new java.util.LinkedHashSet<>();
        int methods = 0;
        for (Object bean : context.getBeansOfType(Repository.class).values()) {
            for (Class<?> type : bean.getClass().getInterfaces()) {
                if (!type.getName().startsWith("com.example.hms.repository")) {
                    continue;
                }
                for (Method method : type.getMethods()) {
                    Object[] arguments = arguments(method);
                    if (arguments.length == 0) {
                        continue;
                    }
                    methods++;
                    String site = type.getSimpleName() + "#" + method.getName();
                    String problem = runPaged(bean, method, arguments);
                    if (problem == null) {
                        continue;
                    }
                    if (ENDED_ON_ANOTHER_KEY.containsKey(site) && problem.contains(ENDED_ON_ANOTHER_KEY.get(site))) {
                        excused.add(site);
                    } else {
                        failures.merge(site, problem, (a, b) -> a);
                    }
                }
            }
        }
        assertThat(methods).as("paged repository methods driven").isGreaterThan(200);
        assertThat(failures)
            .as("a paged read whose order does not end on the unique id repeats rows across pages and skips"
                + " others (QA D1361, GET /lab-results); every repository goes through StablePageOrderConfig")
            .isEmpty();
        assertThat(excused).as("an entry that no longer pages on its whole key: delete or fix it")
            .containsExactlyInAnyOrderElementsOf(ENDED_ON_ANOTHER_KEY.keySet());
    }

    /** Null when every paged statement the call sends ends its order on an id, else what went wrong. */
    private static String runPaged(Object bean, Method method, Object[] arguments) {
        for (Pageable page : new Pageable[]{PageRequest.of(1, 5), PageRequest.of(1, 5, Sort.by(Sort.Direction.DESC, "createdAt"))}) {
            // The sorted run is for the methods whose root is the repository's
            // entity for certain (derived, Specification, inherited); a query's
            // own select list is its author's, and createdAt may not be in it.
            if (page.getSort().isSorted()
                && (method.isAnnotationPresent(org.springframework.data.jpa.repository.Query.class) || !hasCreatedAt(method))) {
                continue;
            }
            for (int i = 0; i < arguments.length; i++) {
                if (arguments[i] instanceof Pageable) {
                    arguments[i] = page;
                }
            }
            CapturedSql.clear();
            try {
                method.invoke(bean, arguments);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                while (cause.getCause() != null) {
                    cause = cause.getCause();
                }
                return "threw " + cause.getClass().getSimpleName() + ": " + cause.getMessage();
            } catch (IllegalAccessException e) {
                return "not callable: " + e.getMessage();
            }
            List<String> paged = CapturedSql.statements().stream()
                .map(sql -> String.join(" ", sql.toLowerCase(Locale.ROOT).strip().split("\\s++")))
                .filter(sql -> sql.startsWith("select") && sql.contains(" offset "))
                .toList();
            if (paged.isEmpty()) {
                return "sent no paged statement (" + page + ")";
            }
            for (String sql : paged) {
                Matcher last = LAST_KEY_IS_ID.matcher(sql);
                if (!last.find()) {
                    return "order does not end on an id (" + page + "): " + sql.substring(Math.max(0, sql.indexOf(" from ")));
                }
            }
        }
        return null;
    }

    /** The repository's entity has a {@code createdAt} to sort on (the second, sorted, run). */
    private static boolean hasCreatedAt(Method method) {
        for (Type generic : method.getDeclaringClass().getGenericInterfaces()) {
            if (generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> entity) {
                for (Class<?> c = entity; c != null; c = c.getSuperclass()) {
                    for (java.lang.reflect.Field field : c.getDeclaredFields()) {
                        if ("createdAt".equals(field.getName())) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Arguments to call a paged method with: a real value for each simple
     * parameter, null for an entity. Empty when the method takes no
     * {@code Pageable}, or takes a parameter this cannot build (a query by
     * example, a projection class).
     */
    private static Object[] arguments(Method method) {
        Class<?>[] types = method.getParameterTypes();
        if (!List.of(types).contains(Pageable.class)) {
            return new Object[0];
        }
        Type[] generics = method.getGenericParameterTypes();
        Object[] out = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            if (types[i] == org.springframework.data.domain.Example.class || types[i] == Class.class) {
                return new Object[0];
            }
            out[i] = types[i] == Pageable.class ? PageRequest.of(1, 5) : sample(types[i], generics[i]);
        }
        return out;
    }

    private static final LocalDateTime SAMPLE_TIME = LocalDateTime.of(2026, 1, 1, 8, 0);

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object sample(Class<?> type, Type generic) {
        Map<Class<?>, Object> simple = Map.ofEntries(
            Map.entry(UUID.class, UUID.fromString("00000000-0000-0000-0000-000000001361")),
            Map.entry(String.class, "d1361"),
            Map.entry(boolean.class, false), Map.entry(Boolean.class, false),
            Map.entry(int.class, 1), Map.entry(Integer.class, 1),
            Map.entry(long.class, 1L), Map.entry(Long.class, 1L),
            Map.entry(short.class, (short) 1), Map.entry(Short.class, (short) 1),
            Map.entry(double.class, 1d), Map.entry(Double.class, 1d),
            Map.entry(BigDecimal.class, BigDecimal.ONE),
            Map.entry(LocalDate.class, SAMPLE_TIME.toLocalDate()),
            Map.entry(LocalDateTime.class, SAMPLE_TIME),
            Map.entry(LocalTime.class, SAMPLE_TIME.toLocalTime()),
            Map.entry(Instant.class, SAMPLE_TIME.toInstant(ZoneOffset.UTC)),
            Map.entry(OffsetDateTime.class, SAMPLE_TIME.atOffset(ZoneOffset.UTC)),
            Map.entry(ZonedDateTime.class, SAMPLE_TIME.atZone(ZoneOffset.UTC)));
        if (simple.containsKey(type)) {
            return simple.get(type);
        }
        if (type.isEnum()) {
            return type.getEnumConstants()[0];
        }
        if (type == Specification.class) {
            return Specification.unrestricted();
        }
        if (Collection.class.isAssignableFrom(type)) {
            Object element = generic instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments()[0] instanceof Class<?> elementType
                ? sample(elementType, elementType) : null;
            List list = element == null ? Collections.emptyList() : List.of(element);
            return Set.class.isAssignableFrom(type) ? new java.util.HashSet<>(list) : new ArrayList<>(list);
        }
        return null;
    }

    /** Every SQL statement Hibernate prepares, kept for the D1361 check. */
    public static final class CapturedSql implements StatementInspector {

        private static final List<String> STATEMENTS = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String inspect(String sql) {
            STATEMENTS.add(sql);
            return sql;
        }

        static void clear() {
            STATEMENTS.clear();
        }

        static List<String> statements() {
            synchronized (STATEMENTS) {
                return new ArrayList<>(STATEMENTS);
            }
        }
    }

    @Test
    @DisplayName("QA D1361: GET /lab-results pages newest result first, closed by id, for every filter combination")
    void labResultListPagesOnATotalOrder() {
        UUID any = UUID.randomUUID();
        UUID none = new UUID(0L, 0L);
        // LabResultServiceImpl.readLabResultsPage with no caller sort.
        PageRequest page = PageRequest.of(1, 200, Sort.by(Sort.Direction.DESC, "resultDate"));
        assertThatCode(() -> {
            for (boolean releasedOnly : new boolean[]{false, true}) {
                for (UUID patient : new UUID[]{null, any}) {
                    for (UUID order : new UUID[]{null, any}) {
                        CapturedSql.clear();
                        labResults.findHandledByHospitalForReader(any, releasedOnly, patient == null,
                            patient == null ? none : patient, order == null, order == null ? none : order, page);
                        labResults.findAllForReader(releasedOnly, patient == null,
                            patient == null ? none : patient, order == null, order == null ? none : order, page);
                        assertThat(CapturedSql.statements().stream()
                            .map(sql -> String.join(" ", sql.toLowerCase(Locale.ROOT).strip().split("\\s++")))
                            .filter(sql -> sql.contains(" offset ")))
                            .hasSize(2)
                            .allMatch(sql -> sql.contains("result_date desc,") && LAST_KEY_IS_ID.matcher(sql).find());
                    }
                }
            }
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("QA D1361: the chat queries that page by hand (LIMIT/OFFSET) run with their id tiebreak")
    void handPagedChatQueriesRun() {
        UUID any = UUID.randomUUID();
        assertThatCode(() -> {
            assertThat(chatMessages.findLatestMessagesForUserNative(any, 5, 5)).isEmpty();
            assertThat(chatMessages.findLatestConversations(any, 5, 5)).isEmpty();
            assertThat(chatMessages.searchMessagesBetweenUsers(any, any, "d1361",
                PageRequest.of(1, 5, Sort.by("timestamp").descending())).getContent()).isEmpty();
        }).doesNotThrowAnyException();
    }

    /**
     * QA D1379 / D1380 / D1390 (the D1347 class): the chart review, the doctor
     * timeline and the slot search now read every row WHOLE (unpaged, the
     * same filters) and count it before they cut a page, and /my-vitals pages
     * the patient's history with a total. Each read runs here on PostgreSQL
     * for every parameter combination its callers use, nulls included, and
     * the whole reads send no OFFSET/LIMIT (nothing cut at the database).
     */
    @Test
    @DisplayName("QA D1379/D1380/D1390: the whole reads behind the server-paged screens run on PostgreSQL, uncut")
    void wholeReadsBehindTheServerPagedScreensRun() {
        UUID any = UUID.randomUUID();
        Set<UUID> readable = Set.of(any, UUID.randomUUID());
        LocalDate day = LocalDate.of(2026, 11, 3);
        LocalDateTime now = day.atTime(8, 0);
        Sort newestResult = Sort.by(Sort.Direction.DESC, "resultDate", "id");
        Sort newestCreated = Sort.by(Sort.Direction.DESC, "createdAt");
        assertThatCode(() -> {
            CapturedSql.clear();
            // ChartReviewServiceImpl: acting hospital (readable set) and the super-admin global view.
            assertThat(encounters.findVisitsByPatientIdAndHospitalIdIn(any, readable, Pageable.unpaged()).getContent()).isEmpty();
            assertThat(encounters.findVisitsByPatientId(any, Pageable.unpaged()).getContent()).isEmpty();
            for (UUID acting : new UUID[]{null, any}) {
                assertThat(labResults.findPatientResultsReadableAt(any, readable, acting, false,
                    Pageable.unpaged(newestResult))).isEmpty();
            }
            assertThat(labResults.findAllPatientResults(any, Pageable.unpaged(newestResult))).isEmpty();
            assertThat(prescriptions.findByPatient_IdAndHospital_IdIn(any, readable, Pageable.unpaged(newestCreated))
                .getContent()).isEmpty();
            assertThat(prescriptions.findByPatient_Id(any, Pageable.unpaged(newestCreated)).getContent()).isEmpty();
            assertThat(imagingOrders.findByPatient_IdAndHospital_IdInOrderByOrderedAtDesc(any, readable, Pageable.unpaged())
                .getContent()).isEmpty();
            assertThat(imagingOrders.findByPatient_IdOrderByOrderedAtDesc(any, Pageable.unpaged()).getContent()).isEmpty();
            assertThat(nursingNotes.findByPatient_IdAndHospital_IdInOrderByCreatedAtDesc(any, readable)).isEmpty();
            assertThat(nursingNotes.findByPatient_IdOrderByCreatedAtDesc(any)).isEmpty();
            // VisitTimelineRows (the doctor timeline): every row of the kind, newest first.
            assertThat(treatmentPlans.findAllByPatientIdAndHospitalId(any, any, Pageable.unpaged(newestCreated))
                .getContent()).isEmpty();
            assertThat(documents.findForStaffByType(any, any,
                com.example.hms.enums.PatientDocumentType.MEDICAL_CERTIFICATE, Pageable.unpaged(newestCreated))
                .getContent()).isEmpty();
            assertThat(obgynReferrals.findByPatient_IdAndHospital_IdIn(any, Set.of(any), Pageable.unpaged(newestCreated))
                .getContent()).isEmpty();
            // SlotInventoryServiceImpl.openSlots / AppointmentSlotPolicy.openOn: every filter combination.
            for (UUID department : new UUID[]{null, any}) {
                for (UUID clinician : new UUID[]{null, any}) {
                    for (UUID visitType : new UUID[]{null, any}) {
                        assertThat(slots.searchOpen(any, department, clinician, visitType, day, day.plusWeeks(2),
                            now, now, Pageable.unpaged())).isEmpty();
                    }
                }
            }
            assertThat(CapturedSql.statements().stream()
                .map(sql -> sql.toLowerCase(Locale.ROOT))
                .filter(sql -> sql.contains(" offset ") || sql.contains(" fetch first ") || sql.contains(" limit ")))
                .as("a whole read is never cut at the database")
                .isEmpty();
            // /my-vitals: a page of the patient's history WITH its total, closed by id.
            CapturedSql.clear();
            assertThat(vitals.findPageByPatient_IdOrderByRecordedAtDesc(any, PageRequest.of(1, 10)).getTotalElements())
                .isZero();
            assertThat(CapturedSql.statements().stream()
                .map(sql -> String.join(" ", sql.toLowerCase(Locale.ROOT).strip().split("\\s++")))
                .filter(sql -> sql.contains(" offset ")))
                .isNotEmpty()
                .allMatch(sql -> sql.contains("recorded_at desc") && LAST_KEY_IS_ID.matcher(sql).find());
        }).doesNotThrowAnyException();
    }

    private void save(String recipient, String message, boolean read, LocalDateTime at) {
        Notification row = notifications.save(Notification.builder()
            .recipientUsername(recipient).message(message).read(read).createdAt(at).build());
        savedNotifications.add(row.getId());
    }
}
