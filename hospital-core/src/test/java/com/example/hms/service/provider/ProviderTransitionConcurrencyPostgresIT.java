package com.example.hms.service.provider;

import com.example.hms.enums.FacilityType;
import com.example.hms.enums.HospitalLifecycleState;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.exception.ConflictException;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.model.Hospital;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.payload.dto.provider.ProviderAddressDTO;
import com.example.hms.payload.dto.provider.ProviderBusinessIdentityDTO;
import com.example.hms.payload.dto.provider.ProviderCreateRequestDTO;
import com.example.hms.payload.dto.provider.ProviderDecisionRequestDTO;
import com.example.hms.payload.dto.provider.ProviderProfessionalDTO;
import com.example.hms.payload.dto.provider.ProviderVerifyRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.HospitalLifecycleStatusService;
import com.example.hms.utility.MessageUtil;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.i18n.LocaleContextHolder;
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

import java.time.Clock;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Provider transitions on PostgreSQL (/code-review of #832, round 2): every
 * verify, reject, resubmit and revoke of one provider takes the facility row
 * lock before it reads the verification status, so a verify racing a reject
 * cannot leave the facility ACTIVE with a REJECTED verification. One wins;
 * the other waits on the lock, then reads the committed status and answers
 * 409.
 *
 * <p>Each race: the first transition runs in a transaction held open after
 * its work; the second starts on another thread, is shown to be waiting, and
 * is released by the first one's commit. H2 cannot show the contention.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({ProviderOnboardingServiceImpl.class, com.example.hms.service.impl.HospitalLifecycleServiceImpl.class,
        com.example.hms.service.HospitalServiceImpl.class, com.example.hms.mapper.HospitalMapper.class,
        ProviderTransitionConcurrencyPostgresIT.Config.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ProviderTransitionConcurrencyPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_provider_race")
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
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "8");
        // No caller identity on the racing threads: the archive step-up is
        // not what these races are about.
        registry.add("hms.hospital-lifecycle.require-mfa", () -> "false");
    }

    @TestConfiguration
    static class Config {
        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }
    }

    @MockitoBean private RoleValidator roleValidator;
    @MockitoBean private HospitalLifecycleStatusService lifecycleStatusService;
    @MockitoBean private AuditEventLogService auditEventLogService;
    @MockitoBean private com.example.hms.service.MfaService mfaService;

    @Autowired private ProviderOnboardingService onboardingService;
    @Autowired private com.example.hms.service.HospitalLifecycleService lifecycleService;
    @Autowired private com.example.hms.service.HospitalService hospitalService;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private ProviderVerificationRepository verificationRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private UUID providerId;

    @BeforeEach
    void setUp() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        LocaleContextHolder.setLocale(Locale.ENGLISH);
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(true);
        when(roleValidator.isSuperAdminFromAuth()).thenReturn(true);
        providerId = onboardingService.create(request()).getId();
    }

    /** The test thread's locale is shared with the next class in this fork: put it back. */
    @AfterEach
    void resetLocale() {
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("verify, then a reject that waits on it: the reject is a 409; VERIFIED and ACTIVE")
    void verifyThenReject() throws Exception {
        CompletableFuture<Object> second = race(this::verify, this::reject);

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class);
        assertThat(currentStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
        Hospital facility = hospitalRepository.findById(providerId).orElseThrow();
        assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.ACTIVE);
        assertThat(facility.isActive()).isTrue();
    }

    @Test
    @DisplayName("reject, then a verify that waits on it: the verify is a 409; REJECTED and still SUSPENDED")
    void rejectThenVerify() throws Exception {
        CompletableFuture<Object> second = race(this::reject, this::verify);

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class);
        assertThat(currentStatus()).isEqualTo(ProviderVerificationStatus.REJECTED);
        Hospital facility = hospitalRepository.findById(providerId).orElseThrow();
        assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.SUSPENDED);
        assertThat(facility.isActive()).isFalse();
    }

    @Test
    @DisplayName("verify, then an archive that waits on it: the archive keeps the verified identity")
    void verifyThenArchive() throws Exception {
        CompletableFuture<Object> second = race(this::verifyRenamed, this::archive);

        assertThat(second.get(30, TimeUnit.SECONDS)).isNotNull();
        Hospital facility = hospitalRepository.findById(providerId).orElseThrow();
        assertThat(facility.getLifecycleState()).isEqualTo(HospitalLifecycleState.ARCHIVED);
        assertThat(facility.getName()).isEqualTo("Verified Trade Name");
        assertThat(currentStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("archive, then a verify that waits on it: the verify is a 409; ARCHIVED, still SUBMITTED")
    void archiveThenVerify() throws Exception {
        CompletableFuture<Object> second = race(this::archive, this::verifyRenamed);

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class);
        assertThat(hospitalRepository.findById(providerId).orElseThrow().getLifecycleState())
            .isEqualTo(HospitalLifecycleState.ARCHIVED);
        assertThat(currentStatus()).isEqualTo(ProviderVerificationStatus.SUBMITTED);
    }

    @Test
    @DisplayName("verify, then a delete that waits on it: the delete is a 409; the VERIFIED provider stays")
    void verifyThenDelete() throws Exception {
        CompletableFuture<Object> second = race(this::verify, this::delete);

        assertThat(causeOf(second)).isInstanceOf(ConflictException.class);
        assertThat(hospitalRepository.findById(providerId)).isPresent();
        assertThat(currentStatus()).isEqualTo(ProviderVerificationStatus.VERIFIED);
    }

    @Test
    @DisplayName("delete, then a verify that waits on it: the verify finds nothing; no VERIFIED provider was deleted")
    void deleteThenVerify() throws Exception {
        CompletableFuture<Object> second = race(this::delete, this::verify);

        assertThat(causeOf(second)).isInstanceOf(com.example.hms.exception.ResourceNotFoundException.class);
        assertThat(hospitalRepository.findById(providerId)).isEmpty();
        assertThat(verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(providerId)).isEmpty();
    }

    @Test
    @DisplayName("two creates with one code: the second waits on the index, then 409; one provider has the code")
    void createVersusCreateWithOneCode() throws Exception {
        ProviderCreateRequestDTO first = request();
        ProviderCreateRequestDTO second = request();
        second.setCode(first.getCode());

        CompletableFuture<Object> loser = race(() -> onboardingService.create(first),
            () -> onboardingService.create(second));

        assertThat(causeOf(loser)).isInstanceOf(ConflictException.class);
        assertThat(hospitalRepository.findByCodeIgnoreCase(first.getCode())).isPresent();
    }

    // ── harness ──────────────────────────────────────────────────────────

    private Object verifyRenamed() {
        ProviderCreateRequestDTO evidence = request();
        evidence.getBusiness().setTradeName("Verified Trade Name");
        return onboardingService.verify(providerId, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true)
            .corrections(ProviderVerifyRequestDTO.Corrections.builder().business(evidence.getBusiness()).build())
            .build());
    }

    private Object archive() {
        return lifecycleService.archive(providerId,
            com.example.hms.payload.dto.superadmin.TenantLifecycleActionRequestDTO.builder().reason("Closed").build(),
            null);
    }

    private Object delete() {
        hospitalService.deleteHospital(providerId, Locale.ENGLISH);
        return Boolean.TRUE;
    }

    private Object verify() {
        return onboardingService.verify(providerId, ProviderVerifyRequestDTO.builder()
            .ifuMatchesRccm(true).cnssMatchesRccm(true).build());
    }

    private Object reject() {
        return onboardingService.reject(providerId, new ProviderDecisionRequestDTO("Illegible extract"));
    }

    private ProviderVerificationStatus currentStatus() {
        return verificationRepository.findFirstByHospital_IdOrderByCreatedAtDesc(providerId)
            .map(ProviderVerification::getStatus)
            .orElseThrow();
    }

    /**
     * Runs {@code first} in a transaction held open after it, starts
     * {@code second} on another thread, checks that it waits, then commits
     * the first. Returns the second's outcome.
     */
    private CompletableFuture<Object> race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CompletableFuture<Object> holder = CompletableFuture.supplyAsync(() ->
            new TransactionTemplate(transactionManager).execute(status -> {
                Object result = first.get();
                firstDone.countDown();
                await(releaseFirst);
                return result;
            }));
        assertThat(firstDone.await(30, TimeUnit.SECONDS)).as("the first transition finished its work").isTrue();

        CompletableFuture<Object> waiter = CompletableFuture.supplyAsync(second);
        assertThatThrownBy(() -> waiter.get(700, TimeUnit.MILLISECONDS))
            .as("the second transition waits on the facility row lock")
            .isInstanceOf(TimeoutException.class);
        releaseFirst.countDown();
        holder.get(30, TimeUnit.SECONDS);
        try {
            waiter.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
            // inspected by the caller through causeOf
        }
        return waiter;
    }

    private static Throwable causeOf(CompletableFuture<Object> future) {
        assertThat(future).isCompletedExceptionally();
        try {
            future.get();
            throw new AssertionError("expected a failure");
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ProviderCreateRequestDTO request() {
        String n = String.format("%05d", SEQUENCE.incrementAndGet()) + UUID.randomUUID().toString().substring(0, 4);
        return ProviderCreateRequestDTO.builder()
            .facilityType(FacilityType.PHARMACY)
            .code("RACE-" + n)
            .business(ProviderBusinessIdentityDTO.builder()
                .legalName("Race " + n + " SARL")
                .legalStructure("SARL")
                .rccmNumber("RCCM-" + n)
                .ifuNumber("IFU-" + n)
                .cnssNumber("CNSS-" + n)
                .address(ProviderAddressDTO.builder().city("Ouagadougou").region("Centre").build())
                .companyPhone("+22670000000")
                .managerName("Manager")
                .managerTitle("Gerant")
                .startedOn(LocalDate.of(2020, 1, 1))
                .build())
            .professional(ProviderProfessionalDTO.builder()
                .licenceNumber("LIC-" + n)
                .licenceAuthority("Authority")
                .responsibleName("Responsible")
                .responsibleOrdreNumber("ORD-" + n)
                .build())
            .build();
    }
}
