package com.example.hms.architecture;

import com.example.hms.service.timeline.VisitTimelineRows;
import com.example.hms.testsupport.SourceScan;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D959 guard (NOT_FIXED in rounds 17 and 18): the chronology left out a
 * kind of clinical event. Round 17 added the plan, the consultation request,
 * the discharge summary and the certificate; round 18 still found transfusion
 * requests, procedure orders and OB/GYN referrals missing - each fix listed
 * the screens reported and nobody enumerated the record types.
 *
 * <p>This guard enumerates them. Every JPA entity that belongs to a patient
 * (a {@code Patient} association or a {@code patientId} column) is either a
 * chronology event with its category ({@link #EVENTS}) or is not one, with
 * the reason a reviewer can check ({@link #NOT_EVENTS}). A new patient-owned
 * entity fails here until someone decides. Every category then needs a row
 * source (the registry in {@code VisitTimelineRows}, or the shared rows of
 * {@code PatientServiceImpl}), a backend label in the three bundles, and a
 * portal label (en/fr/es {@code PORTAL.ENUM.TIMELINE_CATEGORY}) and icon
 * ({@code patient-chart.component.ts#timelineIcon}).
 */
@DisplayName("Every clinical event type is on the chronology")
class TimelineEventRegistryGuardTest {

    private static final Path MODEL = Path.of("src/main/java/com/example/hms/model");
    private static final Path PATIENT_SERVICE = Path.of("src/main/java/com/example/hms/service/PatientServiceImpl.java");
    private static final Path PORTAL = Path.of("../hospital-portal/src");
    private static final Path BUNDLES = Path.of("src/main/resources/i18n");

    /** Patient-owned entities that are chronology events, and the category their row carries. */
    static final Map<String, String> EVENTS = Map.ofEntries(
        // Shared rows, written by PatientServiceImpl (cross-hospital accounting).
        Map.entry("Encounter", "ENCOUNTER"),
        Map.entry("Prescription", "PRESCRIPTION"),
        Map.entry("LabResult", "LAB_RESULT"),
        Map.entry("PatientAllergy", "ALLERGY"),
        Map.entry("UltrasoundOrder", "IMAGING"),
        Map.entry("UltrasoundReport", "IMAGING"),
        Map.entry("PatientSurgicalHistory", "PROCEDURE"),
        // The acting hospital's visit rows (VisitTimelineRows registry).
        Map.entry("PatientProblem", "DIAGNOSIS"),
        Map.entry("LabOrder", "LAB_ORDER"),
        Map.entry("ImagingOrder", "IMAGING"),
        Map.entry("EncounterNote", "NOTE"),
        Map.entry("GeneralReferral", "REFERRAL"),
        Map.entry("TreatmentPlan", "TREATMENT_PLAN"),
        Map.entry("Consultation", "CONSULTATION"),
        Map.entry("DischargeSummary", "DISCHARGE_SUMMARY"),
        Map.entry("PatientUploadedDocument", "DOCUMENT"),
        Map.entry("TransfusionRequest", "TRANSFUSION"),
        Map.entry("ProcedureOrder", "PROCEDURE_ORDER"),
        Map.entry("ObgynReferral", "OBGYN_REFERRAL"),
        Map.entry("NursingNote", "NURSING_NOTE"),
        Map.entry("PatientImmunization", "IMMUNIZATION"),
        Map.entry("DeliveryRecord", "DELIVERY"),
        Map.entry("DeathRecord", "DEATH"),
        Map.entry("MicroCultureResult", "MICRO_CULTURE"));

    /** Patient-owned entities that are not chronology events, each with why. Decide before adding one. */
    static final Map<String, String> NOT_EVENTS = Map.ofEntries(
        Map.entry("Admission", "the stay is on the chronology as its INPATIENT encounter row (AdmissionStayEncounterService)"),
        Map.entry("TransferOrder", "a bed/ward move inside the stay; the stay's encounter row carries the stay"),
        Map.entry("DischargeApproval", "the nurse's request to discharge; the outcome is the discharge summary row"),
        Map.entry("IsolationPrecaution", "a standing ward precaution shown on the bed board and banner, not an event"),
        Map.entry("AdvanceDirective", "a standing directive shown in the chart's Directives section"),
        Map.entry("Appointment", "a booking; the care given is the visit's encounter row"),
        Map.entry("AppointmentWaitlist", "scheduling, not care"),
        Map.entry("PatientRecall", "a scheduling reminder, not care"),
        Map.entry("BillingInvoice", "billing, not clinical"),
        Map.entry("PharmacyClaim", "billing, not clinical"),
        Map.entry("PharmacyPayment", "billing, not clinical"),
        Map.entry("PharmacySale", "a sale; the prescription row is the clinical event"),
        Map.entry("PharmacyFill", "the pharmacy's fill of a prescription; the prescription row carries its status"),
        Map.entry("Dispense", "the pharmacy's dispense of a prescription; the prescription row carries its status"),
        Map.entry("PrescriptionRoutingDecision", "where a prescription is filled; the prescription row carries its status"),
        Map.entry("RefillRequest", "the patient's request; a granted refill is a prescription row"),
        Map.entry("EligibilityCheck", "insurance eligibility, not clinical"),
        Map.entry("PatientInsurance", "coverage, not clinical"),
        Map.entry("PatientGuarantor", "billing responsibility, not clinical"),
        Map.entry("BreakGlassSession", "an access-control session, audited apart"),
        Map.entry("AuditEventLog", "the audit trail, not the record"),
        Map.entry("CdsAcknowledgement", "a decision-support override, audited apart"),
        Map.entry("InBasketItem", "a clinician's work queue item about a record that has its own row"),
        Map.entry("NursingTask", "a ward work item; what was done is charted (MAR, vitals, nursing note)"),
        Map.entry("NurseHandoff", "a shift handover between nurses, not care given to the patient"),
        Map.entry("MedicationAdministrationRecord", "dose-level detail on the MAR tab; the order is the prescription row"),
        Map.entry("TransfusionAdministration", "unit-level detail on the transfusion screen; the request row carries the transfusion"),
        Map.entry("TransfusionReaction", "reported on the transfusion administration and the haemovigilance screen; the request row carries the transfusion"),
        Map.entry("IntakeOutputEntry", "fluid-balance observations, charted on the I/O tab"),
        Map.entry("PatientVitalSign", "observations, charted on the vitals tab and its graph"),
        Map.entry("PostpartumObservation", "observations, charted on the postpartum tab"),
        Map.entry("LaborPartographEntry", "partograph observations, charted on the labour tab"),
        Map.entry("NewbornAssessment", "observations of the newborn, charted on the postpartum tab"),
        Map.entry("LaborEpisode", "the labour episode; its outcome is the delivery row"),
        Map.entry("MaternalHistory", "a versioned history document, shown on the maternity tab"),
        Map.entry("BirthPlan", "the patient's preferences, shown on the maternity tab"),
        Map.entry("HighRiskPregnancyCarePlan", "a standing care plan, shown on the maternity tab"),
        Map.entry("PostpartumCarePlan", "a standing care plan, shown on the postpartum tab"),
        Map.entry("ObstetricAlertEscalation", "an alert routing record; the clinical event is the observation that raised it"),
        Map.entry("PatientDiagnosis", "the legacy diagnosis table; the chronology reads PatientProblem (DIAGNOSIS)"),
        Map.entry("PatientProblemHistory", "the audit history of a problem; the problem row carries its status"),
        Map.entry("PatientFamilyHistory", "history, shown in the chart's history sections"),
        Map.entry("PatientSocialHistory", "history, shown in the chart's history sections"),
        Map.entry("PatientBloodGroup", "a standing attribute shown on the banner and transfusion screen"),
        Map.entry("PatientChartUpdate", "the chart's own change log (Mises à jour tab)"),
        Map.entry("PatientConsent", "consent administration, not care"),
        Map.entry("PatientTreatmentConsent", "consent administration, not care"),
        Map.entry("PatientRecordSharingOptOut", "a privacy preference"),
        Map.entry("PatientProxy", "a family-access grant, not care"),
        Map.entry("PatientPrimaryCare", "an administrative assignment"),
        Map.entry("PanelAssignment", "an administrative assignment"),
        Map.entry("PatientHospitalRegistration", "registration at a hospital, not care"),
        Map.entry("PatientAddressHistory", "demographics"),
        Map.entry("EmpiMasterIdentity", "identity management"),
        Map.entry("User", "the account, not the record"),
        Map.entry("ProgramEnrollment", "a programme enrolment, administrative"),
        Map.entry("MtmReview", "the pharmacist's medication review; its outcome reaches the prescriptions"),
        Map.entry("ProResponse", "a patient-reported questionnaire answer, shown on its own screen"),
        Map.entry("QuestionnaireResponse", "a patient-reported questionnaire answer, shown on its own screen"),
        Map.entry("PatientEducationProgress", "patient education tracking"),
        Map.entry("PatientEducationQuestion", "patient education tracking"),
        Map.entry("VisitEducationDocumentation", "patient education tracking"),
        Map.entry("RoiRequest", "a release-of-information request, administrative"));

    private static final Pattern PATIENT_FIELD = Pattern.compile(
        "private\\s+Patient\\s+\\w+\\s*;|private\\s+(?:java\\.util\\.)?UUID\\s+patientId\\s*;");

    @Test
    @DisplayName("every patient-owned entity is decided: a chronology category or a reason it is not one")
    void everyPatientEntityIsDecided() throws IOException {
        Set<String> patientEntities = new TreeSet<>();
        Set<String> allEntities = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(MODEL)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!source.contains("@Entity")) {
                    continue;
                }
                String name = file.getFileName().toString().replace(".java", "");
                allEntities.add(name);
                if (PATIENT_FIELD.matcher(source).find()) {
                    patientEntities.add(name);
                }
            }
        }
        assertThat(patientEntities).as("patient-owned entities found").hasSizeGreaterThanOrEqualTo(80);
        List<String> undecided = new ArrayList<>();
        for (String entity : patientEntities) {
            if (!EVENTS.containsKey(entity) && !NOT_EVENTS.containsKey(entity)) {
                undecided.add(entity);
            }
        }
        assertThat(undecided)
            .as("a patient-owned entity nobody decided about: put it on the chronology (a VisitTimelineRows "
                + "source, a category label in the bundles and the portal, an icon) or in NOT_EVENTS with the "
                + "reason (QA D959)" + SourceScan.MANDATE)
            .isEmpty();
        Set<String> both = new TreeSet<>(EVENTS.keySet());
        both.retainAll(NOT_EVENTS.keySet());
        assertThat(both).as("decided both ways").isEmpty();
        Set<String> gone = new TreeSet<>(EVENTS.keySet());
        gone.addAll(NOT_EVENTS.keySet());
        gone.removeAll(allEntities);
        assertThat(gone).as("listed but no longer an entity: delete its line").isEmpty();
    }

    @Test
    @DisplayName("every event category has a row source, backend labels and a portal label and icon")
    void everyCategoryIsWired() throws IOException {
        Set<String> categories = new LinkedHashSet<>(EVENTS.values());
        Set<String> shared = sharedCategories();
        assertThat(shared).as("PatientServiceImpl's shared categories")
            .contains("ENCOUNTER", "PRESCRIPTION", "LAB_RESULT", "ALLERGY", "IMAGING", "PROCEDURE");

        List<String> noSource = new ArrayList<>();
        for (String category : categories) {
            if (!VisitTimelineRows.CATEGORIES.contains(category) && !shared.contains(category)) {
                noSource.add(category);
            }
        }
        assertThat(noSource).as("categories no source writes").isEmpty();
        Set<String> emitted = new TreeSet<>(VisitTimelineRows.CATEGORIES);
        emitted.addAll(shared);
        Set<String> orphan = new TreeSet<>(emitted);
        orphan.removeAll(categories);
        assertThat(orphan).as("categories written for no registered entity").isEmpty();

        Map<String, List<String>> missing = new TreeMap<>();
        for (String lang : List.of("en", "fr", "es")) {
            JsonNode node = new ObjectMapper().readTree(PORTAL.resolve("assets/i18n/" + lang + ".json").toFile())
                .path("PORTAL").path("ENUM").path("TIMELINE_CATEGORY");
            Properties bundle = bundle(lang);
            for (String category : emitted) {
                if (node.path(category).asText("").isBlank()) {
                    missing.computeIfAbsent(lang + ".json PORTAL.ENUM.TIMELINE_CATEGORY", k -> new ArrayList<>()).add(category);
                }
                if (bundle.getProperty("timeline.category." + category, "").isBlank()) {
                    missing.computeIfAbsent("messages-patient_" + lang + " timeline.category", k -> new ArrayList<>()).add(category);
                }
            }
        }
        String chart = Files.readString(PORTAL.resolve("app/patients/patient-chart/patient-chart.component.ts"),
            StandardCharsets.UTF_8);
        for (String category : emitted) {
            if (!chart.contains("case '" + category + "':")) {
                missing.computeIfAbsent("patient-chart.component.ts timelineIcon", k -> new ArrayList<>()).add(category);
            }
        }
        assertThat(missing).as("chronology categories without a label or an icon (QA D959)" + SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("the scan sees the D959 shape: an entity with a patient and no decision")
    void catchesTheOldShape() {
        String transfusion = """
            @Entity
            public class TransfusionRequest extends BaseEntity {
                @ManyToOne(fetch = FetchType.LAZY, optional = false)
                private Patient patient;
            }
            """;
        assertThat(PATIENT_FIELD.matcher(transfusion).find()).isTrue();
        assertThat(PATIENT_FIELD.matcher("@Entity class Bed { private Ward ward; }").find()).isFalse();
    }

    /** The categories PatientServiceImpl writes itself (its CATEGORY_* constants used in .category(...)). */
    private static Set<String> sharedCategories() throws IOException {
        String source = SourceScan.stripComments(Files.readString(PATIENT_SERVICE, StandardCharsets.UTF_8));
        Map<String, String> constants = new TreeMap<>();
        Matcher c = Pattern.compile("String\\s+(CATEGORY_\\w+)\\s*=\\s*\"(\\w+)\"").matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        Set<String> used = new TreeSet<>();
        Matcher u = Pattern.compile("\\.category\\((CATEGORY_\\w+)\\)").matcher(source);
        while (u.find()) {
            if (constants.containsKey(u.group(1))) {
                used.add(constants.get(u.group(1)));
            }
        }
        return used;
    }

    private static Properties bundle(String lang) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(BUNDLES.resolve("messages-patient_" + lang + ".properties"),
                StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
