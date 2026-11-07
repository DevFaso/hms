package com.example.hms.architecture;

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
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1488 (round 36): a recorded death takes effect. Every write endpoint of the API is classified here: it
 * refuses new care through {@code DeceasedPatientGuard}, or it stays open and says why. A new endpoint or
 * controller fails until it is classified, on purpose: that is the moment somebody decides what it does for a
 * patient who has died.
 */
@DisplayName("A recorded death refuses new care, and every write endpoint is classified")
class DeceasedPatientCareGuardTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/example/hms/controller");
    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    private static final Pattern WRITE_MAPPING = Pattern.compile("@(?:Post|Put|Patch|Delete)Mapping\\b");
    private static final Pattern ENDPOINT = Pattern.compile("\\n\\s*+public\\s[^;{=(]*+\\(");
    private static final Pattern ANY_METHOD = Pattern.compile("\\n    (?:public|private|protected)\\s[^;{=(]*+\\(");
    private static final String GUARD_CALL = "DeceasedPatientGuard.refuse";

    /** {@code Controller#endpoint | ServiceClass#method} (the method holding the guard's call). */
    private static final String REFUSED = """
        Admission#admitPatient | AdmissionServiceImpl#admitPatient
        AdvanceDirective#create | AdvanceDirectiveServiceImpl#create
        Appointment#createAppointment | AppointmentServiceImpl#createAppointment
        Appointment#updateAppointment | AppointmentServiceImpl#updateAppointment
        Appointment#updateAppointmentStatus | AppointmentServiceImpl#confirmOrCancelAppointment
        BirthPlan#createBirthPlan | BirthPlanServiceImpl#createBirthPlan
        Consultation#createConsultation | ConsultationServiceImpl#createConsultation
        Consultation#scheduleConsultation | ConsultationServiceImpl#scheduleConsultation
        Consultation#startConsultation | ConsultationServiceImpl#startConsultation
        Dispense#dispense | DispenseServiceImpl#validateFill
        Dispense#markReady | DispenseServiceImpl#validateFill
        Dispense#handOver | DispenseServiceImpl#handOver
        Encounter#create | EncounterServiceImpl#createEncounter
        Encounter#submitTriage | EncounterServiceImpl#submitTriage
        Encounter#startEncounter | EncounterServiceImpl#startEncounter
        Encounter#completeTriage | EncounterServiceImpl#completeTriage
        Encounter#submitNursingIntake | EncounterServiceImpl#submitNursingIntake
        EncounterTreatment#addTreatmentToEncounter | EncounterTreatmentServiceImpl#addTreatmentToEncounter
        GeneralReferral#createReferral | GeneralReferralServiceImpl#createReferral
        GeneralReferral#submitReferral | GeneralReferralServiceImpl#submitReferral
        GeneralReferral#scheduleReferral | GeneralReferralServiceImpl#scheduleReferral
        GeneralReferral#startReferral | GeneralReferralServiceImpl#startReferral
        HighRiskPregnancyCarePlan#createPlan | HighRiskPregnancyCarePlanServiceImpl#createPlan
        HighRiskPregnancyCarePlan#addBloodPressureLog | HighRiskPregnancyCarePlanServiceImpl#addBloodPressureLog
        HighRiskPregnancyCarePlan#addMedicationLog | HighRiskPregnancyCarePlanServiceImpl#addMedicationLog
        ImagingOrder#createOrder | ImagingOrderServiceImpl#createOrder
        IntakeOutput#recordEntry | IntakeOutputServiceImpl#recordEntry
        Isolation#startPrecaution | IsolationServiceImpl#startPrecaution
        LabOrder#createLabOrder | LabOrderServiceImpl#buildLabOrder
        LabSpecimen#collectSpecimen | LabSpecimenServiceImpl#createSpecimen
        Labor#startEpisode | LaborServiceImpl#startEpisode
        Labor#addEntry | LaborServiceImpl#addEntry
        MaternalHistory#createMaternalHistory | MaternalHistoryServiceImpl#createMaternalHistory
        MedicalHistory#createImmunization | ImmunizationServiceImpl#createImmunization
        MtmReview#start | MtmReviewServiceImpl#startReview
        NewbornAssessment#recordAssessment | NewbornAssessmentServiceImpl#recordAssessment
        NurseTask#verifyMedication | NurseTaskServiceImpl#requireOrderStillStands
        NurseTask#administerMedication | NurseTaskServiceImpl#requireOrderStillStands
        NurseTask#createHandoff | NurseTaskServiceImpl#createHandoff
        NurseTask#captureVitals | NurseTaskServiceImpl#captureVitals
        NurseTask#createNursingTask | NurseTaskServiceImpl#createNursingTask
        ObgynReferral#createReferral | ObgynReferralServiceImpl#createReferral
        ObgynReferral#startReferral | ObgynReferralServiceImpl#startReferral
        PatientPanel#assign | PanelService#assign
        PatientPortal#scheduleMyAppointment | PatientPortalServiceImpl#scheduleMyAppointment
        PatientPortal#rescheduleMyAppointment | PatientPortalServiceImpl#rescheduleMyAppointment
        PatientPortal#recordHomeVital | PatientVitalSignServiceImpl#recordVital
        PatientPortal#requestRefill | PatientPortalServiceImpl#requestMedicationRefill
        PatientPortal#submitScreening | ProResponseService#persist
        PatientPortal#submitPreCheckIn | PatientPortalServiceImpl#submitPreCheckIn
        PatientPrimaryCare#assign | PatientPrimaryCareServiceImpl#assignPrimaryCare
        PatientRecall#create | PatientRecallServiceImpl#createRecall
        PatientVitalSign#recordVital | PatientVitalSignServiceImpl#recordVital
        PharmacySale#create | PharmacySaleServiceImpl#createSale
        PostpartumCare#recordObservation | PostpartumCareServiceImpl#recordObservation
        PrenatalScheduling#generateSchedule | PrenatalSchedulingServiceImpl#generateSchedule
        PrenatalScheduling#reschedulePrenatalAppointment | PrenatalSchedulingServiceImpl#reschedulePrenatalAppointment
        PrenatalScheduling#createReminder | PrenatalSchedulingServiceImpl#createReminder
        Prescription#create | PrescriptionServiceImpl#createPrescription
        Prescription#sign | PrescriptionServiceImpl#signPrescription
        Prescription#cosign | PrescriptionServiceImpl#cosignPrescription
        Prescription#dispatchSms | PrescriptionSmsDispatchServiceImpl#dispatch
        ProResponse#record | ProResponseService#persist
        ProcedureOrder#createProcedureOrder | ProcedureOrderServiceImpl#createProcedureOrder
        ProgramEnrollment#enroll | ProgramEnrollmentService#enroll
        ProgramEnrollment#recordVisit | ProgramEnrollmentService#recordVisit
        Reception#checkInPatient | ReceptionServiceImpl#checkInPatient
        Reception#addToWaitlist | ReceptionServiceImpl#addToWaitlist
        Reception#offerWaitlistSlot | ReceptionServiceImpl#offerWaitlistSlot
        Reception#acceptWaitlistOffer | ReceptionServiceImpl#acceptWaitlistOffer
        RefillApproval#approve | RefillApprovalServiceImpl#decide
        SlotInventory#book | SlotInventoryServiceImpl#book
        StockOutRouting#routeToPartner | StockOutRoutingServiceImpl#validateRoutableStatus
        StockOutRouting#printForPatient | StockOutRoutingServiceImpl#validateRoutableStatus
        StockOutRouting#backOrder | StockOutRoutingServiceImpl#validateRoutableStatus
        SuperAdminLabOrder#createLabOrder | LabOrderServiceImpl#buildLabOrder
        Transfusion#createRequest | TransfusionServiceImpl#createRequest
        Transfusion#issueUnit | TransfusionServiceImpl#issueUnit
        Transfusion#emergencyRelease | TransfusionServiceImpl#emergencyRelease
        Transfusion#startAdministration | TransfusionServiceImpl#startAdministration
        TreatmentConsent#record | TreatmentConsentServiceImpl#record
        TreatmentPlan#create | TreatmentPlanServiceImpl#create
        Ultrasound#createOrder | UltrasoundServiceImpl#createOrder
        """;

    /** {@code Controller#endpoint,endpoint | why it stays open after a death}. Hospitals need these. */
    private static final String STAYS_OPEN = """
        Admission#dischargePatient,cancelAdmission | discharge and stay close-out
        Admission#updateAdmission,applyOrderSets,assignBed,unassignBed | a stay the death closed refuses it (requireOpen); late notes on a stay keep their real time
        Admission#createOrderSet,deactivateOrderSet | master data, no patient
        AdmissionOrderSet#create,update,deactivate | master data, no patient
        AdmissionOrderSet#apply | a stay the death closed refuses it (requireOpen)
        AdvanceDirective#update,revoke | an existing document, late documentation
        Appointment#deleteAppointment | removes a visit
        Appointment#runReminderSweep | the sweep already skips the deceased
        Appointment#searchAppointments | a read over POST
        BirthPlan#updateBirthPlan,providerReview,deleteBirthPlan | an existing record, late documentation
        Consultation#acknowledgeConsultation,updateConsultation,completeConsultation,declineConsultation,cancelConsultation,assignConsultation,reassignConsultation | closes or documents a request made before the death
        Dispense#claimQueueRow,takeOverQueueRow,releaseQueueRow | the work queue: nothing leaves the pharmacy
        Dispense#cancelReady,cancelDispense | hands stock back
        Encounter#update,delete,completeExamination,markReadyForDischarge,checkOut,closeUnseen | late documentation and visit close-out
        Encounter#upsertEncounterNote,addEncounterNoteAddendum,signEncounterNote,cosignEncounterNote | notes about care before the death, with their real time
        Encounter#issueMedicalCertificate | certificates
        GeneralReferral#acknowledgeReferral,completeReferral,rejectReferral,cancelReferral,expireOverdueReferrals | closes or follows a referral made before the death; submitting one is refused
        HighRiskPregnancyCarePlan#updatePlan,addCareTeamNote,markMilestoneComplete | late documentation
        ImagingOrder#updateOrder,updateStatus,captureSignature | closes or documents an order made before the death
        Isolation#discontinuePrecaution | lifting a precaution
        LabOrder#updateLabOrder,deleteLabOrder,transitionLabOrderStatus,voidLabOrder | closes or corrects an order made before the death; results of its samples stay open
        LabSpecimen#receiveSpecimen,rejectSpecimen | a sample collected before the death
        Labor#recordDelivery | a delivery during which the mother died is recorded, with its real time
        MaternalHistory#updateMaternalHistory,deleteMaternalHistory,markAsReviewed,calculateRiskScore | an existing record, late documentation; only a new one is refused
        MedicalHistory#createSocialHistory,updateSocialHistory,deleteSocialHistory,createFamilyHistory,updateFamilyHistory,deleteFamilyHistory | history, late documentation
        MedicalHistory#markReminderSent,updateImmunization,deleteImmunization | an existing dose, late documentation
        MtmReview#update | an existing review, late documentation
        NurseTask#completeHandoff,completeNursingTask,markInboxRead | closes work
        NurseTask#createCareNote | notes about care before the death, with their real time
        ObgynReferral#acknowledgeReferral,completeReferral,cancelReferral,addMessage | closes or follows a referral made before the death
        Patient#createPatientByReceptionist,updatePatient,setChartRestriction,patchPatient,deletePatient | the patient record itself, not care
        Patient#getDoctorRecord,getDoctorTimeline | reads over POST
        Patient#createPatientAllergy,updatePatientAllergy,deactivatePatientAllergy,createPatientDiagnosis,updatePatientDiagnosis,deletePatientDiagnosis,createPatientChartUpdate | late documentation: a final diagnosis is routinely written after a death
        PatientPanel#end | closes a panel assignment
        PatientPortal#updateMyProfile,updateNotificationPreferences,markNotificationRead,markAllNotificationsRead | account housekeeping
        PatientPortal#payMyInvoice | billing and payments
        PatientPortal#cancelMyAppointment,cancelMyRefill | cancels
        PatientPortal#createRoiRequest,grantProxy,revokeProxy,uploadDocument,deleteDocument | records access and documents, not care
        PatientPortal#updateMyEducationProgress,submitMyEducationQuestion | education, not care
        PatientPrimaryCare#update,end,delete | an existing assignment
        PatientRecall#close,cancel,linkAppointment | closes a recall; the booking it links is refused itself
        Prescription#pharmacistVerify,pediatricDoseReview,requestClarification,resolveClarification,update,delete | an order made before the death: nothing goes out, signing and dispensing it are refused
        ProResponse#acknowledge | closes a response given before the death
        ProcedureOrder#updateProcedureOrder,cancelProcedureOrder | closes an order made before the death
        ProgramEnrollment#updateStatus | how an enrolment is closed, DECEASED included
        Reception#declineWaitlistOffer,closeWaitlistEntry,updateEncounterStatus | closes; a closed visit refuses the desk's status write
        Reception#attestEligibility | billing and payments
        RefillApproval#reject,pause | refuses a refill
        SlotInventory#generate,hold,release,block | the schedule, no patient
        StockOutRouting#partnerRespond,confirmPartnerDispense,partnerNoShow | a partner's outcome of a routing made before the death
        Transfer#requestTransfer,completeTransfer,cancelTransfer | a stay the death closed refuses it (TRANSFERABLE_STATUSES)
        Transfusion#recordReaction | transfusion reaction records
        Transfusion#completeAdministration,stopAdministration | ends a transfusion already running
        Transfusion#cancelRequest | closes a request
        Transfusion#recordCrossmatch,recordBloodGroup | a laboratory result on a sample collected before the death
        Transfusion#receiveUnit,discardUnit | blood bank stock, no patient
        TreatmentConsent#revoke | closes a consent
        TreatmentPlan#update,addFollowUp,updateFollowUp,addReview | an existing plan, late documentation
        Ultrasound#updateOrder,cancelOrder,createOrUpdateReport,markReportReviewed,markPatientNotified | closes or reports an order made before the death
        """;

    private static final String BILLING = "billing and payments";
    private static final String RESULTS = "results of samples collected before the death";
    private static final String NOT_CARE = "administration: writes no care for a patient";
    private static final String RECORDS = "records, notes and access: reads and late documentation with their real time";
    private static final String INBOUND = "what another system reports happened: a fact, with its own time";

    /** Controller -> why none of its writes is new care. */
    private static final Map<String, String> WHOLE_CONTROLLER = Map.ofEntries(
        Map.entry("BillingInvoice", BILLING), Map.entry("Eligibility", BILLING), Map.entry("InvoiceEmail", BILLING),
        Map.entry("InvoiceItem", BILLING), Map.entry("PatientGuarantor", BILLING), Map.entry("PatientInsurance", BILLING),
        Map.entry("PharmacyClaim", BILLING), Map.entry("PharmacyPayment", BILLING),
        Map.entry("DischargeApproval", "discharge and stay close-out"),
        Map.entry("DischargeSummary", "discharge and stay close-out"),
        Map.entry("Mortality", "the death record, and its dispute, which is what lifts the block"),
        Map.entry("ImagingResult", RESULTS), Map.entry("LabResult", RESULTS), Map.entry("MicroCulture", RESULTS),
        Map.entry("BreakGlass", RECORDS), Map.entry("CdsAcknowledgement", RECORDS), Map.entry("DigitalSignature", RECORDS),
        Map.entry("Empi", RECORDS), Map.entry("FileUpload", RECORDS), Map.entry("MedicationHistory", RECORDS),
        Map.entry("NursingNote", RECORDS), Map.entry("PatientEducation", RECORDS),
        Map.entry("PatientHospitalRegistration", RECORDS), Map.entry("PatientPhoto", RECORDS), Map.entry("PatientRoi", RECORDS),
        Map.entry("RecordAccess", RECORDS), Map.entry("RoiWorklist", RECORDS), Map.entry("SensitivityTagging", RECORDS),
        Map.entry("Hl7Inbound", INBOUND), Map.entry("InstrumentOutbox", INBOUND), Map.entry("PartnerSmsWebhook", INBOUND),
        Map.entry("SuperAdminIntegrationMessage", INBOUND),
        Map.entry("AdtIntakeProviderConfig", NOT_CARE), Map.entry("Announcement", NOT_CARE), Map.entry("ApiKey", NOT_CARE),
        Map.entry("Auth", NOT_CARE), Map.entry("BedManagement", NOT_CARE), Map.entry("Chat", NOT_CARE),
        Map.entry("Credentialing", NOT_CARE), Map.entry("Department", NOT_CARE), Map.entry("Dhis2Admin", NOT_CARE),
        Map.entry("Dhis2Export", NOT_CARE), Map.entry("Downtime", NOT_CARE), Map.entry("DrugInteractionAdmin", NOT_CARE),
        Map.entry("EmpiProbabilistic", NOT_CARE), Map.entry("FeatureFlag", NOT_CARE), Map.entry("FrontendAudit", NOT_CARE),
        Map.entry("Hospital", NOT_CARE), Map.entry("HospitalRecordAccessPosture", NOT_CARE), Map.entry("InBasket", NOT_CARE),
        Map.entry("Inventory", NOT_CARE), Map.entry("LabInstrument", NOT_CARE), Map.entry("LabInventory", NOT_CARE),
        Map.entry("LabQcEvent", NOT_CARE), Map.entry("LabReflexRule", NOT_CARE), Map.entry("LabTestDefinition", NOT_CARE),
        Map.entry("LabTestValidationStudy", NOT_CARE), Map.entry("Me", NOT_CARE), Map.entry("MedicationCatalog", NOT_CARE),
        Map.entry("Mfa", NOT_CARE), Map.entry("MllpAllowedSender", NOT_CARE), Map.entry("Notification", NOT_CARE),
        Map.entry("OnCallSchedule", NOT_CARE), Map.entry("Organization", NOT_CARE), Map.entry("OrganizationSecurity", NOT_CARE),
        Map.entry("OrganizationSecurityPolicy", NOT_CARE), Map.entry("OrganizationSecurityRule", NOT_CARE),
        Map.entry("OwnEmailChange", NOT_CARE), Map.entry("PasswordReset", NOT_CARE), Map.entry("Permission", NOT_CARE),
        Map.entry("PermissionMatrix", NOT_CARE), Map.entry("PharmacyRegistry", NOT_CARE), Map.entry("PhoneVerification", NOT_CARE),
        Map.entry("PlatformRegistry", NOT_CARE), Map.entry("ProInstrument", NOT_CARE), Map.entry("ProviderAdmin", NOT_CARE),
        Map.entry("ReferenceCatalog", NOT_CARE), Map.entry("Report", NOT_CARE), Map.entry("Role", NOT_CARE),
        Map.entry("ServiceTranslation", NOT_CARE), Map.entry("SessionTemplate", NOT_CARE), Map.entry("SmartPhrase", NOT_CARE),
        Map.entry("Staff", NOT_CARE), Map.entry("StaffAvailability", NOT_CARE), Map.entry("StaffScheduling", NOT_CARE),
        Map.entry("StockTransaction", NOT_CARE), Map.entry("SuperAdminAuditSavedSearch", NOT_CARE),
        Map.entry("SuperAdminCredentialLifecycle", NOT_CARE), Map.entry("SuperAdminEmergency", NOT_CARE),
        Map.entry("SuperAdminHospitalLifecycle", NOT_CARE), Map.entry("SuperAdminImpersonation", NOT_CARE),
        Map.entry("SuperAdminIntegrationHealth", NOT_CARE), Map.entry("SuperAdminOrganization", NOT_CARE),
        Map.entry("SuperAdminPlatformRegistry", NOT_CARE), Map.entry("SuperAdminProvider", NOT_CARE),
        Map.entry("SuperAdminRegionPolicy", NOT_CARE), Map.entry("SuperAdminSecurityPolicy", NOT_CARE),
        Map.entry("SuperAdminSecurityRule", NOT_CARE), Map.entry("SuperAdminSubscription", NOT_CARE),
        Map.entry("SuperAdminUserGovernance", NOT_CARE), Map.entry("TenantProvisioning", NOT_CARE),
        Map.entry("TenantSchemaCache", NOT_CARE), Map.entry("Treatment", NOT_CARE), Map.entry("User", NOT_CARE),
        Map.entry("UserRoleHospitalAssignment", NOT_CARE), Map.entry("UsernameReminder", NOT_CARE),
        Map.entry("VisitType", NOT_CARE), Map.entry("WebhookEndpoint", NOT_CARE));

    @Test
    @DisplayName("every write endpoint is refused, or stays open and says why")
    void everyWriteEndpointIsClassified() throws IOException {
        Set<String> classified = new TreeSet<>(refused().keySet());
        classified.addAll(staysOpen());
        Set<String> endpoints = writeEndpoints();

        List<String> unclassified = endpoints.stream()
            .filter(endpoint -> !classified.contains(endpoint))
            .filter(endpoint -> !WHOLE_CONTROLLER.containsKey(endpoint.substring(0, endpoint.indexOf('#'))))
            .toList();
        assertThat(endpoints).as("the scan must find the API's writes, or it is scanning nothing").hasSizeGreaterThan(500);
        assertThat(unclassified)
            .as("a write endpoint nobody decided about: does it give new care to a patient who has died? Call "
                + "DeceasedPatientGuard and add it to REFUSED, or add it to STAYS_OPEN with why (QA D1488)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(classified.stream().filter(entry -> !endpoints.contains(entry)).toList())
            .as("classified, but no such write endpoint any more: delete its line").isEmpty();
        assertThat(WHOLE_CONTROLLER.keySet().stream()
                .filter(controller -> endpoints.stream().noneMatch(e -> e.startsWith(controller + "#"))).toList())
            .as("no such controller with writes any more: delete its line").isEmpty();
    }

    @Test
    @DisplayName("a refused endpoint's service method calls the guard, and no endpoint is classified twice")
    void everyRefusedEndpointReachesTheGuard() throws IOException {
        Map<String, Path> services = servicesByName();
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, String> row : refused().entrySet()) {
            String[] target = row.getValue().split("#");
            Path file = services.get(target[0]);
            assertThat(file).as(target[0]).isNotNull();
            String body = bodyOf(SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)), target[1]);
            if (body == null || !body.contains(GUARD_CALL)) {
                missing.add(row.getKey() + " -> " + row.getValue());
            }
        }
        assertThat(missing).as("gives new care to a patient who has died (QA D1488)" + SourceScan.MANDATE).isEmpty();
        assertThat(staysOpen()).as("both refused and open").doesNotContainAnyElementsOf(refused().keySet());
    }

    /**
     * The dispute's one rule: no service reads the death itself to decide about care. An exemption written at one
     * entry point would admit a patient whose medication or blood stays blocked downstream.
     */
    @Test
    @DisplayName("nothing but the guard reads the death to decide about care, and every refusal asks careBlocked")
    void oneEligibilityRule() throws IOException {
        Set<String> allowed = Set.of("DeceasedPatientGuard", "MortalityServiceImpl");
        List<String> readers = new ArrayList<>();
        for (Map.Entry<String, Path> service : servicesByName().entrySet()) {
            String source = SourceScan.stripComments(Files.readString(service.getValue(), StandardCharsets.UTF_8));
            if (!allowed.contains(service.getKey()) && (source.contains(".isDeceased()") || source.contains(".getDeceasedAt()"))) {
                readers.add(service.getKey());
            }
        }
        assertThat(readers).as("decide through DeceasedPatientGuard.careBlocked, not an exemption of its own").isEmpty();
        String guard = SourceScan.stripComments(Files.readString(
            SERVICES.resolve("support/DeceasedPatientGuard.java"), StandardCharsets.UTF_8));
        for (String refusal : List.of("refuseNewCare", "refuseCareAfterDeath", "refuseCareAfterDeathDay")) {
            assertThat(bodyOf(guard, refusal)).as(refusal).contains("careBlocked(patient)");
        }
    }

    /** What the user's rule names must keep its place: these never move to STAYS_OPEN by accident. */
    @Test
    @DisplayName("the writes the ruling keeps open are not refused")
    void whatTheRulingKeepsOpenStaysOpen() throws IOException {
        Map<String, Path> services = servicesByName();
        for (String[] open : List.of(
                new String[] {"MortalityServiceImpl", "amendDeathRecord"},
                new String[] {"MortalityServiceImpl", "markEnteredInError"},
                new String[] {"TransfusionServiceImpl", "recordReaction"},
                new String[] {"LabResultServiceImpl", "createLabResult"},
                new String[] {"NurseTaskServiceImpl", "createCareNote"},
                new String[] {"NursingNoteServiceImpl", "createNote"},
                new String[] {"DischargeSummaryServiceImpl", "createDischargeSummary"},
                new String[] {"LaborServiceImpl", "recordDelivery"})) {
            String body = bodyOf(SourceScan.stripComments(Files.readString(services.get(open[0]), StandardCharsets.UTF_8)), open[1]);
            assertThat(body).as(open[0] + "#" + open[1]).isNotNull().doesNotContain(GUARD_CALL);
        }
    }

    private static Map<String, String> refused() {
        Map<String, String> rows = new TreeMap<>();
        REFUSED.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(line -> {
            String[] cells = line.split("\\s*+\\|\\s*+", 2);
            rows.put(cells[0], cells[1]);
        });
        return rows;
    }

    private static Set<String> staysOpen() {
        Set<String> rows = new TreeSet<>();
        STAYS_OPEN.lines().map(String::strip).filter(line -> !line.isEmpty()).forEach(line -> {
            String[] head = line.split("\\s*+\\|\\s*+", 2)[0].split("#", 2);
            for (String endpoint : head[1].split(",")) {
                rows.add(head[0] + "#" + endpoint);
            }
        });
        return rows;
    }

    private static Set<String> writeEndpoints() throws IOException {
        Set<String> endpoints = new TreeSet<>();
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            for (Path file : files.filter(f -> f.toString().endsWith("Controller.java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String controller = file.getFileName().toString().replace("Controller.java", "");
                Matcher mapping = WRITE_MAPPING.matcher(source);
                while (mapping.find()) {
                    Matcher endpoint = ENDPOINT.matcher(source);
                    if (endpoint.find(mapping.end())) {
                        endpoints.add(controller + "#" + nameOf(endpoint.group()));
                    }
                }
            }
        }
        return endpoints;
    }

    private static Map<String, Path> servicesByName() throws IOException {
        Map<String, Path> services = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            files.filter(f -> f.toString().endsWith(".java"))
                .forEach(f -> services.put(f.getFileName().toString().replace(".java", ""), f));
        }
        return services;
    }

    private static String nameOf(String declaration) {
        String[] words = declaration.substring(0, declaration.length() - 1).strip().split("\\s++");
        return words[words.length - 1];
    }

    /** Every overload's body joined: the guard in any of them counts, as a helper overload is the same method. */
    private static String bodyOf(String source, String method) {
        Matcher m = ANY_METHOD.matcher(source);
        StringBuilder bodies = new StringBuilder();
        while (m.find()) {
            if (!method.equals(nameOf(m.group()))) {
                continue;
            }
            int close = SourceScan.closingParen(source, m.end() - 1);
            int open = source.indexOf('{', close);
            int semi = source.indexOf(';', close);
            if (open >= 0 && (semi < 0 || open < semi)) {
                bodies.append(source, open, SourceScan.closingBrace(source, open));
            }
        }
        return bodies.isEmpty() ? null : bodies.toString();
    }
}
