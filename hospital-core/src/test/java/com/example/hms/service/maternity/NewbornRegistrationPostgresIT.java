package com.example.hms.service.maternity;

import com.example.hms.HmsApplication;
import com.example.hms.config.TestPostgresConfig;
import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.InfantSex;
import com.example.hms.enums.LaborStatus;
import com.example.hms.enums.ProxyStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.Patient;
import com.example.hms.model.User;
import com.example.hms.model.labor.DeliveryNewborn;
import com.example.hms.model.labor.DeliveryRecord;
import com.example.hms.model.labor.LaborEpisode;
import com.example.hms.payload.dto.clinical.labor.DeliveryNewbornDTO;
import com.example.hms.repository.DeliveryNewbornRepository;
import com.example.hms.repository.DeliveryRecordRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LaborEpisodeRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientProxyRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScopeTestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1592 on a real PostgreSQL (Liquibase, V289): a recorded live birth
 * registers each baby through the real registration path - account, record,
 * hospital registration and MRN at the delivering hospital - linked to the
 * delivery and the mother, with the mother's portal proxy, and only once.
 */
@Testcontainers
@SpringBootTest(classes = HmsApplication.class)
@ActiveProfiles("test")
@Import(TestPostgresConfig.class)
class NewbornRegistrationPostgresIT {

    @Container
    @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_newborns")
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

    @Autowired private NewbornRegistrationService service;
    @Autowired private TransactionTemplate tx;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private LaborEpisodeRepository episodeRepository;
    @Autowired private DeliveryRecordRepository deliveryRecordRepository;
    @Autowired private DeliveryNewbornRepository deliveryNewbornRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private PatientProxyRepository proxyRepository;
    /** #1136 follow-up (QA r46): every birth texted the registrar and the hospital; nothing may go out now. */
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.hms.service.SmsService smsService;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    private com.example.hms.service.EmailService emailService;

