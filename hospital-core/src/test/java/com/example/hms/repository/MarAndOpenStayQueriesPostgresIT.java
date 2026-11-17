package com.example.hms.repository;

import com.example.hms.config.StablePageOrderConfig;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.model.Encounter;
import com.example.hms.model.MedicationAdministrationRecord;
import com.example.hms.security.tenant.TenantContextAccessor;
import com.example.hms.service.InpatientOrderEnd;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two JPQL queries round 33 wrote, on a real PostgreSQL built by Liquibase,
 * for every shape their one caller meets: {@code findOpenStayEncounters} (QA
 * D1393, the lookup that files an order written with no encounter) and the MAR
 * rows' total order (QA D1409, the "last dose" of two doses in one minute).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({StablePageOrderConfig.class, TenantContextAccessor.class})
@DisplayName("D1393, D1409: the lookups run on PostgreSQL")
class MarAndOpenStayQueriesPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_open_stay")
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

    private static final UUID HOSPITAL = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001");
    private static final UUID OTHER_HOSPITAL = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000002");
    private static final UUID PATIENT = UUID.fromString("cccccccc-0000-0000-0000-000000000001");
    private static final UUID OTHER_PATIENT = UUID.fromString("cccccccc-0000-0000-0000-000000000002");
    private static final UUID STAFF = UUID.fromString("dddddddd-0000-0000-0000-000000000001");
    private static final UUID ASSIGNMENT = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001");
    private static final UUID RX = UUID.fromString("22222222-0000-0000-0000-000000000001");

    @Autowired private EncounterRepository encounters;
    @Autowired private MedicationAdministrationRecordRepository mar;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void noRows() {
        for (String table : List.of("clinical.medication_administration_records", "clinical.prescriptions",
                "clinical.encounters", "public.admissions")) {
            replica("DELETE FROM " + table);
        }
    }

    private void replica(String sql, Object... args) {
        // One connection for the role and the write: session_replication_role is per session.
        jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) conn -> {
            try (var st = conn.createStatement()) {
                st.execute("SET session_replication_role = replica");
            }
            try (var ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    ps.setObject(i + 1, args[i]);
                }
                ps.executeUpdate();
            }
            try (var st = conn.createStatement()) {
                st.execute("SET session_replication_role = origin");
            }
            return null;
        });
    }

    private UUID admission(AdmissionStatus status) {
        UUID id = UUID.randomUUID();
        replica("INSERT INTO public.admissions (id, patient_id, hospital_id, admitting_provider_id, "
            + "admission_date_time, chief_complaint, acuity_level, admission_type, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, now(), 'test', 'LEVEL_3_MAJOR', 'EMERGENCY', ?, now(), now())",
            id, PATIENT, HOSPITAL, STAFF, status.name());
        return id;
    }

    private UUID encounter(UUID patient, UUID hospital, UUID admissionId, EncounterStatus status, String date) {
        UUID id = UUID.randomUUID();
        replica("INSERT INTO clinical.encounters (id, patient_id, hospital_id, staff_id, assignment_id, code, "
            + "encounter_type, status, encounter_date, admission_id, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, 'INPATIENT', ?, ?::timestamp, ?, now(), now())",
            id, patient, hospital, STAFF, ASSIGNMENT, "ENC-" + id, status.name(), date, admissionId);
        return id;
    }

    private List<UUID> openStays(UUID patient, UUID hospital) {
        return encounters.findOpenStayEncounters(patient, hospital, EncounterStatus.CLOSED,
                InpatientOrderEnd.ENDED_STAY_STATUSES)
            .stream().map(Encounter::getId).toList();
    }

    @ParameterizedTest
    // QA D1516 (changed: CLOSED_IN_ERROR added): a stay a voided death closed has ended, as InpatientOrderEnd says.
    @EnumSource(value = AdmissionStatus.class, names = {"CANCELLED", "DISCHARGED", "CLOSED_IN_ERROR"})
    void anOpenEncounterOfAnEndedAdmissionIsNoOpenStay(AdmissionStatus ended) {
        encounter(PATIENT, HOSPITAL, admission(ended), EncounterStatus.IN_PROGRESS, "2026-11-05 08:00:00");

        assertThat(openStays(PATIENT, HOSPITAL)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AdmissionStatus.class, mode = EnumSource.Mode.EXCLUDE, names = {"CANCELLED", "DISCHARGED", "CLOSED_IN_ERROR"})
    void anOpenEncounterOfAnyOtherAdmissionIsTheOpenStay(AdmissionStatus live) {
        UUID stay = encounter(PATIENT, HOSPITAL, admission(live), EncounterStatus.IN_PROGRESS, "2026-11-05 08:00:00");

        assertThat(openStays(PATIENT, HOSPITAL)).containsExactly(stay);
    }

    @ParameterizedTest
    @EnumSource(value = EncounterStatus.class, names = {"COMPLETED", "CANCELLED", "LEFT_WITHOUT_BEING_SEEN"})
    void aClosedEncounterOfALiveAdmissionIsNoOpenStay(EncounterStatus closed) {
        encounter(PATIENT, HOSPITAL, admission(AdmissionStatus.ACTIVE), closed, "2026-11-05 08:00:00");

        assertThat(openStays(PATIENT, HOSPITAL)).isEmpty();
    }

    /** A re-admission after a cancel: the live stay is found under the cancelled one dated later. */
    @Test
    void theLiveStayIsFoundBehindACancelledOneLatestFirst() {
        encounter(PATIENT, HOSPITAL, admission(AdmissionStatus.CANCELLED), EncounterStatus.IN_PROGRESS, "2026-11-06 08:00:00");
        // One open admission per patient is a database rule; two encounters of it can both be open.
        UUID live = admission(AdmissionStatus.ACTIVE);
        UUID older = encounter(PATIENT, HOSPITAL, live, EncounterStatus.IN_PROGRESS, "2026-11-01 08:00:00");
        UUID newer = encounter(PATIENT, HOSPITAL, live, EncounterStatus.AWAITING_RESULTS, "2026-11-05 08:00:00");

        assertThat(openStays(PATIENT, HOSPITAL)).containsExactly(newer, older);
    }

    /** {@code admission_id} carries no key: an admission hard-deleted does not end a stay, as before this lookup. */
    @Test
    void aStayWhoseAdmissionRowIsGoneStaysOpen() {
        UUID stay = encounter(PATIENT, HOSPITAL, UUID.randomUUID(), EncounterStatus.IN_PROGRESS, "2026-11-05 08:00:00");

        assertThat(openStays(PATIENT, HOSPITAL)).containsExactly(stay);
    }

    @Test
    void aVisitWithNoAdmissionLinkAnotherPatientAndAnotherHospitalAreNotReturned() {
        UUID live = admission(AdmissionStatus.ACTIVE);
        encounter(PATIENT, HOSPITAL, null, EncounterStatus.IN_PROGRESS, "2026-11-05 08:00:00");
        encounter(OTHER_PATIENT, HOSPITAL, live, EncounterStatus.IN_PROGRESS, "2026-11-05 08:00:00");
        encounter(PATIENT, OTHER_HOSPITAL, live, EncounterStatus.IN_PROGRESS, "2026-11-05 08:00:00");

        assertThat(openStays(PATIENT, HOSPITAL)).isEmpty();
    }

    private UUID dose(String status, String slot, String administeredAt, String createdAt) {
        UUID id = UUID.randomUUID();
        replica("INSERT INTO clinical.medication_administration_records (id, prescription_id, patient_id, hospital_id, "
            + "medication_name, scheduled_time, administered_at, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'Morphine', ?::timestamp, ?::timestamp, ?, ?::timestamp, ?::timestamp)",
            id, RX, PATIENT, HOSPITAL, slot, administeredAt, status, createdAt, createdAt);
        return id;
    }

    /** QA D1409: both doses share their slot; the one recorded last is the last dose, whatever the insert order. */
    @Test
    void ofTwoDosesInOneMinuteTheOneRecordedLastComesFirst() {
        String slot = "2026-11-06 10:00:00";
        UUID earlier = dose("GIVEN", slot, "2026-11-06 10:00:05", "2026-11-06 10:00:05");
        UUID later = dose("GIVEN", slot, "2026-11-06 10:00:40", "2026-11-06 10:00:40");
        UUID olderSlot = dose("GIVEN", "2026-11-06 06:00:00", "2026-11-06 06:02:00", "2026-11-06 06:02:00");
        UUID pendingSameSlot = dose("PENDING", slot, null, "2026-11-06 09:59:00");
        UUID newerSlotPending = dose("PENDING", "2026-11-06 16:00:00", null, "2026-11-06 10:01:00");

        assertThat(mar.findByPrescription_IdOrderByScheduledTimeDesc(RX))
            .extracting(MedicationAdministrationRecord::getId)
            .containsExactly(newerSlotPending, later, earlier, pendingSameSlot, olderSlot);
    }

    /** The same stamp too (a clock of second precision): created_at, then the id, still give one order on every read. */
    @Test
    void identicalStampsStillReadInOneOrder() {
        String at = "2026-11-06 10:00:00";
        UUID first = dose("GIVEN", at, at, at);
        UUID second = dose("GIVEN", at, at, at);
        List<UUID> byId = List.of(first, second).stream().sorted(java.util.Comparator.comparing(UUID::toString).reversed()).toList();

        for (int read = 0; read < 3; read++) {
            assertThat(mar.findByPrescription_IdOrderByScheduledTimeDesc(RX))
                .extracting(MedicationAdministrationRecord::getId).containsExactlyElementsOf(byId);
        }
    }

    /**
     * A wall-clock time as the application stores it: hibernate.jdbc.time_zone is UTC, so a LocalDateTime
     * bound or written through JPA is shifted from the JVM's zone, and a row written here in raw SQL must be too.
     */
    private static String stored(String local) {
        java.time.format.DateTimeFormatter format = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        return java.time.LocalDateTime.parse(local, format).atZone(java.time.ZoneId.systemDefault())
            .withZoneSameInstant(java.time.ZoneOffset.UTC).format(format);
    }

    private void order(String status) {
        replica("INSERT INTO clinical.prescriptions (id, patient_id, staff_id, encounter_id, hospital_id, assignment_id, "
            + "medication_name, refills_used, controlled_substance, inpatient_order, take_home, allergies_reviewed, "
            + "interactions_reviewed, contraindications_reviewed, requires_cosign, pediatric_dose_review_required, "
            + "status, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, 'Paracetamol', 0, false, false, false, false, "
            + "false, false, false, false, ?, now(), now())",
            RX, PATIENT, STAFF, UUID.randomUUID(), HOSPITAL, ASSIGNMENT, status);
    }

    /** CR-03: every shape the one caller meets: the window across midnight, status, hospital and patient. */
    @Test
    void cr03_theDosesGivenSinceAMomentAcrossMidnight() {
        UUID justInside = dose("GIVEN", stored("2026-11-06 00:30:00"), stored("2026-11-06 00:30:00"), stored("2026-11-06 00:30:00"));
        UUID yesterdayEvening = dose("GIVEN", stored("2026-11-06 23:50:00"), stored("2026-11-06 23:50:00"), stored("2026-11-06 23:50:00"));
        UUID afterMidnight = dose("GIVEN", stored("2026-11-07 00:10:00"), stored("2026-11-07 00:10:00"), stored("2026-11-07 00:10:00"));
        dose("GIVEN", stored("2026-11-06 00:29:00"), stored("2026-11-06 00:29:59"), stored("2026-11-06 00:29:00"));
        dose("HELD", stored("2026-11-06 23:00:00"), stored("2026-11-06 23:00:00"), stored("2026-11-06 23:00:00"));
        dose("REFUSED", stored("2026-11-06 23:00:00"), stored("2026-11-06 23:00:00"), stored("2026-11-06 23:00:00"));
        dose("MISSED", stored("2026-11-06 23:00:00"), stored("2026-11-06 23:00:00"), stored("2026-11-06 23:00:00"));
        dose("PENDING", stored("2026-11-06 23:00:00"), null, stored("2026-11-06 23:00:00"));
        // The order behind the doses, discontinued: its doses still count.
        order("DISCONTINUED");
        replica("INSERT INTO clinical.medication_administration_records (id, prescription_id, patient_id, hospital_id, "
            + "medication_name, scheduled_time, administered_at, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'X', now(), '2026-11-06 23:00:00'::timestamp, 'GIVEN', now(), now())",
            UUID.randomUUID(), RX, OTHER_PATIENT, HOSPITAL);
        replica("INSERT INTO clinical.medication_administration_records (id, prescription_id, patient_id, hospital_id, "
            + "medication_name, scheduled_time, administered_at, status, created_at, updated_at) "
            + "VALUES (?, ?, ?, ?, 'X', now(), '2026-11-06 23:00:00'::timestamp, 'GIVEN', now(), now())",
            UUID.randomUUID(), RX, PATIENT, OTHER_HOSPITAL);

        List<MedicationAdministrationRecord> given = mar.findGivenSince(PATIENT, HOSPITAL,
            java.time.LocalDateTime.of(2026, 11, 6, 0, 30));

        assertThat(given).extracting(MedicationAdministrationRecord::getId)
            .containsExactly(afterMidnight, yesterdayEvening, justInside);
        assertThat(given.get(0).getPrescription().getStatus())
            .isEqualTo(com.example.hms.enums.PrescriptionStatus.DISCONTINUED);
    }
}
