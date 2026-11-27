package com.example.hms.architecture;

import com.example.hms.service.recordaccess.RestrictedVisitScope;
import com.example.hms.service.recordaccess.SensitivityClassifier;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D4261: content tied to a restricted visit follows the visit's
 * restriction in every shared view.
 *
 * <p>A visit another hospital restricted is withheld from a read at this
 * hospital (decision D3). Most of what a visit produces carries no category
 * of its own, so each record is judged by the visit it was recorded in:
 * {@code RecordAccessPolicy.withheldVisits} names the visits,
 * {@link RestrictedVisitScope} judges the rows. The class is closed by four
 * rules over the source, so a new record type or a new shared read fails here
 * until it is covered:
 * <ol>
 *   <li><b>the record types</b>: every entity that hangs off a visit (a link
 *       to the visit, or to a prescription, a lab order, a lab result or an
 *       imaging order) is classified: it follows its visit through the scope,
 *       carries its own category, or is never read in a shared view, with the
 *       reason;</li>
 *   <li><b>the shared reads</b>: every class that widens a read to the
 *       hospitals the policy opens ({@code readableHospitalIds}) either
 *       applies the scope or is listed with what it reads and why that names
 *       no visit;</li>
 *   <li><b>the gate</b>: every class that opens a chart
 *       ({@code PatientChartAccess.require}) or widens a read, and holds the
 *       repository of a record that follows its visit, applies the scope or is
 *       listed as reading one hospital only;</li>
 *   <li><b>the reads, method by method</b>: every call of a repository read
 *       that takes a patient and is not held to one hospital (a set of
 *       hospitals, or no hospital at all), on a record that follows its
 *       visit, is made in a method that applies the scope to it, or is listed
 *       by method with why no reader of another hospital gets the rows. The
 *       reads are found from the repositories' own declarations, so a class
 *       that neither widens a read nor opens the chart gate is seen too, and
 *       a class that applies the scope in one method is not excused in
 *       another.</li>
 * </ol>
 */
