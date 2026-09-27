package com.example.hms.hl7.mllp;

import com.example.hms.BaseIT;
import com.example.hms.enums.AuditEventType;
import com.example.hms.enums.OrganizationType;
import com.example.hms.enums.empi.EmpiAliasType;
import com.example.hms.enums.empi.EmpiIdentityStatus;
import com.example.hms.enums.empi.EmpiMergeType;
import com.example.hms.enums.integration.IntegrationMessageStatus;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.User;
import com.example.hms.model.empi.EmpiIdentityAlias;
import com.example.hms.model.empi.EmpiMasterIdentity;
import com.example.hms.model.empi.EmpiMergeEvent;
import com.example.hms.model.integration.IntegrationMessageEvent;
import com.example.hms.model.platform.MllpAllowedSender;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.empi.EmpiIdentityAliasRepository;
import com.example.hms.repository.empi.EmpiMasterIdentityRepository;
import com.example.hms.repository.empi.EmpiMergeEventRepository;
import com.example.hms.repository.integration.IntegrationMessageEventRepository;
import com.example.hms.repository.platform.MllpAllowedSenderRepository;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Inbound {@code ADT^A40} end to end: the real dispatcher, the real
 * {@code MllpInboundMergeServiceImpl}, the real {@code EmpiServiceImpl} and
 * the real repositories, on a thread that — like an MLLP worker — carries no
 * {@code HospitalContext} and no authentication.
 *
 * <p>Every other A40 test mocks {@code EmpiService}, and that is how every
 * inbound A40 came to be refused without a test noticing: EMPI's merge
 * resolved its scope from the request context, found none on the worker
 * thread and threw, the merge path caught that as a domain refusal, and
 * the throw had already marked the shared transaction rollback-only, so the
 * commit failed and the sender got the server-error {@code AE}. Only a test
 * that lets the real EMPI service run on a context-free thread can see it.
 *
 * <p>Extends {@link BaseIT} with {@code @AutoConfigureMockMvc(addFilters =
 * false)} and nothing else, so it reuses the Spring context that
 * {@code LabOrderCrossHospitalIT} and its siblings already build rather than
 * adding one to the capped CI heap. It cleans up only the rows it created.
 */
@AutoConfigureMockMvc(addFilters = false)
class AdtA40MergeEndToEndIT extends BaseIT {

    private static final String REMOTE = "127.0.0.1:0";
    private static final String NOT_FOUND_MSA_TEXT = "ADT^A40 referenced entity not found";
    private static final String INVALID_MSA_TEXT = "ADT^A40 invalid or missing required fields";
    private static final String NOT_OWNER_MSA_TEXT =
        "ADT^A40 not applied: a patient identity is owned by another hospital";
    private static final String FLUSH_FAIL_MARKER = "A40FLUSHFAIL";

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired private Hl7MessageDispatcher dispatcher;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private EmpiMasterIdentityRepository identityRepository;
    @Autowired private EmpiIdentityAliasRepository aliasRepository;
    @Autowired private EmpiMergeEventRepository mergeEventRepository;
    @Autowired private MllpAllowedSenderRepository allowedSenderRepository;
    @Autowired private IntegrationMessageEventRepository messageEventRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    /** Unique per test, so allowlist rows and dead letters never collide. */
    private String sendingApplication;
    private String sendingFacility;

    private Organization organization;
    private Hospital receiving;
    private Hospital elsewhere;

    private final List<UUID> userIds = new ArrayList<>();
    private final List<UUID> patientIds = new ArrayList<>();
    private final List<UUID> identityIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        // The worker thread's state, made explicit: nothing to read a scope from.
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();

        String run = nextId();
        sendingApplication = "A40IT-LIS-" + run;
        sendingFacility = "A40IT-FAC-" + run;

