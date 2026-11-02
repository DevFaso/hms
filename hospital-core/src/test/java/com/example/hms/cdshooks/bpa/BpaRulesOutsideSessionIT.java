package com.example.hms.cdshooks.bpa;

import com.example.hms.BaseIT;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsCard;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.rules.CdsRule;
import com.example.hms.cdshooks.rules.CdsRuleContext;
import com.example.hms.cdshooks.service.BpaProtocolsCdsService;
import com.example.hms.enums.ConsciousnessLevel;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.PrescriptionStatus;
import com.example.hms.enums.ProblemStatus;
import com.example.hms.model.BpaProtocol;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientProblem;
import com.example.hms.model.PatientVitalSign;
import com.example.hms.model.Prescription;
import com.example.hms.model.User;
import com.example.hms.repository.BpaProtocolRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientProblemRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PatientVitalSignRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.utility.HospitalTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1121 (round 20, a regression) and D1114: every BPA rule, and every CDS
 * rule, runs on real entities with NO persistence context open — the way the
 * CDS hooks call them (open-in-view is off).
 *
 * <p>The D1114 time-zone fix loaded the hospital and walked to its LAZY
 * organisation inside the rule, NEWS2 read the vital's LAZY patient, and the
 * engine swallowed the {@code LazyInitializationException} with a WARN: every
 * time-stamped card vanished and the mock-based tests stayed green. This test
 * drives the real Spring beans and the real repositories:
 * <ul>
 *   <li>each registered {@link BpaRule} is evaluated on a context the real
 *       engine built (so its readings carry real Hibernate proxies) with no
 *       transaction open, and EVERY rule must render its card in one of the
 *       two seeded scenarios — a new rule that is not covered here fails;</li>
 *   <li>the {@code patient-view} hook returns the time-stamped cards with the
 *       reading time on the hospital's wall clock (D1114);</li>
 *   <li>each registered {@link CdsRule} is evaluated on an order whose
 *       hospital and patient are uninitialised proxies.</li>
 * </ul>
 * The test profile sets {@code hms.cds.rules.fail-fast=true}, so an exception
 * inside any rule fails here instead of being logged and dropped.
 */
class BpaRulesOutsideSessionIT extends BaseIT {

    /** A zone no test host runs in, so the wall-clock conversion always shows. */
    private static final ZoneId HOSPITAL_ZONE = ZoneId.of("Pacific/Kiritimati");
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final String TEST_DATA_PREFIX = "BPA-IT-";

    @Autowired private List<BpaRule> bpaRules;
    @Autowired private List<CdsRule> cdsRules;
    @Autowired private List<com.example.hms.cdshooks.service.CdsHookService> hookServices;
    @Autowired private BpaRuleEngine engine;
    @Autowired private BpaProtocolsCdsService bpaService;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientVitalSignRepository vitalRepository;
    @Autowired private PatientProblemRepository problemRepository;
    @Autowired private MaternalHistoryRepository maternalHistoryRepository;
    @Autowired private BpaProtocolRepository protocolRepository;

    @Value("${hms.cds.rules.fail-fast:false}")
    private boolean failFast;

    private final AtomicInteger sequence = new AtomicInteger();
    private final List<Runnable> cleanup = new ArrayList<>();

    private Hospital hospital;
    /** Adult man: fever, qSOFA 2 and a NEWS2 parameter scoring 3. */
    private Patient febrile;
    /** Pregnant woman at about 23 weeks: severe-range blood pressure, tachycardia, a postpartum-coded problem. */
    private Patient pregnant;
    private LocalDateTime febrileReadingAt;
    private LocalDateTime pregnantReadingAt;

