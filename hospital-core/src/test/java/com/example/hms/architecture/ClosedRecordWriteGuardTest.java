package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1428 guard (round 34): an edit of a record that has already reached its
 * terminal state. {@code updateAdmission} rewrote a cancelled stay's notes,
 * acuity and room; the same hole was open on a closed visit's PUT, a signed
 * result, a revoked directive and a recorded dose.
 *
 * <ul>
 *   <li>{@link #everyEditMethodRefusesAClosedRecordOrSaysWhyNot()} reads every
 *       public {@code update*}, {@code edit*} and {@code amend*} of every
 *       service: it refuses a closed record in its own body, or it is listed
 *       here with why not. A new one is classified on purpose.</li>
 *   <li>{@link #theNamedWritesKeepTheirCheck()} pins the writes not named as
 *       edits, each to the check that closes it.</li>
 * </ul>
 */
@DisplayName("A closed record is not edited")
class ClosedRecordWriteGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");
    private static final Path FHIR_WRITES = Path.of("src/main/java/com/example/hms/fhir/write");

    /** Every method declaration at class level; the name is the word before the parenthesis. */
    private static final Pattern METHOD = Pattern.compile(
        "\\n    (?:public|private|protected|static|void)\\b[^;{=(]*+\\(");
    private static final Pattern EDIT_NAME = Pattern.compile("(?:update|edit|amend)(?:[A-Z]\\w*+)?+");

    /** A refusal keyed on the record's state, or a helper named for one. */
    private static final Pattern STATE_CHECK = Pattern.compile(
        "requireOpen\\(|requireWritableBy\\(|requirePlanOpen\\(|requireNotCancelled\\(|requireNotReleased\\(|requireNotWithdrawn\\("
            + "|requireNotVoided\\(|requireDispensedOrderKept\\(|isClosedStatus\\(|isClosed\\(|isTerminal\\(|isSigned\\("
            + "|getIsFinalized\\(|isWithdrawn\\(|validateStatusTransition\\(|CLOSED_TO_EDIT|RESULTED_STATES"
            + "|getStatus\\(\\)\\s*+==\\s*+\\w++\\.(?:COMPLETED|CANCELLED|REVOKED|ENTERED_IN_ERROR)"
            + "|[lL]ocked\"|NoEdit\"|readOnly\"|applyCorrectionGuard\\(|requireLegalUpdate\\(");

    private static final String NO_TERMINAL_STATE =
        "this record has no terminal state (master data, an account, or a living history)";

    /** Edit methods with no state check, each with why. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "AdmissionOrderSetServiceImpl#update", 1, NO_TERMINAL_STATE,
        "AnnouncementServiceImpl#updateAnnouncement", 1, NO_TERMINAL_STATE,
        "AuditSavedSearchServiceImpl#update", 1, NO_TERMINAL_STATE,
        "BedManagementServiceImpl#updateWard", 1, NO_TERMINAL_STATE,
        "DepartmentServiceImpl#updateDepartment", 1, NO_TERMINAL_STATE,
        "DepartmentServiceImpl#updateDepartmentHead", 1, NO_TERMINAL_STATE,
        "Dhis2ConfigServiceImpl#updateMapping", 1, NO_TERMINAL_STATE,
        "DrugInteractionAdminServiceImpl#update", 1, NO_TERMINAL_STATE,
        "HospitalServiceImpl#updateHospital", 1, NO_TERMINAL_STATE,
        "InventoryServiceImpl#updateInventoryItem", 1, NO_TERMINAL_STATE,
        "LabInstrumentServiceImpl#update", 1, NO_TERMINAL_STATE,
        "LabInventoryServiceImpl#update", 1, NO_TERMINAL_STATE,
        "LabReflexRuleServiceImpl#updateRule", 1, NO_TERMINAL_STATE,
        "LabTestDefinitionServiceImpl#update", 1, NO_TERMINAL_STATE,
        "LabTestDefinitionServiceImpl#updateReferenceRanges", 1, NO_TERMINAL_STATE,
        "LabTestValidationStudyServiceImpl#update", 1, NO_TERMINAL_STATE,
        "MedicationCatalogItemServiceImpl#update", 1, NO_TERMINAL_STATE,
        "MedicationCatalogItemServiceImpl#updatePediatricDosing", 1, NO_TERMINAL_STATE,
        "MllpAllowedSenderServiceImpl#update", 1, NO_TERMINAL_STATE,
        "NotificationServiceImpl#updatePreferences", 1, NO_TERMINAL_STATE,
        "OnCallScheduleServiceImpl#update", 1, NO_TERMINAL_STATE,
        "OrganizationRegionServiceImpl#updateOrganizationRegion", 1, NO_TERMINAL_STATE,
        "OrganizationSecurityPolicyServiceImpl#updatePolicy", 1, NO_TERMINAL_STATE,
        "OrganizationSecurityPolicyServiceImpl#updatePolicyFromDto", 1, NO_TERMINAL_STATE,
        "OrganizationSecurityRuleServiceImpl#updateRule", 1, NO_TERMINAL_STATE,
        "OrganizationSecurityRuleServiceImpl#updateRuleFromDto", 1, NO_TERMINAL_STATE,
        "OrganizationServiceImpl#updateOrganization", 1, NO_TERMINAL_STATE,
        "PatientEducationServiceImpl#updateQuestion", 1, NO_TERMINAL_STATE,
        "PatientEducationServiceImpl#updateResource", 1, NO_TERMINAL_STATE,
        "PatientGuarantorServiceImpl#update", 1, NO_TERMINAL_STATE,
        "PatientHospitalRegistrationServiceImpl#updateRegistration", 1, NO_TERMINAL_STATE,
        "PatientInsuranceServiceImpl#updatePatientInsurance", 1, NO_TERMINAL_STATE,
        "PatientPortalServiceImpl#updateMyNotificationPreferences", 1, NO_TERMINAL_STATE,
        "PatientPortalServiceImpl#updateMyProfile", 1, NO_TERMINAL_STATE,
        "PatientPrimaryCareServiceImpl#updatePrimaryCare", 1, NO_TERMINAL_STATE,
        "PatientServiceImpl#updatePatient", 1, NO_TERMINAL_STATE,
        "PermissionServiceImpl#updatePermission", 1, NO_TERMINAL_STATE,
        "PharmacyServiceImpl#update", 1, NO_TERMINAL_STATE,
        "PlatformRegistryServiceImpl#updateHospitalServiceLink", 1, NO_TERMINAL_STATE,
        "PlatformRegistryServiceImpl#updateOrganizationService", 1, NO_TERMINAL_STATE,
        "ProviderAdminServiceImpl#updateProfile", 1, NO_TERMINAL_STATE,
        "RegionPolicyServiceImpl#update", 1, NO_TERMINAL_STATE,
        "RoleServiceImpl#update", 1, NO_TERMINAL_STATE,
        "ServiceTranslationServiceImpl#updateTranslation", 1, NO_TERMINAL_STATE,
        "SessionTemplateServiceImpl#update", 1, NO_TERMINAL_STATE,
        "SmartPhraseServiceImpl#update", 1, NO_TERMINAL_STATE,
        "StaffServiceImpl#updateStaff", 1, NO_TERMINAL_STATE,
        "StaffServiceImpl#updateStaffDepartment", 1, NO_TERMINAL_STATE,
        "StaffServiceImpl#updateStaffLabRole", 1, NO_TERMINAL_STATE,
        "TreatmentServiceImpl#updateTreatment", 1, NO_TERMINAL_STATE,
        "UserServiceImpl#updateProfileImage", 1, NO_TERMINAL_STATE,
        "UserServiceImpl#updateUser", 1, NO_TERMINAL_STATE,
        "VisitTypeServiceImpl#update", 1, NO_TERMINAL_STATE,
        "FamilyHistoryServiceImpl#updateFamilyHistory", 1, NO_TERMINAL_STATE,
        "SocialHistoryServiceImpl#updateSocialHistory", 1, NO_TERMINAL_STATE,
        "MedicationHistoryServiceImpl#updatePharmacyFill", 1, NO_TERMINAL_STATE,
        "PatientEducationServiceImpl#updateProgress", 1, NO_TERMINAL_STATE,
        "PatientEducationServiceImpl#updateVisitDocumentation", 1, NO_TERMINAL_STATE,
        "PatientPortalServiceImpl#updateMyEducationProgress", 1, NO_TERMINAL_STATE,
        "BedManagementServiceImpl#updateBed", 1, NO_TERMINAL_STATE,
        "BedManagementServiceImpl#updateBedStatus", 1, NO_TERMINAL_STATE,
        "InventoryServiceImpl#updateStockLot", 1, NO_TERMINAL_STATE,
        "SubscriptionServiceImpl#updatePlan", 1, NO_TERMINAL_STATE,
        "UserRoleHospitalAssignmentServiceImpl#updateAssignment", 1, NO_TERMINAL_STATE,
        "WebhookEndpointService#update", 1, NO_TERMINAL_STATE,
        "PatientFhirWriteService#update", 1, NO_TERMINAL_STATE,
        "EncounterFhirWriteService#update", 2, "both delegate to doUpdate, pinned in NAMED_WRITES",
        "ObservationFhirWriteService#updateLabResult", 1, "QA D1428 open, tasklist: appends notes to a released result; FHIR writes are off by default",
        "StaffSchedulingServiceImpl#updateShift", 1, "a roster shift, not a clinical record",
        "StaffSchedulingServiceImpl#updateShiftStatus", 1, "a roster shift, not a clinical record",
        "AppointmentServiceImpl#updateAppointment", 1, "scheduling, not a clinical record: its status rule is requireValidStatusChange, read by the edit further down",
        "HighRiskPregnancyCarePlanServiceImpl#updatePlan", 1, "QA D1428 open, tasklist: the active flag on the same body is how a plan is closed and reopened, so an inactive plan's edit needs a product rule first",
        "ImmunizationServiceImpl#updateImmunization", 1, "QA D1428 open, tasklist: fix/r34-maternity-records owns this file in round 34; an entered-in-error dose can be edited back",
        "InvoiceItemServiceImpl#updateInvoiceItem", 1, "billing, not a clinical record; the invoice's own lock is outside this class",
        "MaternalHistoryServiceImpl#updateMaternalHistory", 1, "a versioned record: an edit writes a new version",
        "MortalityServiceImpl#amendDeathRecord", 1, "this IS the amend path of a certified record: a reason is required and it is audited",
        "PatientServiceImpl#updatePatientDiagnosis", 1, "a status change reopens a removed diagnosis on purpose, with its history (QA D1240)",
        "PatientServiceImpl#updatePatientAllergy", 1, "reactivating a deactivated allergy is deliberate (QA D1242): a safety record reopens, audited",
        "ProgramEnrollmentService#updateStatus", 1, "reopening a wrongly closed enrolment is the designed path of this endpoint",
        "ReceptionServiceImpl#updateEncounterStatus", 1, "the desk's status write: EncounterClosureRules.requireDeskTransition refuses a closed visit");

    @Test
    @DisplayName("every edit method refuses a closed record, or says why it has none")
    void everyEditMethodRefusesAClosedRecordOrSaysWhyNot() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        int edits = 0;
        try (Stream<Path> files = Stream.concat(Files.walk(SERVICES), Files.walk(FHIR_WRITES))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!source.contains(" class ")) {
                    continue;
                }
                String service = file.getFileName().toString().replace(".java", "");
                for (Matcher m = METHOD.matcher(source); m.find(); ) {
                    String name = nameOf(m.group());
                    int open = bodyStart(source, m.end() - 1);
                    if (open < 0 || !m.group().stripLeading().startsWith("public") || !EDIT_NAME.matcher(name).matches()) {
                        continue;
                    }
                    edits++;
                    if (!STATE_CHECK.matcher(source.substring(open, SourceScan.closingBrace(source, open))).find()) {
                        actual.merge(service + "#" + name, 1, Integer::sum);
                    }
                }
            }
        }
        assertThat(edits).as("edit methods found").isGreaterThanOrEqualTo(50);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("an edit of a record with a terminal state that does not refuse the closed record first (QA D1428); "
                + "if the record has no terminal state, say so in ALLOWED" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    /** {file, method, what its body must contain}: the writes not named as edits, and the edits' helpers. */
    private static final List<String[]> NAMED_WRITES = List.of(
        new String[] {"impl/AdmissionServiceImpl.java", "updateAdmission", "requireWritableBy("},
        new String[] {"impl/AdmissionServiceImpl.java", "requireWritableBy", "requireOpen()"},
        new String[] {"impl/AdmissionServiceImpl.java", "applyRoomBed", "admission.roomBed.notHeldBed"},
        new String[] {"impl/AdmissionServiceImpl.java", "applyOrderSets", "requireOpen()"},
        new String[] {"impl/AdmissionOrderSetServiceImpl.java", "applyToAdmission", "requireOpen()"},
        new String[] {"BedAssignmentService.java", "unassignBed", "requireOpen()"},
        new String[] {"EncounterServiceImpl.java", "updateEncounter", "lateDocumentationOnClosedVisit("},
        new String[] {"EncounterServiceImpl.java", "lateDocumentationOnClosedVisit", "ENCOUNTER_CLOSED_READ_ONLY"},
        new String[] {"EncounterServiceImpl.java", "deleteEncounter", "isClosedStatus("},
        new String[] {"EncounterServiceImpl.java", "upsertDischargeSummaryForCheckout", "getIsFinalized()"},
        new String[] {"DischargeSummaryServiceImpl.java", "enrichSparseExistingSummaries", "getIsFinalized()"},
        new String[] {"EncounterTreatmentServiceImpl.java", "addTreatmentToEncounter", "ENDED_UNSEEN"},
        new String[] {"impl/NurseTaskServiceImpl.java", "verifyMedicationAdministration", "nurse.mar.alreadyRecorded"},
        // QA D1477: every nursing write on a patient goes through DischargedPatientGuard.
        new String[] {"impl/NurseTaskServiceImpl.java", "captureVitals", "requireNotDischargedWithoutVisit("},
        new String[] {"impl/NurseTaskServiceImpl.java", "createNursingTask", "nurse.task.patientDischarged"},
        new String[] {"impl/NurseTaskServiceImpl.java", "createHandoff", "nurse.handoff.patientDischarged"},
        new String[] {"impl/IsolationServiceImpl.java", "startPrecaution", "isolation.precaution.patientDischarged"},
        new String[] {"impl/IsolationServiceImpl.java", "resolveAdmission", "requireOpen("},
        new String[] {"impl/PatientVitalSignServiceImpl.java", "recordVital", "dischargedPatientGuard.requireNotDischargedWithoutVisit("},
        new String[] {"../fhir/write/EncounterFhirWriteService.java", "doUpdate", "isClosed()"},
        new String[] {"LabResultServiceImpl.java", "updateLabResult", "withdrawSignature("},
        new String[] {"LabResultServiceImpl.java", "deleteLabResult", "labresult.signed.noDelete"},
        new String[] {"TreatmentPlanServiceImpl.java", "addFollowUp", "requirePlanOpen("},
        new String[] {"TreatmentPlanServiceImpl.java", "updateFollowUp", "requirePlanOpen("},
        new String[] {"TreatmentPlanServiceImpl.java", "addReview", "requirePlanOpen("},
        new String[] {"PrescriptionServiceImpl.java", "updatePrescription", "requireDispensedOrderKept("},
        new String[] {"PrescriptionServiceImpl.java", "deletePrescription", "hasBeenDispensedFrom("},
        new String[] {"impl/ImagingReportServiceImpl.java", "updateReportStatus", "requireNotVoided("},
        // QA D1435: every way a stay ends closes its nursing work.
        new String[] {"AdmissionStayEncounterService.java", "recordCancellation", "closeNursingWorkOfCancelledStay("},
        new String[] {"AdmissionStayEncounterService.java", "applyDischarge", "closePendingNursingTasks("},
        new String[] {"impl/MortalityServiceImpl.java", "closeAdmissions", "closeNursingWorkOfEndedStay"},
        new String[] {"integration/impl/MllpInboundAdtVisitProjectionServiceImpl.java", "applyDischarge", "closeNursingWorkOfEndedStay"});

    @Test
    @DisplayName("the writes not named as edits keep the check that closes them")
    void theNamedWritesKeepTheirCheck() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String[] write : NAMED_WRITES) {
            String source = SourceScan.stripComments(Files.readString(SERVICES.resolve(write[0]), StandardCharsets.UTF_8));
            String body = bodyOf(source, write[1]);
            if (body == null || !body.contains(write[2])) {
                missing.add(write[0] + "#" + write[1] + " lost " + write[2]);
            }
        }
        assertThat(missing).as("a closed record's write lost its check (QA D1428)" + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("the stay's one choke point refuses every closed status")
    void theStaysChokePointKeysOnOpen() throws IOException {
        String source = SourceScan.stripComments(Files.readString(
            Path.of("src/main/java/com/example/hms/model/Admission.java"), StandardCharsets.UTF_8));
        assertThat(bodyOf(source, "requireOpen")).contains("AdmissionStatus.OPEN.contains(status)");
    }

    private static String nameOf(String declaration) {
        String[] words = declaration.substring(0, declaration.length() - 1).strip().split("\\s++");
        return words[words.length - 1];
    }

    /** The body's opening brace, or -1 for a declaration with none (an abstract method). */
    private static int bodyStart(String source, int openParen) {
        int close = SourceScan.closingParen(source, openParen);
        for (int i = close + 1; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                return i;
            }
            if (c == ';') {
                return -1;
            }
        }
        return -1;
    }

    private static String bodyOf(String source, String method) {
        for (Matcher m = METHOD.matcher(source); m.find(); ) {
            int open = method.equals(nameOf(m.group())) ? bodyStart(source, m.end() - 1) : -1;
            if (open >= 0) {
                return source.substring(open, SourceScan.closingBrace(source, open));
            }
        }
        return null;
    }
}