        organization = organizationRepository.save(Organization.builder()
            .name("A40 Network " + run)
            .code("A40ORG-" + run)
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        receiving = saveHospital("A40 Receiving " + run);
        elsewhere = saveHospital("A40 Elsewhere " + run);

        allowedSenderRepository.save(MllpAllowedSender.builder()
            .hospital(receiving)
            .sendingApplication(sendingApplication)
            .sendingFacility(sendingFacility)
            .description("ADT^A40 end-to-end test sender")
            .active(true)
            .build());
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        // Derived finders and batch deletes throughout: EmpiMergeEvent and
        // EmpiMasterIdentity are TenantScoped, and with no HospitalContext the
        // tenant-aware findAll/findById answer empty.
        tx.executeWithoutResult(status -> {
            for (UUID identityId : identityIds) {
                mergeEventRepository.findTopBySecondaryIdentity_IdOrderByMergedAtDesc(identityId)
                    .ifPresent(mergeEventRepository::delete);
                aliasRepository.deleteAll(aliasRepository.findByMasterIdentity_Id(identityId));
            }
        });
        auditEventLogRepository.deleteAll(mergeAudits());
        identityRepository.deleteAllByIdInBatch(identityIds);
        messageEventRepository.deleteAll(messageEventRepository.findAll().stream()
            .filter(row -> row.getIntegrationId() != null
                && row.getIntegrationId().contains(sendingApplication))
            .toList());
        allowedSenderRepository.findBySendingApplicationAndSendingFacility(sendingApplication, sendingFacility)
            .ifPresent(allowedSenderRepository::delete);
        for (UUID patientId : patientIds) {
            registrationRepository.deleteAll(registrationRepository.findByPatientId(patientId));
        }
        patientRepository.deleteAllByIdInBatch(patientIds);
        userRepository.deleteAllByIdInBatch(userIds);
        hospitalRepository.deleteAllByIdInBatch(List.of(receiving.getId(), elsewhere.getId()));
        organizationRepository.deleteAllByIdInBatch(List.of(organization.getId()));
        identityIds.clear();
        patientIds.clear();
        userIds.clear();
    }

    @Test
    @DisplayName("a valid A40 from an allowlisted sender merges two local patients and answers AA")
    void validMergeIsAppliedAndAccepted() {
        String run = nextId();
        String survivingMrn = "A40S-" + run;
        String priorMrn = "A40P-" + run;
        Patient survivor = patientAt(receiving, receiving);
        Patient retiree = patientAt(receiving, receiving);
        EmpiMasterIdentity survivorIdentity = identityFor(survivor, receiving, survivingMrn);
        EmpiMasterIdentity retireeIdentity = identityFor(retiree, receiving, priorMrn);

        String ack = dispatcher.dispatch(a40("MSG-OK-" + run, survivingMrn, priorMrn), REMOTE);

        assertThat(msa(ack)).isEqualTo("MSA|AA|MSG-OK-" + run);

        // The rows the merge is made of, read back from committed state.
        tx.executeWithoutResult(status -> {
            Optional<EmpiMergeEvent> merge = mergeEventRepository
                .findTopBySecondaryIdentity_IdOrderByMergedAtDesc(retireeIdentity.getId());
            assertThat(merge).as("an empi.merge_events row for the retired identity").isPresent();
            assertThat(merge.get().getPrimaryIdentity().getId()).isEqualTo(survivorIdentity.getId());
            assertThat(merge.get().getMergeType()).isEqualTo(EmpiMergeType.AUTOMATED);
            assertThat(merge.get().getHospitalId()).isEqualTo(receiving.getId());
            assertThat(merge.get().getNotes()).contains("HL7 ADT^A40").contains("MSG-OK-" + run);

            EmpiMasterIdentity retired = identityRepository.findByPatientId(retiree.getId()).orElseThrow();
            assertThat(retired.getStatus()).isEqualTo(EmpiIdentityStatus.MERGED);
            assertThat(retired.isActive()).isFalse();

            // The prior MRN now resolves to the survivor, which is what makes
            // a resend of this A40 an idempotent AA rather than a new merge.
            EmpiIdentityAlias moved = aliasRepository
                .findByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType.MRN, priorMrn).orElseThrow();
            assertThat(moved.getMasterIdentity().getId()).isEqualTo(survivorIdentity.getId());
        });
        assertThat(failedRows()).isEmpty();
        // The SUCCESS audit, written after commit, exactly once.
        assertThat(mergeAuditsFor(survivorIdentity)).hasSize(1);