    @BeforeEach
    void seed() {
        Organization organization = organizationRepository.save(Organization.builder()
            .name(TEST_DATA_PREFIX + "Network")
            .code("ORG-BPA-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .defaultTimezone(HOSPITAL_ZONE.getId())
            .active(true)
            .build());
        cleanup.add(() -> organizationRepository.deleteById(organization.getId()));
        hospital = hospitalRepository.save(Hospital.builder()
            .name(TEST_DATA_PREFIX + "Hospital")
            .code("HBPA" + nextId())
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+22656" + nextId())
            .email("bpa" + nextId() + "@hospital.test")
            .organization(organization)
            .build());
        cleanup.add(0, () -> hospitalRepository.deleteById(hospital.getId()));

        for (String code : List.of(MalariaFeverProtocolRule.PROTOCOL_CODE, SepsisQsofaProtocolRule.PROTOCOL_CODE,
                NewsScoreProtocolRule.PROTOCOL_CODE, ObHemorrhageProtocolRule.PROTOCOL_CODE,
                PregnancyHypertensionProtocolRule.PROTOCOL_CODE)) {
            if (protocolRepository.findByProtocolCode(code).isEmpty()) {
                BpaProtocol saved = protocolRepository.save(BpaProtocol.builder()
                    .protocolCode(code).name(code).summary(code).active(true).build());
                cleanup.add(0, () -> protocolRepository.deleteById(saved.getId()));
            }
        }

        LocalDateTime now = LocalDateTime.now().withSecond(0).withNano(0);
        febrile = patient("MALE", LocalDate.of(1980, 3, 4));
        febrileReadingAt = now.minusHours(1);
        vital(febrile, PatientVitalSign.builder()
            .recordedAt(febrileReadingAt)
            .temperatureCelsius(39.4)
            .respiratoryRateBpm(25)
            .systolicBpMmHg(95)
            .diastolicBpMmHg(60)
            .heartRateBpm(118)
            .spo2Percent(90)
            .onOxygen(false)
            .consciousnessLevel(ConsciousnessLevel.ALERT));

        pregnant = patient("FEMALE", LocalDate.of(1995, 7, 1));
        pregnantReadingAt = now.minusMinutes(30);
        vital(pregnant, PatientVitalSign.builder()
            .recordedAt(pregnantReadingAt)
            .systolicBpMmHg(164)
            .diastolicBpMmHg(112)
            .heartRateBpm(120));
        MaternalHistory history = maternalHistoryRepository.save(MaternalHistory.builder()
            .patient(pregnant)
            .hospital(hospital)
            .recordedDate(now.minusDays(1))
            .estimatedDueDate(LocalDate.now().plusWeeks(17))
            .build());
        cleanup.add(0, () -> maternalHistoryRepository.deleteById(history.getId()));
        PatientProblem problem = problemRepository.save(PatientProblem.builder()
            .patient(pregnant)
            .hospital(hospital)
            .problemCode("Z39")
            .problemDisplay("Post-partum care")
            .status(ProblemStatus.ACTIVE)
            .build());
        cleanup.add(0, () -> problemRepository.deleteById(problem.getId()));
    }

    @AfterEach
    void tearDown() {
        LocaleContextHolder.resetLocaleContext();
        cleanup.forEach(Runnable::run);
        cleanup.clear();
    }

    @Test
    void theTestProfileMakesARuleExceptionFailTheTest() {
        assertThat(failFast)
            .as("hms.cds.rules.fail-fast must be on in the test profile, or a throwing rule is only a log line")
            .isTrue();
    }

    @Test
    void everyRegisteredBpaRuleRendersItsCardWithNoSessionOpen() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        BpaRuleContext febrileContext = engine.buildContext(reload(febrile), hospital.getId());
        BpaRuleContext pregnantContext = engine.buildContext(reload(pregnant), hospital.getId());
        assertThat(febrileContext.recentVitals()).isNotEmpty();
        assertThat(pregnantContext.recentVitals()).isNotEmpty();

        List<String> silent = new ArrayList<>();
        for (BpaRule rule : bpaRules) {
            // No try/catch: a LazyInitializationException (or any other) fails the test.
            List<CdsCard> cards = new ArrayList<>(rule.evaluate(febrileContext));
            cards.addAll(rule.evaluate(pregnantContext));
            if (cards.isEmpty()) {
                silent.add(rule.id());
            }
        }
        assertThat(bpaRules).hasSizeGreaterThanOrEqualTo(5);
        assertThat(silent)
            .as("every registered BPA rule must render a card in one of the seeded scenarios; "
                + "extend the seed when a rule is added")
            .isEmpty();
    }

    @Test
    void thePatientViewHookReturnsTheTimeStampedCardsOnTheHospitalsClock() {
        LocaleContextHolder.setLocale(Locale.FRENCH);

        List<CdsCard> febrileCards = bpaService.evaluate(hook(febrile.getId())).cards();
        List<CdsCard> pregnantCards = bpaService.evaluate(hook(pregnant.getId())).cards();

        String febrileTime = HospitalTime.at(febrileReadingAt, HOSPITAL_ZONE).format(WHEN);
        String pregnantTime = HospitalTime.at(pregnantReadingAt, HOSPITAL_ZONE).format(WHEN);
        // Malaria, sepsis (qSOFA, untimed) and NEWS2 for the febrile man, in French.
        assertThat(febrileCards).hasSize(3);
        assertThat(febrileCards).extracting(CdsCard::summary)
            .anyMatch(s -> s.startsWith("Paludisme"))
            .anyMatch(s -> s.startsWith("NEWS2"));
        assertThat(febrileCards).filteredOn(c -> !c.summary().startsWith("Sepsis"))
            .hasSize(2)
            .allSatisfy(card -> assertThat(card.detail())
                .as("D1114: the reading time is the hospital's wall clock")
                .contains(febrileTime));
        assertThat(pregnantCards).hasSizeGreaterThanOrEqualTo(2)
            .allSatisfy(card -> assertThat(card.detail()).contains(pregnantTime))
            .anySatisfy(card ->
            assertThat(card.indicator()).isEqualTo(CdsCard.Indicator.CRITICAL));
    }