@DisplayName("D4261: every shared read withholds what a restricted visit produced")
class RestrictedVisitCoverageGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** How a record that hangs off a visit is kept from a reader of another hospital. */
    private enum Rule {
        /** Judged by {@code RestrictedVisitScope.admits(Type)} in every shared read. */
        FOLLOWS_ITS_VISIT,
        /** Carries its own category, else its visit's: {@code SensitivityClassifier.effectiveCategory(Type)}. */
        OWN_CATEGORY,
        /** Never read in a shared view; the reason says where it is read. */
        NOT_SHARED
    }

    private record Kind(Rule rule, String why) {
        static Kind follows(String why) {
            return new Kind(Rule.FOLLOWS_ITS_VISIT, why);
        }

        static Kind ownCategory(String why) {
            return new Kind(Rule.OWN_CATEGORY, why);
        }

        static Kind notShared(String why) {
            return new Kind(Rule.NOT_SHARED, why);
        }
    }

    /** What marks an entity as hanging off a visit: a link to it, or to a record that itself follows one. */
    private static final Pattern VISIT_LINK = Pattern.compile(
        "private\\s+(?:Encounter|Prescription|LabOrder|LabResult|ImagingOrder)\\s+\\w+\\s*;"
            + "|name\\s*=\\s*\"(?:encounter_id|source_encounter_id)\"");

    /** Every record type that hangs off a visit. Adding one means deciding how it is kept from another hospital. */
    private static final Map<String, Kind> VISIT_RECORDS = Map.ofEntries(
        Map.entry("Prescription", Kind.follows("the visit it was written in")),
        Map.entry("PharmacyFill", Kind.follows("the prescription it fills")),
        Map.entry("LabOrder", Kind.follows("the visit it was placed in")),
        Map.entry("LabResult", Kind.follows("its order")),
        Map.entry("ImagingOrder", Kind.follows("the visit it was placed in (encounterId)")),
        Map.entry("ImagingReport", Kind.follows("its order")),
        Map.entry("ProcedureOrder", Kind.follows("the visit it was placed in")),
        Map.entry("DischargeSummary", Kind.follows("the visit it closes")),
        Map.entry("PatientImmunization", Kind.follows("the visit it was given in, when it names one")),
        Map.entry("ObgynReferral", Kind.follows("the visit it was written in, when it names one")),
        Map.entry("Consultation", Kind.ownCategory("its own tag, else its visit's")),
        Map.entry("PatientProblem", Kind.ownCategory("its own tag, else the visit it was recorded in")),
        Map.entry("EncounterNote", Kind.notShared("read only through its visit: the chart and the snapshot load the"
            + " notes of the visits they already admitted")),
        Map.entry("EncounterNoteHistory", Kind.notShared("the edit history of a note, read through the visit at its"
            + " own hospital")),
        Map.entry("EncounterHistory", Kind.notShared("the change log of a visit, read through the visit at its own"
            + " hospital")),
        Map.entry("EncounterTreatment", Kind.notShared("read through its visit at the visit's own hospital")),
        Map.entry("BillingInvoice", Kind.notShared("billing is read at the hospital that issued the invoice")),
        Map.entry("DeathRecord", Kind.notShared("stays with the hospital that wrote it (MortalityServiceImpl)")),
        Map.entry("ImagingReportStatusHistory", Kind.notShared("the workflow log of a report, read at the imaging"
            + " department of the order's hospital")),
        Map.entry("ImagingStudyImage", Kind.notShared("served through its order, which is read at its own hospital"
            + " or through the scope")),
        Map.entry("InBasketItem", Kind.notShared("a message to one recipient, read by that recipient")),
        Map.entry("InstrumentOutbox", Kind.notShared("the analyser queue of the laboratory that works the order")),
        Map.entry("LabSpecimen", Kind.notShared("the laboratory's own work on an order it handles")),
        Map.entry("MicroCultureResult", Kind.notShared("read at one hospital (findForPatient(patient, hospital))")),
        Map.entry("MedicationAdministrationRecord", Kind.notShared("the ward's administration record, read at the"
            + " hospital of the stay")),
        Map.entry("PatientTreatmentConsent", Kind.notShared("a consent form, read at the hospital that holds it")),
        Map.entry("Dispense", Kind.notShared("the pharmacy's own dispensing, read at the dispensing hospital")),
        Map.entry("PrescriptionQueueClaim", Kind.notShared("the pharmacy work queue of one hospital")),
        Map.entry("PrescriptionRoutingDecision", Kind.notShared("the pharmacy routing of one hospital")),
        Map.entry("PrescriptionTransmission", Kind.notShared("the sending log of a prescription, read with the"
            + " prescription at its own hospital")),
        Map.entry("RefillRequest", Kind.notShared("a renewal request, read at the prescription's own hospital and by"
            + " the patient")),
        Map.entry("PatientRecall", Kind.notShared("the recall list of one hospital")),
        Map.entry("TransfusionRequest", Kind.notShared("read at one hospital (findByPatient_IdAndHospital_Id)")),
        Map.entry("TreatmentPlan", Kind.notShared("read at the hospital that wrote the plan")));

    /**
     * The repository of each record that follows its visit. A vital sign names no visit: it follows the day and the
     * hospital of one ({@code RestrictedVisitScope.admits(PatientVitalSign)}), so its repository is held to the same
     * rules.
     */
    private static final Map<String, String> SCOPED_REPOSITORIES = Map.ofEntries(
        Map.entry("PrescriptionRepository", "Prescription"),
        Map.entry("PharmacyFillRepository", "PharmacyFill"),
        Map.entry("LabOrderRepository", "LabOrder"),
        Map.entry("LabResultRepository", "LabResult"),
        Map.entry("ImagingOrderRepository", "ImagingOrder"),
        Map.entry("ImagingReportRepository", "ImagingReport"),
        Map.entry("ProcedureOrderRepository", "ProcedureOrder"),
        Map.entry("DischargeSummaryRepository", "DischargeSummary"),
        Map.entry("ImmunizationRepository", "PatientImmunization"),
        Map.entry("ObgynReferralRepository", "ObgynReferral"),
        Map.entry("PatientVitalSignRepository", "PatientVitalSign"));

    /** A class that widens a read to the readable hospitals and reads no record of a visit: what it reads. */
    private static final Map<String, String> SHARED_READS_OF_NO_VISIT_RECORD = Map.ofEntries(
        Map.entry("service/BirthPlanServiceImpl.java", "birth plans: a pregnancy record, no visit link"),
        Map.entry("service/HighRiskPregnancyCarePlanServiceImpl.java", "care plans: a pregnancy record, no visit link"),
        Map.entry("service/PatientChartUpdateServiceImpl.java", "chart updates: recorded on the chart, no visit link"),
        Map.entry("service/PatientInsuranceServiceImpl.java", "coverage held at another hospital: not a clinical row"),
        Map.entry("service/EncounterServiceImpl.java", "the visits themselves, by their own category (CrossHospitalRows)"),
        Map.entry("service/impl/AdmissionServiceImpl.java", "stays, by their own category (CrossHospitalRows)"),
        Map.entry("service/impl/ConsultationServiceImpl.java", "consultations, by their own category, else their"
            + " visit's (CrossHospitalRows)"),
        Map.entry("service/impl/NursingNoteServiceImpl.java", "nursing notes, by their own category (CrossHospitalRows)"),
        Map.entry("service/impl/PatientStoryboardServiceImpl.java", "problems, by their own category, else their"
            + " visit's (WithheldRows), and advance directives"),
        Map.entry("service/impl/AdvanceDirectiveServiceImpl.java", "advance directives: the patient's, no visit link"),
        Map.entry("service/impl/GeneralReferralServiceImpl.java", "referrals between hospitals: no visit link"),
        Map.entry("service/impl/LaborServiceImpl.java", "labour episodes and deliveries: no visit link"),
        Map.entry("service/impl/MaternalHistoryServiceImpl.java", "the pregnancy record: no visit link"),
        Map.entry("service/impl/NewbornAssessmentServiceImpl.java", "newborn assessments: no visit link"),
        Map.entry("service/impl/PostpartumCareServiceImpl.java", "postpartum observations: no visit link"),
        Map.entry("service/impl/UltrasoundServiceImpl.java", "ultrasound orders and reports: no visit link"),
        Map.entry("service/vitals/SevereHypertension.java", "whether the patient is pregnant or delivered recently:"
            + " labour, delivery and pregnancy records, no visit link"));

    /** A class behind the chart gate that holds a scoped repository and reads it at one hospital only. */
    private static final Map<String, String> ONE_HOSPITAL_READERS = Map.ofEntries(
        Map.entry("service/EncounterServiceImpl.java", "reads the records of ONE visit the caller already opened"
            + " (findByEncounter_Id, findById checked against it), and writes"),
        Map.entry("service/impl/GrowthChartServiceImpl.java", "findGrowthSeries(patient, hospital): the acting hospital"),
        Map.entry("service/impl/MicroCultureServiceImpl.java", "the laboratory's own orders and results, at one hospital"),
        Map.entry("service/impl/OrderReviewHoldServiceImpl.java", "the holds of one hospital, and the order each names"
            + " there"),
        Map.entry("service/impl/PatientStoryboardServiceImpl.java", "the latest weight, at the acting hospital"
            + " (findFirstByPatient_IdAndHospital_Id...)"),
        Map.entry("service/recordaccess/MinorResultReleaseServiceImpl.java", "a clinician's release of a result of"
            + " their own hospital"));

    /**
     * A method that calls a patient read not held to one hospital, without the scope: "file#method" and why no
     * reader of another hospital is handed a restricted visit's rows by it.
     */
    private static final Map<String, String> UNSCOPED_PATIENT_READS = Map.ofEntries(
        // One hospital, passed as a set.
        Map.entry("fhir/bulk/FhirBulkExportRunner.java#exportOnePatient",
            "the bulk export reads one hospital's own orders (Set.of(hospitalId))"),
        Map.entry("service/imaging/ImagingReadingContextService.java#priorStudiesOf",
            "the reading context of one hospital (List.of(hospitalId))"),
        Map.entry("service/timeline/VisitTimelineRows.java#obgynReferralRows",
            "the visit timeline of the acting hospital (Set.of(acting))"),
        // The FHIR servlet: bounded for every provider, once.
        Map.entry("fhir/provider/ImmunizationFhirResourceProvider.java#search",
            "FhirTenantBoundaryInterceptor filters every resource that leaves to the bound hospital"),
        Map.entry("fhir/provider/MedicationRequestFhirResourceProvider.java#search",
            "FhirTenantBoundaryInterceptor filters every resource that leaves to the bound hospital"),
        // The patient's own record, and the proxy's read of it (held to ProxyRecordFilter by its own guard).
        Map.entry("service/DischargeSummaryServiceImpl.java#getDischargeSummariesForPortalPatient",
            "the patient's own summaries, in the portal"),
        Map.entry("service/DischargeSummaryServiceImpl.java#enrichSparseExistingSummaries",
            "completes the patient's own summaries before the portal read; returns a count"),
        Map.entry("service/PrescriptionServiceImpl.java#getPrescriptionsForPortalPatient",
            "the patient's own prescriptions, in the portal"),
        Map.entry("service/imaging/PatientImagingPortalService.java#studiesOf",
            "the patient's own studies, in the portal (a proxy read is filtered there)"),
        Map.entry("service/impl/ImmunizationServiceImpl.java#portalImmunizations",
            "the patient's own doses, in the portal (the proxy read applies ProxyRecordFilter)"),
        Map.entry("service/impl/PatientMedicationServiceImpl.java#issuedFor",
            "the patient's own medicines, in the portal (the proxy read applies ProxyRecordFilter)"),
        Map.entry("service/impl/PatientMedicationServiceImpl.java#countMedicationsForPatientPortal",
            "the count of the patient's own medicines, in the portal"),
        Map.entry("service/impl/PatientVitalSignServiceImpl.java#getRecentVitalsForPatientPortal",
            "the patient's own vitals, in the portal"),
        Map.entry("service/impl/PatientVitalSignServiceImpl.java#getVitalsPageForPatientPortal",
            "the patient's own vitals, in the portal"),
        Map.entry("service/impl/PatientVitalSignServiceImpl.java#getRecentVitalsForProxy",
            "the proxy's read, filtered by ProxyRecordFilter.admits(vital, sensitiveVisits)"),
        // No acting hospital: a verified super-admin in global view, who measures against no hospital.
        Map.entry("cdshooks/bpa/BpaRuleEngine.java#loadActivePrescriptions",
            "patient-wide only with no hospital (global view); with one, that hospital's orders"),
        Map.entry("cdshooks/rules/CdsRuleEngine.java#loadActivePrescriptions",
            "patient-wide only with no hospital (global view); with one, that hospital's orders"),
        Map.entry("cdshooks/rules/CdsRuleEngine.java#loadLatestWeighing",
            "patient-wide only with no hospital (global view); with one, that hospital's weighing"),
        Map.entry("service/impl/PatientStoryboardServiceImpl.java#latestWeighing",
            "patient-wide only with no hospital (global view); with one, that hospital's weighing"),
        Map.entry("service/impl/PatientVitalSignServiceImpl.java#getLatestSnapshot",
            "patient-wide only with no hospital (global view); with one, that hospital's latest row"),
        Map.entry("service/LabResultServiceImpl.java#readLabResultsPage",
            "findAllForReader runs only with no hospital (global view); a hospital reads what it handles"),
        // Writes: nothing is handed to a reader.
        Map.entry("service/impl/ImmunizationServiceImpl.java#refuseDuplicateDose",
            "the duplicate check of a write: a dose is one event in the patient's life; answers a refusal, no row"),
        Map.entry("service/impl/OrderReviewHoldServiceImpl.java#snapshot",
            "locks the patient's open orders to hold them at a death or a transfer: a write, no row returned"),
        Map.entry("service/impl/OrderReviewHoldServiceImpl.java#snapshotAtDeath",
            "locks the patient's open orders to hold them at a death: a write, no row returned"));

    private static final Pattern WIDENS = Pattern.compile("\\brecordAccessPolicy\\s*\\.\\s*readableHospitalIds\\s*\\(");
    private static final Pattern OPENS_A_CHART = Pattern.compile("\\bpatientChartAccess\\s*\\.\\s*require\\s*\\(");
    private static final Pattern APPLIES_THE_SCOPE = Pattern.compile(
        "RestrictedVisitScope\\s*\\.\\s*of\\s*\\(\\s*recordAccessPolicy\\s*\\.\\s*withheldVisits\\s*\\(");
    /** The scope at work in a method: built there, handed to it, or the timeline's own form of the same rule. */
    private static final Pattern SCOPED_HERE = Pattern.compile(
        "\\bRestrictedVisitScope\\b|\\brestricted\\b|\\bwithheld\\s*\\.\\s*admit\\s*\\(");
    private static final Pattern FIELD = Pattern.compile("(?:[\\w.]+\\.)?(\\w+Repository)\\s+(\\w+)\\s*[;,)]");
    private static final Pattern CALL = Pattern.compile("\\b(\\w+)\\s*\\.\\s*(\\w+)\\s*\\(");
    /** A repository read, by the first word of its name. */
    private static final Pattern READ_NAME = Pattern.compile("^(?:find|count|exists|search|lock|get)\\w*");
    private static final Pattern DECLARED = Pattern.compile("\\b(\\w+)\\s*\\(");
    private static final Pattern NAMES_A_PATIENT = Pattern.compile("Patient");
    private static final Pattern PATIENT_PARAMETER = Pattern.compile("\\bUUID\\s+patientId\\b");
    private static final Pattern SEVERAL_HOSPITALS = Pattern.compile("Hospital_IdIn|HospitalIdIn");
    private static final Pattern SEVERAL_HOSPITALS_PARAMETER = Pattern.compile("Collection<UUID>\\s+\\w*[hH]ospital");
    private static final Pattern ONE_HOSPITAL = Pattern.compile("Hospital_Id(?!In)|HospitalId(?!In)");
    private static final Pattern ONE_HOSPITAL_PARAMETER = Pattern.compile("\\bUUID\\s+\\w*[hH]ospitalId\\b");
    /** A class member's declaration, at member indentation: anything but a statement, then a name and its "(". */
    private static final Pattern MEMBER = Pattern.compile("\\n {4}(?! )([^\\n(;={}]*?)\\b(\\w+)\\s*\\(");
    private static final Set<String> NOT_A_DECLARATION = Set.of("return", "new", "else", "throw");

    /** A method of a source file: its name and where its body starts and ends. */
    private record Member(String name, int open, int close) {
        boolean holds(int index) {
            return open < index && index < close;
        }
    }

    @Test
    @DisplayName("rule 1: every record type that hangs off a visit is classified, and the classification holds")
    void everyRecordTypeThatHangsOffAVisitIsClassified() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Path file : sources("model")) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains("@Entity") && VISIT_LINK.matcher(code).find()) {
                found.add(file.getFileName().toString().replace(".java", ""));
            }
        }
        // The visit itself is not a record of a visit.
        found.remove("Encounter");
        assertThat(found).as("the scan must find the known records of a visit, or it scans nothing")
            .contains("Prescription", "LabOrder", "LabResult", "ImagingOrder", "PharmacyFill");

        List<String> problems = new ArrayList<>();
        for (String entity : found) {
            if (!VISIT_RECORDS.containsKey(entity)) {
                problems.add(entity + " hangs off a visit and is not classified: add RestrictedVisitScope.admits("
                    + entity + ") and apply it in every shared read (FOLLOWS_ITS_VISIT), or list it in VISIT_RECORDS"
                    + " with why another hospital never reads it");
            }
        }
        for (Map.Entry<String, Kind> entry : VISIT_RECORDS.entrySet()) {
            String entity = entry.getKey();
            if (!found.contains(entity)) {
                problems.add(entity + " is classified and no longer hangs off a visit: remove the entry");
            } else if (entry.getValue().rule() == Rule.FOLLOWS_ITS_VISIT && !declares(RestrictedVisitScope.class,
                    "admits", entity)) {
                problems.add(entity + " is said to follow its visit, and RestrictedVisitScope has no admits("
                    + entity + ")");
            } else if (entry.getValue().rule() == Rule.OWN_CATEGORY && !declares(SensitivityClassifier.class,
                    "effectiveCategory", entity)) {
                problems.add(entity + " is said to carry its own category, and SensitivityClassifier has no"
                    + " effectiveCategory(" + entity + ")");
            }
        }
        assertThat(problems).as("records of a visit").isEmpty();
        for (String entity : SCOPED_REPOSITORIES.values()) {
            assertThat(declares(RestrictedVisitScope.class, "admits", entity))
                .as("RestrictedVisitScope.admits(" + entity + ")").isTrue();
        }
    }

    @Test
    @DisplayName("rule 2: every read widened to the readable hospitals applies the scope, or reads no record of a visit")
    void everySharedReadIsClassified() throws IOException {
        Map<String, String> code = mainSources();
        Set<String> widening = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> source : code.entrySet()) {
            if (!WIDENS.matcher(source.getValue()).find()) {
                continue;
            }
            String file = source.getKey();
            widening.add(file);
            boolean scoped = APPLIES_THE_SCOPE.matcher(source.getValue()).find();
            boolean listed = SHARED_READS_OF_NO_VISIT_RECORD.containsKey(file);
            if (!scoped && !listed) {
                problems.add(file + " reads across the readable hospitals without RestrictedVisitScope: apply it to"
                    + " every record of a visit it reads, or list it in SHARED_READS_OF_NO_VISIT_RECORD with what it"
                    + " reads");
            }
            if (scoped && listed) {
                problems.add(file + " applies the scope and is listed as reading no record of a visit: remove the entry");
            }
        }
        for (String listed : SHARED_READS_OF_NO_VISIT_RECORD.keySet()) {
            if (!widening.contains(listed)) {
                problems.add(listed + " is listed and no longer reads across the readable hospitals: remove the entry");
            }
        }
        assertThat(widening).as("the scan must find the known shared reads, or it scans nothing")
            .contains("service/PrescriptionServiceImpl.java", "service/impl/ChartReviewServiceImpl.java",
                "service/PatientSnapshotServiceImpl.java", "fhir/everything/PatientEverythingService.java",
                "service/impl/PatientMedicationServiceImpl.java");
        assertThat(problems).as("shared reads").isEmpty();
    }

    @Test
    @DisplayName("rule 3: a class behind the chart gate that holds the repository of a visit's record applies the scope")
    void everyGatedReaderOfAVisitRecordAppliesTheScope() throws IOException {
        Map<String, String> code = mainSources();
        Set<String> holders = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> source : code.entrySet()) {
            String text = source.getValue();
            if (!WIDENS.matcher(text).find() && !OPENS_A_CHART.matcher(text).find()) {
                continue;
            }
            Set<String> held = new TreeSet<>();
            for (String repository : SCOPED_REPOSITORIES.keySet()) {
                if (Pattern.compile("\\b" + repository + "\\b").matcher(text).find()) {
                    held.add(repository);
                }
            }
            if (held.isEmpty()) {
                continue;
            }
            String file = source.getKey();
            holders.add(file);
            boolean scoped = APPLIES_THE_SCOPE.matcher(text).find();
            boolean listed = ONE_HOSPITAL_READERS.containsKey(file);
            if (!scoped && !listed) {
                problems.add(file + " opens a chart or widens a read and holds " + held + " without"
                    + " RestrictedVisitScope: apply it, or list the class in ONE_HOSPITAL_READERS with the single"
                    + " hospital it reads at");
            }
            if (scoped && listed) {
                problems.add(file + " applies the scope and is listed as a one-hospital reader: remove the entry");
            }
        }
        for (String listed : ONE_HOSPITAL_READERS.keySet()) {
            if (!holders.contains(listed)) {
                problems.add(listed + " is listed as a one-hospital reader and no longer holds such a repository"
                    + " behind the gate: remove the entry");
            }
        }
        assertThat(problems).as("gated readers of a visit's records").isEmpty();
    }

    @Test
    @DisplayName("rule 4: every patient read not held to one hospital is scoped in the method that makes it")
    void everyPatientReadIsScopedInItsMethod() throws IOException {
        Map<String, Set<String>> patientReads = patientReadsNotHeldToOneHospital();
        assertThat(patientReads.get("PrescriptionRepository")).as("the scan must find the known reads")
            .contains("findByPatient_Id", "findByPatient_IdAndHospital_IdIn", "findSentAcrossHospitals");
        assertThat(patientReads.get("ImmunizationRepository"))
            .contains("findOverdueImmunizations", "findUpcomingDueDates", "findByPatient_IdAndActiveTrue");
        assertThat(patientReads.get("PatientVitalSignRepository"))
            .contains("findFirstByPatient_IdOrderByRecordedAtDesc")
            .doesNotContain("findFirstByPatient_IdAndHospital_IdOrderByRecordedAtDesc");

        Set<String> unscoped = new TreeSet<>();
        Set<String> scopedCallers = new TreeSet<>();
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, String> source : mainSources().entrySet()) {
            String file = source.getKey();
            if (file.startsWith("repository/")) {
                continue;
            }
            String text = source.getValue();
            Map<String, String> fields = new LinkedHashMap<>();
            Matcher field = FIELD.matcher(text);
            while (field.find()) {
                if (patientReads.containsKey(field.group(1))) {
                    fields.put(field.group(2), field.group(1));
                }
            }
            if (fields.isEmpty()) {
                continue;
            }
            List<Member> members = membersOf(text);
            Set<String> scopingHelpers = new TreeSet<>();
            for (Member member : members) {
                if (APPLIES_THE_SCOPE.matcher(text.substring(member.open(), member.close())).find()) {
                    scopingHelpers.add(member.name());
                }
            }
            Matcher call = CALL.matcher(text);
            while (call.find()) {
                String repository = fields.get(call.group(1));
                if (repository == null || !patientReads.get(repository).contains(call.group(2))) {
                    continue;
                }
                int at = call.start();
                Member owner = members.stream().filter(m -> m.holds(at)).findFirst().orElse(null);
                String key = file + "#" + (owner == null ? "?" : owner.name());
                if (owner != null && (SCOPED_HERE.matcher(text.substring(owner.open(), owner.close())).find()
                        || handedToAScopingHelper(text.substring(owner.open(), at), scopingHelpers))) {
                    scopedCallers.add(key);
                    continue;
                }
                unscoped.add(key);
                if (!UNSCOPED_PATIENT_READS.containsKey(key)) {
                    problems.add(key + " calls " + repository + "." + call.group(2) + ", a patient read not held to"
                        + " one hospital, without RestrictedVisitScope in that method: apply the scope there (or pass"
                        + " the rows straight to a method of the class that does), read at one hospital, or list the"
                        + " method in UNSCOPED_PATIENT_READS with why no reader of another hospital gets the rows");
                }
            }
        }
        for (String listed : UNSCOPED_PATIENT_READS.keySet()) {
            if (!unscoped.contains(listed)) {
                problems.add(listed + " is listed and no longer makes an unscoped patient read: remove the entry");
            }
        }
        assertThat(problems).as("patient reads of a visit's records").isEmpty();
        assertThat(scopedCallers).as("the scan must find the known scoped methods, or it scans nothing")
            .contains("service/PrescriptionServiceImpl.java#sentRows",
                "service/impl/PatientLabResultServiceImpl.java#fetchRows",
                "service/impl/ImmunizationServiceImpl.java#getOverdueImmunizations",
                "service/impl/ImmunizationServiceImpl.java#getUpcomingImmunizations",
                "service/impl/ChartReviewServiceImpl.java#loadMedications");
    }

    /**
     * For each scoped repository, the reads that take a patient and are not held to one hospital: read from the
     * repository's own declarations, by name and by parameter, so a {@code @Query} finder counts as a derived one.
     */
    private static Map<String, Set<String>> patientReadsNotHeldToOneHospital() throws IOException {
        Map<String, Set<String>> reads = new LinkedHashMap<>();
        for (Path file : sources("repository")) {
            String repository = file.getFileName().toString().replace(".java", "");
            if (!SCOPED_REPOSITORIES.containsKey(repository)) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Set<String> names = new TreeSet<>();
            Matcher declared = DECLARED.matcher(code);
            while (declared.find()) {
                String name = declared.group(1);
                int close = SourceScan.closingParen(code, declared.end() - 1);
                if (close < 0 || !READ_NAME.matcher(name).matches()) {
                    continue;
                }
                String after = code.substring(close + 1, Math.min(code.length(), close + 40)).strip();
                if (!after.startsWith(";") && !after.startsWith("{")) {
                    continue;
                }
                String parameters = code.substring(declared.end(), close);
                boolean patient = NAMES_A_PATIENT.matcher(name).find() || PATIENT_PARAMETER.matcher(parameters).find();
                boolean several = SEVERAL_HOSPITALS.matcher(name).find()
                    || SEVERAL_HOSPITALS_PARAMETER.matcher(parameters).find();
                boolean one = ONE_HOSPITAL.matcher(name).find() || ONE_HOSPITAL_PARAMETER.matcher(parameters).find();
                if (patient && (several || !one)) {
                    names.add(name);
                }
            }
            reads.put(repository, names);
        }
        assertThat(reads.keySet()).as("every scoped repository was read")
            .containsExactlyInAnyOrderElementsOf(SCOPED_REPOSITORIES.keySet());
        return reads;
    }

    /** The methods of a source file: a declaration at member indentation, with a body. */
    private static List<Member> membersOf(String code) {
        List<Member> members = new ArrayList<>();
        Matcher member = MEMBER.matcher(code);
        while (member.find()) {
            String[] words = member.group(1).strip().split("\\s+");
            if (words[0].isEmpty() || NOT_A_DECLARATION.contains(words[0]) || words[0].startsWith("@")) {
                continue;
            }
            int close = SourceScan.closingParen(code, member.end() - 1);
            if (close < 0) {
                continue;
            }
            int open = code.indexOf('{', close);
            int semicolon = code.indexOf(';', close);
            if (open < 0 || (semicolon >= 0 && semicolon < open)) {
                continue;
            }
            int end = SourceScan.closingBrace(code, open);
            if (end > open) {
                members.add(new Member(member.group(2), open, end));
            }
        }
        return members;
    }

    /** The read is an argument of a call to a method of the class that applies the scope: {@code helper(.. read(..}. */
    private static boolean handedToAScopingHelper(String bodyBeforeTheRead, Set<String> scopingHelpers) {
        int statement = bodyBeforeTheRead.lastIndexOf(';') + 1;
        String sameStatement = bodyBeforeTheRead.substring(statement);
        for (String helper : scopingHelpers) {
            if (Pattern.compile("\\b" + helper + "\\s*\\(").matcher(sameStatement).find()) {
                return true;
            }
        }
        return false;
    }

    /** Whether {@code owner} declares {@code name(Type)} for the entity of that simple name. */
    private static boolean declares(Class<?> owner, String name, String entitySimpleName) {
        for (Method method : owner.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].getSimpleName().equals(entitySimpleName)) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> code = new LinkedHashMap<>();
        for (Path file : sources("")) {
            code.put(MAIN.relativize(file).toString().replace('\\', '/'),
                SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
        }
        return code;
    }

    private static List<Path> sources(String under) throws IOException {
        try (Stream<Path> files = Files.walk(MAIN.resolve(under))) {
            return files.filter(f -> f.toString().endsWith(".java")).sorted().toList();
        }
    }
}
