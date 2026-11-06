package com.example.hms.service.impl;

import com.example.hms.enums.MicroCultureStatus;
import com.example.hms.enums.MicroGrowthResult;
import com.example.hms.exception.BusinessException;
import com.example.hms.payload.dto.MicroCultureResponseDTO;
import com.example.hms.payload.dto.MicroCultureUpdateDTO;
import com.example.hms.payload.dto.MicroIsolateRequestDTO;
import com.example.hms.service.MicroCultureService;
import com.example.hms.service.NotificationService;
import com.example.hms.service.support.PatientChartAccess;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.MessageSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QA CX-009: a validated culture report kept saying growth with no organism. Each
 * call below runs in its own transaction on a PostgreSQL built by Liquibase: the
 * row lock, the history table and the concurrent removal only mean something there.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({MicroCultureServiceImpl.class, MicroCultureOutcomePostgresIT.Config.class})
@DisplayName("CX-009: a validated culture report says what its organisms say, on PostgreSQL")
class MicroCultureOutcomePostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_micro_outcome")
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

    @TestConfiguration
    static class Config {
        @Bean
        MessageSource microOutcomeMessages() {
            return com.example.hms.i18n.TestMessageSources.bundles();
        }

        @Bean
        java.time.Clock microOutcomeClock() {
            return java.time.Clock.systemDefaultZone();
        }
    }

    @Autowired private MicroCultureService service;
    @Autowired private PlatformTransactionManager transactionManager;
    @PersistenceContext private EntityManager entityManager;

    @MockitoBean private NotificationService notificationService;
    @MockitoBean private PatientChartAccess patientChartAccess;

    private final UUID hospital = UUID.randomUUID();
    private final UUID patient = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID culture = UUID.randomUUID();

    @BeforeEach
    void seed() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            entityManager.createNativeQuery("SET LOCAL session_replication_role = replica").executeUpdate();
            String tag = UUID.randomUUID().toString().substring(0, 8);
            entityManager.createNativeQuery("""
                INSERT INTO hospital.hospitals (id, code, name, active, created_at, updated_at)
                VALUES (:id, :code, :name, true, now(), now())
                """).setParameter("id", hospital).setParameter("code", "CX9-" + tag)
                .setParameter("name", "Hopital CX9 " + tag).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO clinical.patients (id, user_id, first_name, last_name, date_of_birth, phone_number_primary,
                    is_active, created_at, updated_at)
                VALUES (:id, :u, 'Awa', 'Kabore', DATE '1990-06-15', :phone, true, now(), now())
                """).setParameter("id", patient).setParameter("u", UUID.randomUUID())
                .setParameter("phone", "+226 71 " + tag).executeUpdate();
            UUID definition = UUID.randomUUID();
            entityManager.createNativeQuery("""
                INSERT INTO lab.lab_test_definitions (id, test_code, name, is_active, created_at, updated_at)
                VALUES (:id, :code, 'Blood culture', true, now(), now())
                """).setParameter("id", definition).setParameter("code", "CX9" + tag).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO lab.lab_orders (id, hospital_id, patient_id, lab_test_definition_id, ordering_staff_id,
                    assignment_id, status, order_datetime, priority, clinical_indication, medical_necessity_note,
                    primary_diagnosis_code, order_channel, documentation_shared_with_lab, standing_order,
                    created_at, updated_at)
                VALUES (:id, :h, :p, :t, :s, :a, 'IN_PROGRESS', now(), 'ROUTINE', 'CX-009', 'CX-009', 'A41.9',
                    'PORTAL', false, false, now(), now())
                """).setParameter("id", order).setParameter("h", hospital).setParameter("p", patient)
                .setParameter("t", definition).setParameter("s", UUID.randomUUID())
                .setParameter("a", UUID.randomUUID()).executeUpdate();
            entityManager.createNativeQuery("""
                INSERT INTO lab.micro_culture_results (id, lab_order_id, patient_id, hospital_id, status, growth_result,
                    finalized_at, created_at, updated_at)
                VALUES (:id, :o, :p, :h, 'FINAL', 'GROWTH', now(), now(), now())
                """).setParameter("id", culture).setParameter("o", order).setParameter("p", patient)
                .setParameter("h", hospital).executeUpdate();
        });
    }

    private UUID isolate(int number, String organism) {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("""
                INSERT INTO lab.micro_isolates (id, culture_result_id, isolate_number, organism_name, created_at, updated_at)
                VALUES (:id, :c, :n, :o, now(), now())
                """).setParameter("id", id).setParameter("c", culture).setParameter("n", number)
                .setParameter("o", organism).executeUpdate());
        return id;
    }

    private void leaveInTheBrokenState() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("""
                UPDATE lab.micro_culture_results SET status = 'CORRECTED', correction_reason = 'old', corrected_at = now()
                WHERE id = :c
                """).setParameter("c", culture).executeUpdate());
    }

    private MicroCultureResponseDTO read() {
        return service.getCulture(culture, hospital);
    }

    @Test
    @DisplayName("removing the last organism is refused on the outcome, and nothing changes")
    void lastIsolateIsRefused() {
        UUID only = isolate(1, "Escherichia coli");

        assertThatThrownBy(() -> service.deleteIsolate(culture, only, hospital, "CX-009 reason"))
            .isInstanceOf(BusinessException.class);

        MicroCultureResponseDTO after = read();
        assertThat(after.getStatus()).isEqualTo(MicroCultureStatus.FINAL);
        assertThat(after.getGrowthResult()).isEqualTo(MicroGrowthResult.GROWTH);
        assertThat(after.getIsolates()).hasSize(1);
        assertThat(after.getCorrections()).isEmpty();
    }

    @Test
    @DisplayName("removing one of two is a correction, and the history still names it")
    void oneOfTwoIsRemovedAndRemembered() {
        UUID wrong = isolate(1, "Escherichia coli");
        isolate(2, "Klebsiella pneumoniae");

        MicroCultureResponseDTO after = service.deleteIsolate(culture, wrong, hospital, "CX-009 misidentified");

        assertThat(after.getStatus()).isEqualTo(MicroCultureStatus.CORRECTED);
        assertThat(after.getIsolates()).extracting(MicroCultureResponseDTO.Isolate::getOrganismName)
            .containsExactly("Klebsiella pneumoniae");
        assertThat(after.getCorrections()).singleElement().satisfies(correction -> {
            assertThat(correction.getReason()).isEqualTo("CX-009 misidentified");
            assertThat(correction.getStatusBefore()).isEqualTo(MicroCultureStatus.FINAL);
            assertThat(correction.getIsolatesBefore()).contains("Escherichia coli", "Klebsiella pneumoniae");
        });
    }

    @Test
    @DisplayName("two people removing the two organisms at once cannot empty the report")
    void concurrentRemovalsLeaveOne() throws Exception {
        UUID first = isolate(1, "Escherichia coli");
        UUID second = isolate(2, "Klebsiella pneumoniae");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Future<MicroCultureResponseDTO>> other = new ArrayList<>();

        // The second removal starts while the first has removed but not committed. It
        // must end refused. On its own this test does not prove the row lock: the first
        // correction's UPDATE of the report also makes the second wait, through a flush.
        // The next test is the one that fails without the lock.
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.deleteIsolate(culture, first, hospital, "CX-009 race");
            other.add(pool.submit(() -> service.deleteIsolate(culture, second, hospital, "CX-009 race")));
            try {
                other.get(0).get(3, TimeUnit.SECONDS);
            } catch (TimeoutException waiting) {
                // Waiting on the row, as it should: the first commits now.
                assertThat(other.get(0)).isNotDone();
            } catch (ExecutionException | InterruptedException unexpected) {
                throw new IllegalStateException(unexpected);
            }
        });

        assertThatThrownBy(() -> other.get(0).get(60, TimeUnit.SECONDS))
            .isInstanceOf(ExecutionException.class)
            .cause().isInstanceOf(BusinessException.class);
        pool.shutdown();
        assertThat(read().getIsolates()).extracting(MicroCultureResponseDTO.Isolate::getOrganismName)
            .containsExactly("Klebsiella pneumoniae");
    }

    @Test
    @DisplayName("two corrections at once both stay: the second reads the report after the first")
    void concurrentCorrectionsDoNotOverwriteEachOther() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        List<Future<MicroCultureResponseDTO>> other = new ArrayList<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            service.updateCulture(culture, hospital, MicroCultureUpdateDTO.builder()
                .gramStain("CX-009 stain").correctionReason("CX-009 stain").build());
            other.add(pool.submit(() -> service.updateCulture(culture, hospital, MicroCultureUpdateDTO.builder()
                .notes("CX-009 note").correctionReason("CX-009 note").build())));
            try {
                other.get(0).get(3, TimeUnit.SECONDS);
            } catch (TimeoutException waiting) {
                assertThat(other.get(0)).isNotDone();
            } catch (ExecutionException | InterruptedException unexpected) {
                throw new IllegalStateException(unexpected);
            }
        });
        other.get(0).get(60, TimeUnit.SECONDS);
        pool.shutdown();

        MicroCultureResponseDTO after = read();
        assertThat(after.getGramStain()).isEqualTo("CX-009 stain");
        assertThat(after.getNotes()).isEqualTo("CX-009 note");
        assertThat(after.getCorrections()).hasSize(2);
    }

    @Test
    @DisplayName("correcting to no growth takes the organisms off in one step, when asked, and remembers them")
    void noGrowthWithRemoval() {
        isolate(1, "Escherichia coli");

        MicroCultureUpdateDTO withoutAsking = MicroCultureUpdateDTO.builder()
            .growthResult(MicroGrowthResult.NO_GROWTH).correctionReason("CX-009 contaminated plate").build();
        assertThatThrownBy(() -> service.updateCulture(culture, hospital, withoutAsking))
            .isInstanceOf(BusinessException.class);
        assertThat(read().getIsolates()).hasSize(1);

        MicroCultureResponseDTO after = service.updateCulture(culture, hospital, MicroCultureUpdateDTO.builder()
            .growthResult(MicroGrowthResult.NO_GROWTH).correctionReason("CX-009 contaminated plate")
            .removeIsolates(Boolean.TRUE).build());

        assertThat(after.getGrowthResult()).isEqualTo(MicroGrowthResult.NO_GROWTH);
        assertThat(after.getStatus()).isEqualTo(MicroCultureStatus.CORRECTED);
        assertThat(after.getIsolates()).isEmpty();
        assertThat(after.getCorrections()).singleElement().satisfies(correction -> {
            assertThat(correction.getGrowthBefore()).isEqualTo(MicroGrowthResult.GROWTH);
            assertThat(correction.getIsolatesBefore()).contains("Escherichia coli");
        });
    }

    @Test
    @DisplayName("a later growth on a validated no-growth report: the outcome alone is refused, the organism carries it")
    void noGrowthToGrowthGoesThroughTheOrganism() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
            entityManager.createNativeQuery("UPDATE lab.micro_culture_results SET growth_result = 'NO_GROWTH' WHERE id = :c")
                .setParameter("c", culture).executeUpdate());

        MicroCultureUpdateDTO outcomeAlone = MicroCultureUpdateDTO.builder()
            .growthResult(MicroGrowthResult.GROWTH).correctionReason("CX-009 late growth").build();
        assertThatThrownBy(() -> service.updateCulture(culture, hospital, outcomeAlone))
            .isInstanceOf(BusinessException.class);

        MicroCultureResponseDTO after = service.addIsolate(culture, hospital, MicroIsolateRequestDTO.builder()
            .organismName("Escherichia coli").correctionReason("CX-009 late growth").build());

        assertThat(after.getGrowthResult()).isEqualTo(MicroGrowthResult.GROWTH);
        assertThat(after.getStatus()).isEqualTo(MicroCultureStatus.CORRECTED);
        assertThat(after.getIsolates()).hasSize(1);
    }

    @Test
    @DisplayName("a report already left at growth with no organism reads, and corrects in each direction")
    void existingBrokenRowsStayCorrectable() {
        leaveInTheBrokenState();
        assertThat(read().getIsolates()).isEmpty();

        // Its notes, with the outcome unchanged as the portal sends it.
        MicroCultureResponseDTO noted = service.updateCulture(culture, hospital, MicroCultureUpdateDTO.builder()
            .growthResult(MicroGrowthResult.GROWTH).notes("CX-009 note").correctionReason("CX-009 note").build());
        assertThat(noted.getNotes()).isEqualTo("CX-009 note");

        // An organism.
        MicroCultureResponseDTO added = service.addIsolate(culture, hospital, MicroIsolateRequestDTO.builder()
            .organismName("Escherichia coli").correctionReason("CX-009 organism").build());
        assertThat(added.getIsolates()).hasSize(1);
        assertThat(added.getGrowthResult()).isEqualTo(MicroGrowthResult.GROWTH);
    }

    @Test
    @DisplayName("a report already left at growth with no organism can be corrected to no growth")
    void existingBrokenRowCorrectsToNoGrowth() {
        leaveInTheBrokenState();
        MicroCultureResponseDTO after = service.updateCulture(culture, hospital, MicroCultureUpdateDTO.builder()
            .growthResult(MicroGrowthResult.NO_GROWTH).correctionReason("CX-009 outcome").build());
        assertThat(after.getGrowthResult()).isEqualTo(MicroGrowthResult.NO_GROWTH);
    }
}
