package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1212 (rounds 23 and 24) class guard: a catch that swallows the exception
 * of a call which JOINS the caller's transaction.
 *
 * <p>The proxy health summary wrapped nested {@code @Transactional} reads in
 * try/catch "for partial availability". The nested call's transaction
 * interceptor marks the SHARED transaction rollback-only on the way out, so
 * the swallowed failure came back at the outer commit as
 * {@code UnexpectedRollbackException} and the whole page answered 500 — for
 * four rounds, because mocked unit tests have no transaction. A best-effort
 * read goes through {@code service.support.IsolatedReads} (its own
 * transaction); a best-effort write through a {@code REQUIRES_NEW} service or
 * an after-commit hook.
 *
 * <p>The scan: in every main source file that declares {@code @Transactional},
 * each {@code try} block whose first {@code catch} does not rethrow, and each
 * call in it on an injected field whose target joins the caller's
 * transaction — a Spring Data repository (its methods are transactional), or
 * a bean whose implementing method is {@code @Transactional} without
 * {@code REQUIRES_NEW}/{@code NOT_SUPPORTED}/{@code NEVER}, or sits in a class
 * annotated {@code @Transactional}. A call to a non-transactional bean or to a
 * {@code REQUIRES_NEW} audit service is not an instance.
 *
 * <p>The sites below predate the guard. Each was read (D1212 hunt, round 24);
 * most are repository reads on a page that would rather degrade than fail, or
 * writes whose failure is already fatal to the transaction. They are standing
 * debt (tasklist.md): the count may only shrink, and a new site fails.
 */