        String resend = dispatcher.dispatch(a40("MSG-OK2-" + run, survivingMrn, priorMrn), REMOTE);
        assertThat(msa(resend)).isEqualTo("MSA|AA|MSG-OK2-" + run);
        assertThat(mergeAuditsFor(survivorIdentity)).hasSize(1);
    }

    @Test
    @DisplayName("an A40 naming a patient registered only at another hospital answers exactly like an unknown MRN")
    void crossTenantMergeIsRefusedLikeAnUnknownIdentifier() {
        String run = nextId();
        String localMrn = "A40L-" + run;
        String foreignMrn = "A40F-" + run;
        Patient local = patientAt(receiving, receiving);
        Patient foreign = patientAt(elsewhere, elsewhere);
        identityFor(local, receiving, localMrn);
        EmpiMasterIdentity foreignIdentity = identityFor(foreign, elsewhere, foreignMrn);

        String crossTenant = dispatcher.dispatch(a40("MSG-X-" + run, localMrn, foreignMrn), REMOTE);
        String unknown = dispatcher.dispatch(a40("MSG-X-" + run, localMrn, "A40NOBODY-" + run), REMOTE);

        assertThat(msa(crossTenant)).isEqualTo("MSA|AE|MSG-X-" + run + "|" + NOT_FOUND_MSA_TEXT);
        assertThat(msa(crossTenant)).isEqualTo(msa(unknown));
        tx.executeWithoutResult(status -> {
            assertThat(mergeEventRepository
                .findTopBySecondaryIdentity_IdOrderByMergedAtDesc(foreignIdentity.getId())).isEmpty();
            assertThat(identityRepository.findByPatientId(foreign.getId()).orElseThrow().getStatus())
                .isEqualTo(EmpiIdentityStatus.ACTIVE);
        });
        assertThat(failedRows()).extracting(IntegrationMessageEvent::getErrorMessage)
            .anySatisfy(reason -> assertThat(reason).contains("cross-tenant rejection"));
    }

    @Test
    @DisplayName("a merge EMPI refuses answers the REJECTED_INVALID AE and applies nothing, not a server error")
    void mergeRefusedByEmpiAnswersItsOwnAckAndRollsBack() {
        String run = nextId();
        String survivingMrn = "A40RS-" + run;
        String priorMrn = "A40RP-" + run;
        // Both registered at the receiving hospital, so the MLLP gate passes;
        // but the retiree's identity was already merged away, which EMPI
        // refuses as a domain rule after the gate.
        Patient survivor = patientAt(receiving, receiving);
        Patient retiree = patientAt(receiving, receiving);
        EmpiMasterIdentity survivorIdentity = identityFor(survivor, receiving, survivingMrn);
        EmpiMasterIdentity retireeIdentity = identityFor(retiree, receiving, priorMrn);
        tx.executeWithoutResult(status -> {
            EmpiMasterIdentity merged = identityRepository.findByPatientId(retiree.getId()).orElseThrow();
            merged.setStatus(EmpiIdentityStatus.MERGED);
            identityRepository.save(merged);
        });

        String ack = dispatcher.dispatch(a40("MSG-R-" + run, survivingMrn, priorMrn), REMOTE);

        assertThat(msa(ack)).isEqualTo("MSA|AE|MSG-R-" + run + "|" + INVALID_MSA_TEXT);
        tx.executeWithoutResult(status -> {
            assertThat(mergeEventRepository
                .findTopBySecondaryIdentity_IdOrderByMergedAtDesc(retireeIdentity.getId())).isEmpty();
            assertThat(aliasRepository.findByAliasTypeAndAliasValueIgnoreCase(EmpiAliasType.MRN, priorMrn)
                .orElseThrow().getMasterIdentity().getId()).isEqualTo(retireeIdentity.getId());
            assertThat(identityRepository.findByPatientId(survivor.getId()).orElseThrow().getId())
                .isEqualTo(survivorIdentity.getId());
        });
        assertDeadLetter("merge refused by EMPI", "MSG-R-" + run, survivingMrn, priorMrn);
        assertThat(mergeAuditsFor(survivorIdentity)).isEmpty();
    }

    @Test
    @DisplayName("a pair registered here but owned by another hospital gets a terminal AR naming the condition")
    void identityOwnedElsewhereIsATerminalRefusal() {
        String run = nextId();
        String survivingMrn = "A40HS-" + run;
        String priorMrn = "A40HP-" + run;
        // A referred patient: home hospital elsewhere, master identity stamped
        // there, but ALSO registered at the receiving hospital, so the
        // registration gate passes. EMPI would refuse it on every retry, so
        // the answer is AR, and says why without naming anyone.
        Patient survivor = patientAt(receiving, receiving);
        Patient retiree = patientAt(elsewhere, receiving);
        identityFor(survivor, receiving, survivingMrn);
        EmpiMasterIdentity retireeIdentity = identityFor(retiree, elsewhere, priorMrn);

        String ack = dispatcher.dispatch(a40("MSG-H-" + run, survivingMrn, priorMrn), REMOTE);

        assertThat(msa(ack)).isEqualTo("MSA|AR|MSG-H-" + run + "|" + NOT_OWNER_MSA_TEXT);
        tx.executeWithoutResult(status -> assertThat(mergeEventRepository
            .findTopBySecondaryIdentity_IdOrderByMergedAtDesc(retireeIdentity.getId())).isEmpty());
        assertDeadLetter("identity owned by another hospital", "MSG-H-" + run, survivingMrn, priorMrn);
    }

    @Test
    @DisplayName("two copies of one A40 racing on two connections apply ONE merge, one event, one audit row")
    void concurrentDuplicatesMergeOnce() throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            // Several pairs, so that at least some copies genuinely overlap.
            for (int attempt = 0; attempt < 5; attempt++) {
                String run = nextId();
                String survivingMrn = "A40CS-" + run;
                String priorMrn = "A40CP-" + run;
                Patient survivor = patientAt(receiving, receiving);
                Patient retiree = patientAt(receiving, receiving);
                EmpiMasterIdentity survivorIdentity = identityFor(survivor, receiving, survivingMrn);
                EmpiMasterIdentity retireeIdentity = identityFor(retiree, receiving, priorMrn);

                CountDownLatch go = new CountDownLatch(1);
                // Worker threads, like MLLP workers: no security context.
                Future<String> first = workers.submit(() -> {
                    go.await();
                    return dispatcher.dispatch(a40("MSG-C1-" + run, survivingMrn, priorMrn), REMOTE);
                });
                Future<String> second = workers.submit(() -> {
                    go.await();
                    return dispatcher.dispatch(a40("MSG-C2-" + run, survivingMrn, priorMrn), REMOTE);
                });
                go.countDown();
                List<String> answers = List.of(
                    msa(first.get(60, TimeUnit.SECONDS)), msa(second.get(60, TimeUnit.SECONDS)));

                Integer merges = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM empi.merge_events WHERE secondary_identity_id = ?",
                    Integer.class, retireeIdentity.getId());
                assertThat(merges).as("merge events for pair %s", run).isEqualTo(1);
                assertThat(mergeAuditsFor(survivorIdentity)).as("PATIENT_MERGE audits for pair %s", run)
                    .hasSize(1);
                // One copy applied it. The other either arrived after the
                // commit (both MRNs now resolve to one patient: AA) or lost
                // the claim mid-flight (the already-merged refusal: AE).
                assertThat(answers).anySatisfy(msa -> assertThat(msa).startsWith("MSA|AA|"));
                assertThat(answers).allSatisfy(msa -> assertThat(msa)
                    .matches("MSA\\|AA\\|MSG-C[12]-" + run
                        + "|MSA\\|AE\\|MSG-C[12]-" + run + "\\|" + java.util.regex.Pattern.quote(INVALID_MSA_TEXT)));
            }
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    @DisplayName("a merge that fails when flushed answers its refusal and leaves no SUCCESS audit")
    void flushFailureIsARefusalWithNoSuccessAudit() {
        String run = nextId();
        String survivingMrn = "A40FS-" + run;
        String priorMrn = "A40FP-" + run;
        Patient survivor = patientAt(receiving, receiving);
        Patient retiree = patientAt(receiving, receiving);
        EmpiMasterIdentity survivorIdentity = identityFor(survivor, receiving, survivingMrn);
        EmpiMasterIdentity retireeIdentity = identityFor(retiree, receiving, priorMrn);
        String controlId = FLUSH_FAIL_MARKER + "-" + run;
        // A constraint that only this message's merge row breaks: the notes
        // carry MSH-10. It fails on INSERT, which Hibernate defers to flush.
        jdbcTemplate.execute("ALTER TABLE empi.merge_events ADD CONSTRAINT a40it_flush_fail "
            + "CHECK (notes IS NULL OR notes NOT LIKE '%" + FLUSH_FAIL_MARKER + "%')");
        String ack;
        try {
            ack = dispatcher.dispatch(a40(controlId, survivingMrn, priorMrn), REMOTE);
        } finally {
            jdbcTemplate.execute("ALTER TABLE empi.merge_events DROP CONSTRAINT a40it_flush_fail");
        }

        assertThat(msa(ack)).isEqualTo("MSA|AE|" + controlId + "|" + INVALID_MSA_TEXT);
        tx.executeWithoutResult(status -> {
            assertThat(mergeEventRepository
                .findTopBySecondaryIdentity_IdOrderByMergedAtDesc(retireeIdentity.getId())).isEmpty();
            assertThat(identityRepository.findByPatientId(retiree.getId()).orElseThrow().getStatus())
                .isEqualTo(EmpiIdentityStatus.ACTIVE);
        });
        assertThat(mergeAuditsFor(survivorIdentity)).isEmpty();
        assertDeadLetter("merge refused by EMPI", controlId, survivingMrn, priorMrn);
    }

    /* ── fixtures ─────────────────────────────────────────────────────── */

    private String a40(String controlId, String survivingMrn, String priorMrn) {
        return "MSH|^~\\&|" + sendingApplication + "|" + sendingFacility + "|HMS|HMS|20260926120000||ADT^A40|"
            + controlId + "|P|2.5\r"
            + "EVN|A40|20260926120000\r"
            + "PID|1||" + survivingMrn + "^^^HOSP^MR||Traore^Awa||19900101|F\r"
            + "MRG|" + priorMrn + "^^^HOSP^MR\r";
    }

    /** The MSA segment: code, echoed MSH-10 and text — everything the sender reads. */
    private static String msa(String ack) {
        return ack.lines()
            .flatMap(line -> List.of(line.split("\r")).stream())
            .filter(segment -> segment.startsWith("MSA|"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no MSA segment in " + ack));
    }

    /** One FAILED row with this reason and MSH-10, and no MRN or payload in it. */
    private void assertDeadLetter(String reason, String controlId, String... mrns) {
        List<IntegrationMessageEvent> rows = failedRows().stream()
            .filter(row -> row.getErrorMessage() != null && row.getErrorMessage().startsWith(reason))
            .toList();
        assertThat(rows).hasSize(1);
        IntegrationMessageEvent row = rows.get(0);
        assertThat(row.getErrorMessage()).contains("\"" + controlId + "\"");
        assertThat(row.getPayload()).isNull();
        for (String mrn : mrns) {
            assertThat(row.getErrorMessage()).doesNotContain(mrn);
        }
    }

    /** Every PATIENT_MERGE audit row for merges into this test's identities. */
    private List<AuditEventLog> mergeAudits() {
        List<String> ids = identityIds.stream().map(UUID::toString).toList();
        return auditEventLogRepository.findAll().stream()
            .filter(row -> row.getEventType() == AuditEventType.PATIENT_MERGE)
            .filter(row -> ids.contains(row.getResourceId()))
            .toList();
    }

    private List<AuditEventLog> mergeAuditsFor(EmpiMasterIdentity survivor) {
        return mergeAudits().stream()
            .filter(row -> survivor.getId().toString().equals(row.getResourceId()))
            .toList();
    }

    private List<IntegrationMessageEvent> failedRows() {
        return messageEventRepository.findAll().stream()
            .filter(row -> row.getIntegrationId() != null && row.getIntegrationId().contains(sendingApplication))
            .filter(row -> row.getStatus() == IntegrationMessageStatus.FAILED)
            .toList();
    }

    private Hospital saveHospital(String name) {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name(name)
            .code("A40H" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226540" + id)
            .email("a40-" + id + "@hospital.test")
            .organization(organization)
            .build());
    }

    /** A patient whose home is {@code home}, registered at {@code registeredAt}. */
    private Patient patientAt(Hospital home, Hospital registeredAt) {
        String suffix = nextId();
        User user = userRepository.save(User.builder()
            .username("a40patient" + suffix)
            .passwordHash("hashed-password")
            .email("a40patient" + suffix + "@example.test")
            .firstName("A40")
            .lastName("Patient" + suffix)
            .phoneNumber("+22671" + suffix)
            .isActive(true)
            .build());
        userIds.add(user.getId());
        Patient patient = patientRepository.save(Patient.builder()
            .firstName("Awa")
            .lastName("Traore")
            .dateOfBirth(LocalDate.of(1990, 1, 1))
            .gender("F")
            .address("Patient address")
            .city("Ouagadougou")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22672" + suffix)
            .email("a40awa" + suffix + "@patient.test")
            .emergencyContactName("Issa Traore")
            .emergencyContactPhone("+22673" + suffix)
            .organizationId(organization.getId())
            .hospitalId(home.getId())
            .user(user)
            .build());
        patientIds.add(patient.getId());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(patient)
            .hospital(registeredAt)
            .mrn("REG-" + suffix)
            .registrationDate(LocalDate.now())
            .active(true)
            .build());
        return patient;
    }

    /** A master identity stamped with {@code stamp}, carrying one MRN alias. */
    private EmpiMasterIdentity identityFor(Patient patient, Hospital stamp, String mrn) {
        EmpiMasterIdentity identity = EmpiMasterIdentity.builder()
            .empiNumber("EMP-A40-" + nextId())
            .patientId(patient.getId())
            .organizationId(organization.getId())
            .hospitalId(stamp.getId())
            .sourceSystem("A40-IT")
            .build();
        identity.addAlias(EmpiIdentityAlias.builder()
            .aliasType(EmpiAliasType.MRN)
            .aliasValue(mrn)
            .sourceSystem("A40-IT")
            .build());
        EmpiMasterIdentity saved = identityRepository.save(identity);
        identityIds.add(saved.getId());
        return saved;
    }

    private static String nextId() {
        return String.format("%06d", SEQUENCE.incrementAndGet());
    }
}