    @Test
    void everyRegisteredCdsRuleRunsOnAnOrderWhoseAssociationsAreUninitialisedProxies() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        // getReferenceById outside a transaction hands back a proxy whose session has closed:
        // reading anything but the id throws, exactly as a detached order's hospital does.
        Hospital hospitalProxy = hospitalRepository.getReferenceById(hospital.getId());
        Prescription earlier = new Prescription();
        earlier.setId(UUID.randomUUID());
        earlier.setHospital(hospitalProxy);
        earlier.setPatient(reload(febrile));
        earlier.setMedicationName("Paracétamol");
        earlier.setStatus(PrescriptionStatus.SIGNED);
        earlier.setCreatedAt(LocalDateTime.now().minusHours(3));
        CdsRuleContext context = new CdsRuleContext(reload(febrile), hospital.getId(), "Paracétamol", null, null,
            500.0, null, 70.0, List.of(earlier), null);

        List<CdsCard> cards = new ArrayList<>();
        for (CdsRule rule : cdsRules) {
            cards.addAll(rule.evaluate(context));
        }
        assertThat(cdsRules).isNotEmpty();
        assertThat(cards).extracting(CdsCard::summary)
            .as("the duplicate-order card still renders, dated on the hospital's day")
            .anyMatch(s -> s.contains("Paracétamol"));
    }

    /**
     * Every registered CDS Hooks service, called the way the controller calls
     * it (no session open, the bean's own transaction rules only), answers for
     * a patient with readings, problems and a draft order: none of them reads a
     * LAZY association it has no session for.
     */
    @Test
    void everyCdsHookServiceAnswersWithNoSessionOpen() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
        Map<String, Object> draft = new HashMap<>();
        draft.put("resourceType", "MedicationRequest");
        draft.put("id", "draft-1");
        draft.put("medicationCodeableConcept", Map.of("text", "Paracétamol 500 mg"));
        draft.put("dosageInstruction", List.of(Map.of("text", "500 mg q6h")));
        Map<String, Object> bundle = Map.of("entry", List.of(Map.of("resource", draft)));
        List<String> answered = new ArrayList<>();
        for (Patient patient : List.of(febrile, pregnant)) {
            for (com.example.hms.cdshooks.service.CdsHookService service : hookServices) {
                Map<String, Object> context = new HashMap<>();
                context.put("patientId", patient.getId().toString());
                context.put("draftOrders", bundle);
                context.put("medications", bundle);
                context.put("selections", List.of());
                CdsHookResponse response = service.evaluate(new CdsHookRequest(service.descriptor().hook(), "i",
                    null, null, "Practitioner/x", context, new LinkedHashMap<>()));
                assertThat(response).as(service.descriptor().id()).isNotNull();
                answered.add(service.descriptor().id());
            }
        }
        assertThat(answered).contains("hms-bpa-protocols", "hms-patient-view");
    }

    private Patient reload(Patient patient) {
        return patientRepository.findByIdUnscoped(patient.getId()).orElseThrow();
    }

    private Patient patient(String gender, LocalDate dateOfBirth) {
        int n = nextId();
        User account = userRepository.save(User.builder()
            .username("bpa-it-user-" + n)
            .passwordHash("noop")
            .email("bpa-it-user-" + n + "@hospital.test")
            .firstName("Test")
            .lastName(TEST_DATA_PREFIX + n)
            .phoneNumber("+22671" + n + "100")
            .build());
        cleanup.add(0, () -> userRepository.deleteById(account.getId()));
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Test")
            .lastName(TEST_DATA_PREFIX + n)
            .dateOfBirth(dateOfBirth)
            .gender(gender)
            .phoneNumberPrimary("+22671" + n + "200")
            .email("bpa-it-" + n + "@patient.test")
            .hospitalId(hospital.getId())
            .user(account)
            .build());
        cleanup.add(0, () -> patientRepository.deleteById(saved.getId()));
        return saved;
    }

    private void vital(Patient patient, PatientVitalSign.PatientVitalSignBuilder builder) {
        PatientVitalSign saved = vitalRepository.save(builder.patient(patient).hospital(hospital).build());
        cleanup.add(0, () -> vitalRepository.deleteById(saved.getId()));
    }

    private static CdsHookRequest hook(UUID patientId) {
        Map<String, Object> context = new HashMap<>();
        context.put("patientId", patientId.toString());
        return new CdsHookRequest("patient-view", "i", null, null, "Practitioner/x", context, new LinkedHashMap<>());
    }

    private int nextId() {
        return sequence.incrementAndGet();
    }
}