@DisplayName("No catch swallows a failure that joined the caller's transaction")
class SwallowedNestedTransactionGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String DEBT = "QA D1212 hunt (round 24): standing debt, ";
    private static final String REPO_READ = DEBT + "best-effort repository read inside the caller's transaction";
    private static final String CONSENT = DEBT + "'best effort' consent capture at (pre-)check-in: a failing record()"
        + " still rolls the check-in back; it writes rows of the same transaction (the encounter), so it needs a"
        + " savepoint or an after-commit capture, not REQUIRES_NEW";
    private static final String REPO_WRITE = DEBT + "repository write whose failure already dooms the transaction";

    /** {@code @Transactional}, not {@code @TransactionalEventListener} (which runs after the commit). */
    private static final Pattern DECLARES_TRANSACTIONAL = Pattern.compile("@Transactional(?!\\w)");
    private static final Pattern CLASS_DECL = Pattern.compile("\\b(?:class|interface)\\s+(\\w+)([^{;]*)\\{");
    private static final Pattern FIELD = Pattern.compile("private\\s+(?:final\\s+)?([\\w.]+)\\s+(\\w+)\\s*;");
    private static final Pattern TRY = Pattern.compile("\\btry\\s*\\{");
    private static final Pattern CATCH = Pattern.compile("\\A\\s*catch\\s*\\(([^)]*)\\)\\s*\\{");
    private static final Pattern THROW = Pattern.compile("\\bthrow\\b");
    private static final Pattern CALL = Pattern.compile("\\b(\\w+)\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern BEAN_TYPE = Pattern.compile(
        "(Service|Repository|Recorder|Policy|Access|Client|Gateway|Store|Manager)$");
    private static final Pattern OWN_TRANSACTION = Pattern.compile("REQUIRES_NEW|NOT_SUPPORTED|NEVER");
    private static final Pattern CLASS_LEVEL_TX = Pattern.compile(
        "@Transactional(\\([^)]*\\))?\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s*)*public\\s+(?:final\\s+|abstract\\s+)?class\\b");

    /** Site = file + called type.method, with how many swallowing try blocks call it today. */
    static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "security/DowntimeStateService.java: PlatformDowntimeStateRepository.findById", 1, REPO_READ,
        "security/GlobalSessionRevocationService.java: SecurityRevocationRepository.findById", 2, REPO_READ,
        "service/ClinicalDashboardServiceImpl.java: EncounterRepository.findByStaff_Id", 1, REPO_READ,
        "service/ClinicalDashboardServiceImpl.java: OnCallScheduleRepository.findActiveByStaffIdAt", 1, REPO_READ,
        "service/ClinicalDashboardServiceImpl.java: StaffRepository.findFirstByUserIdOrderByCreatedAtAsc", 1, REPO_READ,
        "service/DischargeSummaryServiceImpl.java: DischargeSummaryRepository.save", 1, REPO_WRITE,
        "service/DischargeSummaryServiceImpl.java: PrescriptionRepository.findByEncounter_Id", 1, REPO_READ,
        "service/DoctorWorklistServiceImpl.java: AdmissionRepository.findByAdmittingProviderIdOrderByAdmissionDateTimeDesc", 1, REPO_READ,
        "service/DoctorWorklistServiceImpl.java: ConsultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc", 2, REPO_READ,
        "service/DoctorWorklistServiceImpl.java: PatientVitalSignRepository.findFirstByPatient_IdOrderByRecordedAtDesc", 1, REPO_READ,
        "service/EncounterServiceImpl.java: PrescriptionRepository.findByEncounter_Id", 1, REPO_READ,
        "service/EncounterServiceImpl.java: PatientRecallRepository.save", 1, REPO_WRITE,
        "service/impl/GeneralReferralServiceImpl.java: AppointmentRepository.save", 1, REPO_WRITE,
        "service/PatientSnapshotServiceImpl.java: AdmissionRepository.findByPatientIdOrderByAdmissionDateTimeDesc", 1, REPO_READ,
        "service/ResultReviewServiceImpl.java: EncounterNoteRepository.findByHospital_IdAndRequiresCosignTrueAndCosignedAtIsNullAndSignedAtIsNotNullOrderBySignedAtAsc", 1, REPO_READ,
        "service/ResultReviewServiceImpl.java: NotificationRepository.findByRecipientUsernameAndTypeAndReadFalseOrderByCreatedAtDesc", 1, REPO_READ,
        "service/ReceptionServiceImpl.java: UserRepository.findByUsername", 1, REPO_READ,
        "service/ReceptionServiceImpl.java: TreatmentConsentService.record", 1, CONSENT,
        "service/impl/PatientPortalServiceImpl.java: PatientTreatmentConsentRepository.existsByAppointment_IdAndStatus", 1, CONSENT,
        "service/impl/PatientPortalServiceImpl.java: TreatmentConsentService.record", 1, CONSENT,
        "service/ImagingCriticalNotificationService.java: ImagingReportRepository.save", 1, REPO_WRITE,
        "service/InstrumentOutboxServiceImpl.java: InstrumentOutboxRepository.save", 1, REPO_WRITE,
        "service/LabResultServiceImpl.java: UserRepository.findById", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: EncounterRepository.findByPatient_IdAndHospital_IdInOrderByEncounterDateDesc", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: LabOrderRepository.findByPatient_IdAndHospital_IdIn", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: LabResultRepository.findPatientResultsReadableAt", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: PatientAllergyRepository.findByPatient_Id", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: PatientDiagnosisRepository.findByPatient_IdAndStatusAndHospitalIsNullOrderByDiagnosedAtDesc", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: PatientDiagnosisRepository.findByPatient_IdAndStatusAndHospital_IdInOrderByDiagnosedAtDesc", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: PatientProblemRepository.findByPatient_IdAndHospital_IdIn", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: PatientVitalSignRepository.findByPatient_IdAndHospital_IdInOrderByRecordedAtDesc", 1, REPO_READ,
        "service/PatientSnapshotServiceImpl.java: PrescriptionRepository.findByPatient_IdAndHospital_IdIn", 1, REPO_READ,
        "service/PrescriptionServiceImpl.java: PatientRepository.findByIdUnscoped", 1, REPO_READ,
        "service/ResultReviewServiceImpl.java: ChatMessageRepository.countByRecipient_IdAndReadFalse", 1, REPO_READ,
        "service/ResultReviewServiceImpl.java: ConsultationRepository.findByConsultant_IdAndStatusOrderByRequestedAtDesc", 1, REPO_READ,
        "service/ResultReviewServiceImpl.java: DigitalSignatureRepository.findBySignedBy_IdAndStatusOrderBySignatureDateTimeDesc", 1, REPO_READ,
        "service/ResultReviewServiceImpl.java: PrescriptionRepository.countByStaff_IdAndStatus", 1, REPO_READ,
        "service/ResultReviewServiceImpl.java: RefillRequestRepository.findByPrescription_Staff_IdAndStatusOrderByCreatedAtDesc", 1, REPO_READ,
        "service/UserGovernanceService.java: UserService.createUserWithRolesAndHospital", 1,
            DEBT + "bulk import: one failing row's nested create poisons the whole import (needs per-row transactions)",
        "service/UserGovernanceService.java: PasswordResetService.invalidateAllForUser", 1,
            DEBT + "force reset: a failing reset poisons the batch (needs per-user transactions)",
        "service/UserGovernanceService.java: PasswordResetService.requestReset", 2,
            DEBT + "invitation/reset mail inside the caller's transaction (needs an after-commit send)",
        "service/UserGovernanceService.java: UserRepository.save", 1, REPO_WRITE,
        "service/UserRoleHospitalAssignmentServiceImpl.java: UserRoleRepository.existsByUserIdAndRoleId", 1, REPO_READ,
        "service/UserRoleHospitalAssignmentServiceImpl.java: UserRoleRepository.save", 1, REPO_WRITE,
        "service/UserServiceImpl.java: UserRoleHospitalAssignmentRepository.findFirstByUserIdAndActiveTrue", 1, REPO_READ,
        "service/apikey/ApiKeyService.java: ApiKeyRepository.stampLastUsed", 1, REPO_WRITE,
        "service/impl/HospitalLifecycleStatusServiceImpl.java: HospitalRepository.findIdsByLifecycleStateIn", 1, REPO_READ,
        "service/impl/MicroCultureServiceImpl.java: MicroIsolateRepository.findByCultureResult_IdOrderByIsolateNumberAscCreatedAtAsc", 1, REPO_READ,
        "service/impl/OrganizationLifecycleStatusServiceImpl.java: OrganizationRepository.findIdsByLifecycleStateIn", 1, REPO_READ,
        "service/impl/PatientDocumentServiceImpl.java: UserRoleHospitalAssignmentRepository.findFirstByUser_IdAndHospital_IdAndActiveTrue", 1, REPO_READ,
        "service/impl/ReferralExpiryPersistence.java: GeneralReferralRepository.saveAndFlush", 1, REPO_WRITE,
        "service/impl/SuperAdminLabOrderServiceImpl.java: LabTestDefinitionRepository.findById", 1, REPO_READ,
        "service/integration/InstrumentOutboxDispatchService.java: InstrumentOutboxRepository.save", 1, REPO_WRITE,
        "service/integration/health/IntegrationHealthRecorder.java: IntegrationHealthEventRepository.save", 1, REPO_WRITE,
        "service/integration/health/IntegrationHealthRecorder.java: IntegrationHealthSnapshotRepository.save", 2, REPO_WRITE,
        "service/integration/message/IntegrationMessageRecorder.java: IntegrationMessageEventRepository.findByIdForUpdate", 1, REPO_READ,
        "service/integration/message/IntegrationMessageRecorder.java: IntegrationMessageEventRepository.findFirstByCorrelationIdAndStatusAndReceivedAtAfterOrderByReceivedAtDesc", 1, REPO_READ,
        "service/integration/message/IntegrationMessageRecorder.java: IntegrationMessageEventRepository.save", 2, REPO_WRITE,
        "service/pharmacy/DispenseServiceImpl.java: PrescriptionRoutingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc", 1, REPO_READ,
        "service/pharmacy/DispenseServiceImpl.java: RefillRequestRepository.findFirstByPrescription_IdAndStatusOrderByUpdatedAtDesc", 1, REPO_READ,
        "service/pharmacy/DispenseServiceImpl.java: RefillRequestRepository.save", 1, REPO_WRITE,
        "service/pharmacy/InventoryServiceImpl.java: UserRepository.findById", 1, REPO_READ,
        "service/pharmacy/StockOutRoutingServiceImpl.java: PrescriptionRoutingDecisionRepository.findByPrescriptionIdOrderByDecidedAtDesc", 1, REPO_READ,
        "service/pharmacy/StockOutRoutingServiceImpl.java: PrescriptionRoutingDecisionRepository.save", 1, REPO_WRITE,
        "service/pro/ProScreeningEscalationService.java: ProResponseRepository.save", 1, REPO_WRITE,
        "service/scheduling/RecallReminderService.java: PatientRecallRepository.save", 1, REPO_WRITE);

    private static final Map<String, String> SOURCES = new TreeMap<>();
    private static final Map<String, List<String>> IMPLEMENTORS = new HashMap<>();

    @BeforeAll
    static void readTheTree() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                SOURCES.put(rel, source);
                Matcher decl = CLASS_DECL.matcher(source);
                while (decl.find()) {
                    IMPLEMENTORS.computeIfAbsent(decl.group(1), k -> new ArrayList<>()).add(rel);
                    for (String iface : implementedTypes(decl.group(2))) {
                        IMPLEMENTORS.computeIfAbsent(iface, k -> new ArrayList<>()).add(rel);
                    }
                }
            }
        }
    }

    private static List<String> implementedTypes(String declTail) {
        int at = declTail.indexOf("implements");
        if (at < 0) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : declTail.substring(at + "implements".length()).split(",")) {
            String name = part.replaceAll("<.*", "").trim();
            int dot = name.lastIndexOf('.');
            out.add(dot >= 0 ? name.substring(dot + 1) : name);
        }
        return out;
    }

    @Test
    @DisplayName("QA D1212: a swallowed catch never wraps a call that joins the caller's transaction")
    void noSwallowedJoinedCall() {
        Map<String, Integer> actual = GuardAllowlist.counts(sites());
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a try/catch that does not rethrow wraps a call joining the caller's transaction: the failure"
                + " marks the shared transaction rollback-only and the commit answers 500"
                + " (UnexpectedRollbackException). Read best-effort through IsolatedReads.attempt(...)"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("fixed sites: lower the allowance").isEmpty();
    }

    @Test
    @DisplayName("the scan sees the shape it guards (the health summary's reads, routed through IsolatedReads)")
    void theScanSeesTheShape() {
        String probe = "@Transactional class P { private final PatientLabResultService labResultService;"
            + " void m() { try { labResultService.getLabResultsForPatientPortal(a, b, 5); }"
            + " catch (Exception e) { log.warn(\"x\"); } } }";
        assertThat(sitesIn("probe/P.java", probe))
            .containsExactly("probe/P.java: PatientLabResultService.getLabResultsForPatientPortal");
        String rethrows = probe.replace("log.warn(\"x\");", "throw e;");
        assertThat(sitesIn("probe/P.java", rethrows)).isEmpty();
        String audit = "@Transactional class P { private final AuditEventLogService auditEventLogService;"
            + " void m() { try { auditEventLogService.logEvent(d); } catch (RuntimeException e) { } } }";
        assertThat(sitesIn("probe/P.java", audit)).as("REQUIRES_NEW audit is its own transaction").isEmpty();
    }

    private static List<String> sites() {
        List<String> out = new ArrayList<>();
        SOURCES.forEach((rel, source) -> out.addAll(sitesIn(rel, source)));
        return out;
    }

    private static List<String> sitesIn(String rel, String source) {
        List<String> out = new ArrayList<>();
        if (!DECLARES_TRANSACTIONAL.matcher(source).find()) {
            return out;
        }
        Map<String, String> fields = new HashMap<>();
        Matcher field = FIELD.matcher(source);
        while (field.find()) {
            String type = field.group(1);
            fields.put(field.group(2), type.substring(type.lastIndexOf('.') + 1));
        }
        Matcher tryBlock = TRY.matcher(source);
        while (tryBlock.find()) {
            int open = tryBlock.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            if (close + 1 >= source.length()) {
                continue;
            }
            Matcher catchClause = CATCH.matcher(source.substring(close + 1, Math.min(source.length(), close + 400)));
            if (!catchClause.find()) {
                continue;
            }
            int catchOpen = close + 1 + catchClause.end() - 1;
            String catchBody = source.substring(catchOpen, SourceScan.closingBrace(source, catchOpen));
            if (THROW.matcher(catchBody).find()) {
                continue;
            }
            Matcher call = CALL.matcher(source.substring(open, close));
            List<String> seen = new ArrayList<>();
            while (call.find()) {
                String type = fields.get(call.group(1));
                String site = rel + ": " + type + "." + call.group(2);
                if (type != null && BEAN_TYPE.matcher(type).find() && !seen.contains(site)
                    && joinsCallersTransaction(type, call.group(2))) {
                    seen.add(site);
                }
            }
            out.addAll(seen);
        }
        return out;
    }

    /** Whether {@code type.method(...)} runs in the caller's transaction (see the class Javadoc). */
    static boolean joinsCallersTransaction(String type, String method) {
        if (type.endsWith("Repository")) {
            return true;
        }
        Pattern declaration = Pattern.compile(
            "((?:\\s*@[\\w.]+(?:\\([^)]*\\))?)*)\\s*(?:public|protected)\\s+[\\w<>,.?\\[\\] ]+\\s+"
                + Pattern.quote(method) + "\\s*\\(");
        for (String rel : IMPLEMENTORS.getOrDefault(type, List.of())) {
            String source = SOURCES.get(rel);
            Matcher classTx = CLASS_LEVEL_TX.matcher(source);
            boolean classJoins = classTx.find() && !OWN_TRANSACTION.matcher(classTx.group()).find();
            Matcher decl = declaration.matcher(source);
            while (decl.find()) {
                String annotations = decl.group(1);
                boolean joins = annotations.contains("@Transactional")
                    ? !OWN_TRANSACTION.matcher(annotations).find()
                    : classJoins;
                if (joins) {
                    return true;
                }
            }
        }
        return false;
    }
}