    private Hospital hospital;
    private Patient mother;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.ROOT);
        hospital = hospitalRepository.saveAndFlush(Hospital.builder()
            .name("Maternité D1592 " + tag)
            .code("N" + tag)
            .city("Ouagadougou").state("Centre").country("Burkina Faso")
            .phoneNumber("+226-555-1592").email(tag.toLowerCase(Locale.ROOT) + "@hospital.test")
            .active(true)
            .build());
        User account = new User();
        account.setUsername("awa.traore." + tag.toLowerCase(Locale.ROOT));
        account.setPasswordHash("x");
        account.setFirstName("Awa");
        account.setLastName("Traore");
        account.setPhoneNumber("+2267" + (1_000_000 + Math.abs(tag.hashCode() % 8_999_999)));
        account = userRepository.saveAndFlush(account);
        Patient m = new Patient();
        m.setUser(account);
        m.setFirstName("Awa");
        m.setLastName("Traore");
        m.setDateOfBirth(LocalDate.of(1995, 5, 1));
        m.setGender("FEMALE");
        m.setPhoneNumberPrimary(account.getPhoneNumber());
        m.setCity("Ouagadougou");
        m.setHospitalId(hospital.getId());
        mother = patientRepository.saveAndFlush(m);
        ActingScopeTestSupport.actingAt(UUID.randomUUID(), hospital.getId());
    }

    @AfterEach
    void tearDown() {
        HospitalContextHolder.clear();
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    private DeliveryRecord delivery(boolean liveBirth, int infants) {
        LaborEpisode episode = episodeRepository.saveAndFlush(LaborEpisode.builder()
            .patient(mother)
            .hospital(hospital)
            .admittedAt(LocalDateTime.now().minusHours(8))
            .status(LaborStatus.DELIVERED)
            .build());
        return deliveryRecordRepository.saveAndFlush(DeliveryRecord.builder()
            .episode(episode)
            .patient(mother)
            .hospital(hospital)
            .birthDateTime(LocalDateTime.now().minusHours(1))
            .deliveryMode(DeliveryMode.SPONTANEOUS_VAGINAL)
            .liveBirth(liveBirth)
            .numberOfInfants(infants)
            .infantSex(InfantSex.FEMALE)
            .newbornRegistrationPending(liveBirth)
            .build());
    }

    @Test
    @DisplayName("twins become two patients at the delivering hospital, each with an MRN, once")
    void twinsAreRegisteredOnce() {
        DeliveryRecord delivery = delivery(true, 2);

        List<DeliveryNewbornDTO> babies = tx.execute(s -> service.registerLiveBorn(delivery, null));
        List<DeliveryNewbornDTO> again = tx.execute(s -> service.registerLiveBorn(delivery, null));

        assertThat(babies).hasSize(2);
        assertThat(again).extracting(DeliveryNewbornDTO::getPatientId)
            .containsExactlyElementsOf(babies.stream().map(DeliveryNewbornDTO::getPatientId).toList());
        assertThat(babies).allSatisfy(baby -> assertThat(baby.getMrn()).isNotBlank());
        assertThat(deliveryRecordRepository.findById(delivery.getId()).orElseThrow().isNewbornRegistrationPending())
            .as("the pending flag is cleared once the babies are registered").isFalse();
        tx.executeWithoutResult(s -> {
            List<DeliveryNewborn> links = deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId());
            assertThat(links).hasSize(2);
            for (DeliveryNewborn link : links) {
                Patient baby = link.getNewbornPatient();
                assertThat(baby.getLastName()).isEqualTo("Traore");
                assertThat(baby.getDateOfBirth()).isEqualTo(delivery.getBirthDateTime().toLocalDate());
                assertThat(baby.getHospitalId()).isEqualTo(hospital.getId());
                assertThat(link.getMotherPatient().getId()).isEqualTo(mother.getId());
                assertThat(registrationRepository.findByPatientIdAndHospitalIdAndActiveTrue(baby.getId(), hospital.getId()))
                    .isPresent();
                assertThat(registrationRepository.findByPatientId(baby.getId())).hasSize(1);
                // A dependant account: no login channel of its own.
                assertThat(baby.getUser().getEmail()).isNull();
                assertThat(baby.getUser().getPhoneNumber()).isNull();
                assertThat(proxyRepository.findByGrantorPatient_IdAndProxyUser_IdAndStatus(
                    baby.getId(), mother.getUser().getId(), ProxyStatus.ACTIVE)).isPresent();
            }
            assertThat(links).extracting(l -> l.getNewbornPatient().getFirstName()).containsExactly("Bébé 1", "Bébé 2");
            assertThat(links).extracting(l -> l.getNewbornPatient().getGender()).containsExactly("FEMALE", null);
        });
    }

    @Test
    @DisplayName("a stillbirth registers no patient")
    void stillbirthRegistersNoOne() {
        DeliveryRecord delivery = delivery(false, 1);

        List<DeliveryNewbornDTO> babies = tx.execute(s -> service.registerLiveBorn(delivery, null));
        assertThat(babies).isEmpty();
        assertThat(deliveryNewbornRepository.findByDeliveryRecord_IdOrderByBirthOrderAsc(delivery.getId())).isEmpty();
    }

    /**
     * #1136 follow-up (QA r46): each baby's PATIENT assignment sent the registrar's and the hospital's
     * "new registration" SMS, so every birth texted staff phones on prod. The baby has no phone and no e-mail:
     * no activation message and no FYI. Reception finds the baby in the patient list (an in-basket item is filed
     * only when a registration fails).
     */
    @Test
    @DisplayName("registering a newborn sends no SMS and no e-mail, to the family or to staff")
    void registeringANewbornSendsNothing() {
        String tag = UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        User midwife = new User();
        midwife.setUsername("sage.femme." + tag);
        midwife.setPasswordHash("x");
        midwife.setFirstName("Sali");
        midwife.setLastName("Ouedraogo");
        midwife.setPhoneNumber("+2266" + (1_000_000 + Math.abs(tag.hashCode() % 8_999_999)));
        User registrar = userRepository.saveAndFlush(midwife);
        // The recorder is the registrar the FYI used to text, with a phone of her own; the hospital has one too.
        ActingScopeTestSupport.actingAt(registrar.getId(), hospital.getId());
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
            new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                registrar, null, List.of()));
        DeliveryRecord delivery = delivery(true, 2);

        List<DeliveryNewbornDTO> babies = tx.execute(s -> service.registerLiveBorn(delivery, registrar.getId()));

        assertThat(babies).hasSize(2);
        org.mockito.Mockito.verify(smsService, org.mockito.Mockito.never())
            .send(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.verifyNoInteractions(emailService);
    }
}
