package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1148 / D1150 / D1151 / D1158 (round 21): the server accepted clinical
 * writes with nothing in them - a blank ultrasound report read "Normal",
 * blank postpartum observations counted as the 15-minute checks, an empty
 * triage, intake, encounter note and social-history version, a one-click
 * ACTIVE DNR. This reads every body-carrying write handler of the
 * controllers that record clinical content, and each one must be, by
 * construction:
 * <ul>
 *   <li><b>refused empty at the edge</b>: an all-null instance of its body
 *       fails bean validation on a content property (not an id, a
 *       reference or a date of record) - checked here with the real
 *       validator; or</li>
 *   <li><b>checked by its service</b>: listed in {@link #CHECKED} with the
 *       service file and method, whose body calls
 *       {@code ClinicalContent.require…} - checked here in the source; or</li>
 *   <li><b>not a record of findings</b>: listed in {@link #NOT_FINDINGS}
 *       with why (a workflow action, an assignment, a reason-only body).</li>
 * </ul>
 * A new handler on these controllers fails until it is one of the three;
 * a listed handler that disappears fails until its line goes.
 */
class EmptyClinicalWriteGuardTest {

    /** The constraints that make a property required (NotInClinicalFuture is a date rule, not one of them). */
    private static final Set<Class<? extends Annotation>> REQUIRED_CONSTRAINTS = Set.of(
        jakarta.validation.constraints.NotNull.class, jakarta.validation.constraints.NotBlank.class,
        jakarta.validation.constraints.NotEmpty.class, jakarta.validation.constraints.AssertTrue.class);

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    /** The controllers whose writes record clinical content. */
    private static final List<String> CLINICAL_CONTROLLERS = List.of(
        "EncounterController", "UltrasoundController", "PostpartumCareController", "NewbornAssessmentController",
        "AdvanceDirectiveController", "MedicalHistoryController", "TreatmentConsentController",
        "PatientVitalSignController", "NursingNoteController", "IntakeOutputController", "LaborController",
        "MaternalHistoryController", "DischargeSummaryController", "ConsultationController",
        "HighRiskPregnancyCarePlanController", "BirthPlanController", "ImagingResultController",
        "MicroCultureController", "NurseTaskController", "TreatmentPlanController", "MortalityController",
        "PatientController");

    /** Properties that do not carry clinical content: references, dates of record, flags of the record itself. */
    private static final Pattern NOT_CONTENT = Pattern.compile(
        "(?i).*(id|ids|identifier|date|time|at|status|source|version|active|lateEntry|hospital|patient|encounter"
            + "|staff|assignment|author|recordedBy|type|category|template|method)$");

    /** "Controller.method" -> "service/File.java#method": the service method that calls ClinicalContent. */
    private static final Map<String, String> CHECKED = new LinkedHashMap<>();

    static {
        CHECKED.put("EncounterController.submitTriage", "service/EncounterServiceImpl.java#submitTriage");
        CHECKED.put("EncounterController.submitNursingIntake", "service/EncounterServiceImpl.java#submitNursingIntake");
        CHECKED.put("EncounterController.upsertEncounterNote", "service/EncounterServiceImpl.java#upsertEncounterNote");
        CHECKED.put("UltrasoundController.createOrUpdateReport", "service/impl/UltrasoundServiceImpl.java#createOrUpdateReport");
        CHECKED.put("PostpartumCareController.recordObservation", "service/impl/PostpartumCareServiceImpl.java#recordObservation");
        CHECKED.put("NewbornAssessmentController.recordAssessment", "service/impl/NewbornAssessmentServiceImpl.java#recordAssessment");
        CHECKED.put("AdvanceDirectiveController.create", "service/impl/AdvanceDirectiveServiceImpl.java#create");
        CHECKED.put("MedicalHistoryController.createSocialHistory", "service/impl/SocialHistoryServiceImpl.java#createSocialHistory");
        CHECKED.put("MedicalHistoryController.updateSocialHistory", "service/impl/SocialHistoryServiceImpl.java#updateSocialHistory");
        CHECKED.put("TreatmentConsentController.record", "service/impl/TreatmentConsentServiceImpl.java#record");
        CHECKED.put("LaborController.addEntry", "service/impl/LaborServiceImpl.java#addEntry");
        CHECKED.put("ImagingResultController.createReport", "service/impl/ImagingReportServiceImpl.java#createReport");
        CHECKED.put("NurseTaskController.createCareNote", "service/impl/NurseTaskServiceImpl.java#createCareNote");
        CHECKED.put("MaternalHistoryController.createMaternalHistory",
            "service/impl/MaternalHistoryServiceImpl.java#createMaternalHistory");
        CHECKED.put("HighRiskPregnancyCarePlanController.createPlan",
            "service/HighRiskPregnancyCarePlanServiceImpl.java#createPlan");
        // QA D1182 (round 22): the edit paths, held to the create's rule (the
        // four round-21 KNOWN_GAPS, drained to zero).
        CHECKED.put("ImagingResultController.updateReport", "service/impl/ImagingReportServiceImpl.java#updateReport");
        CHECKED.put("MaternalHistoryController.updateMaternalHistory",
            "service/impl/MaternalHistoryServiceImpl.java#updateMaternalHistory");
        CHECKED.put("HighRiskPregnancyCarePlanController.updatePlan",
            "service/HighRiskPregnancyCarePlanServiceImpl.java#updatePlan");
    }

    /**
     * "Controller.method" -> why it is not fixed in this PR: each a known site of
     * the D1151 class, standing debt in tasklist.md. The list only shrinks.
     */
    private static final Map<String, String> KNOWN_GAPS = new LinkedHashMap<>();

    static {
        // Round 22 (QA D1182): empty - every edit path is CHECKED or NOT_FINDINGS. Must stay empty.
    }

    @Test
    void noKnownGapIsLeft() {
        assertThat(KNOWN_GAPS).as("QA D1182: the edit paths are checked; a new gap is a defect, not a list entry")
            .isEmpty();
    }

    /** "Controller.method" -> why its body is not a record of clinical findings. */
    private static final Map<String, String> NOT_FINDINGS = new LinkedHashMap<>();

    static {
        NOT_FINDINGS.put("EncounterController.create", "opens a visit (patient, staff, type); its notes come later");
        NOT_FINDINGS.put("EncounterController.update", "edits the visit's administrative fields");
        NOT_FINDINGS.put("EncounterController.checkOut", "a workflow step that closes the visit");
        NOT_FINDINGS.put("EncounterController.closeUnseen", "a workflow step; its reason is @NotBlank (QA D788)");
        NOT_FINDINGS.put("EncounterController.issueMedicalCertificate", "a document issued from the visit, its own rules (QA D1093)");
        NOT_FINDINGS.put("EncounterController.addEncounterNoteAddendum", "an addendum; its text is required by its service (QA D831)");
        NOT_FINDINGS.put("UltrasoundController.createOrder", "an order, not findings");
        NOT_FINDINGS.put("UltrasoundController.updateOrder", "an order, not findings");
        NOT_FINDINGS.put("AdvanceDirectiveController.update", "a partial edit of a recorded directive; requireOrderDetails runs on the result");
        NOT_FINDINGS.put("NursingNoteController.appendAddendum", "an addendum to a recorded note");
        NOT_FINDINGS.put("LaborController.startEpisode", "opens the labour episode");
        NOT_FINDINGS.put("ConsultationController.updateConsultation", "edits the request of a consultation");
        NOT_FINDINGS.put("ConsultationController.scheduleConsultation", "a workflow step (a date)");
        NOT_FINDINGS.put("ConsultationController.declineConsultation", "a workflow step with its reason");
        NOT_FINDINGS.put("ConsultationController.cancelConsultation", "a cancellation; its reason is @NotBlank (QA D1098)");
        NOT_FINDINGS.put("ConsultationController.assignConsultation", "an assignment");
        NOT_FINDINGS.put("ConsultationController.reassignConsultation", "an assignment");
        NOT_FINDINGS.put("ImagingResultController.updateReportStatus", "a status move of a report");
        NOT_FINDINGS.put("NurseTaskController.verifyMedication", "the five-rights scan of a dose");
        NOT_FINDINGS.put("NurseTaskController.administerMedication", "a dose outcome; HELD/REFUSED need a reason (QA D1159)");
        NOT_FINDINGS.put("NurseTaskController.createHandoff", "a handoff between nurses");
        NOT_FINDINGS.put("NurseTaskController.createNursingTask", "a task, not findings");
        NOT_FINDINGS.put("NurseTaskController.completeNursingTask", "closes a task");
        NOT_FINDINGS.put("TreatmentPlanController.addReview", "a review action of another clinician");
        NOT_FINDINGS.put("BirthPlanController.providerReview", "a review action");
        NOT_FINDINGS.put("PatientController.createPatientByReceptionist", "registration, not clinical findings");
        NOT_FINDINGS.put("PatientController.updatePatient", "demographics");
        NOT_FINDINGS.put("PatientController.patchPatient", "demographics");
        NOT_FINDINGS.put("PatientController.setChartRestriction", "an access setting");
        NOT_FINDINGS.put("PatientController.getDoctorRecord", "a read carried in a POST body");
        NOT_FINDINGS.put("PatientController.getDoctorTimeline", "a read carried in a POST body");
        NOT_FINDINGS.put("MortalityController.amendDeathRecord", "an amendment of a recorded death, with its reason");
        NOT_FINDINGS.put("MicroCultureController.updateCulture",
            "a partial correction of an opened culture (QA D1182 review): a null keeps the stored value, so it"
                + " cannot blank the culture; its findings (growth, isolates) are required at finalize");
        NOT_FINDINGS.put("MicroCultureController.createCulture",
            "opens a culture for a lab order (@NotNull labOrderId); growth and isolates are recorded on it later");
        NOT_FINDINGS.put("PatientController.updatePatientDiagnosis",
            "a partial edit of a recorded diagnosis: null keeps the stored value, a sent name must not be blank");
    }

    @Test
    void everyClinicalWriteRefusesAnEmptyBody() throws Exception {
        List<String> handlers = handlers();
        assertThat(handlers).as("clinical write handlers found").hasSizeGreaterThan(60);

        List<String> unclassified = new ArrayList<>();
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            for (String entry : handlers) {
                String[] parts = entry.split("\\|");
                String handler = parts[0];
                if (CHECKED.containsKey(handler) || NOT_FINDINGS.containsKey(handler) || KNOWN_GAPS.containsKey(handler)) {
                    continue;
                }
                if (!refusedEmptyAtTheEdge(validator, Class.forName(parts[1]))) {
                    unclassified.add(handler + " (" + parts[1].substring(parts[1].lastIndexOf('.') + 1) + ")");
                }
            }
        }
        assertThat(unclassified)
            .as("a clinical write whose empty body is accepted (QA D1151 class): require content in its body"
                + " (@NotNull/@NotBlank on a finding), or call ClinicalContent.require… in its service and"
                + " list it in CHECKED%s", SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    void everyListedHandlerExistsAndEveryCheckIsInItsService() throws Exception {
        Set<String> found = new TreeSet<>();
        for (String entry : handlers()) {
            found.add(entry.split("\\|")[0]);
        }
        Set<String> listed = new TreeSet<>(CHECKED.keySet());
        listed.addAll(NOT_FINDINGS.keySet());
        listed.addAll(KNOWN_GAPS.keySet());
        listed.removeAll(found);
        assertThat(listed).as("listed handlers that no longer exist: delete their line").isEmpty();

        for (Map.Entry<String, String> e : CHECKED.entrySet()) {
            String[] target = e.getValue().split("#");
            String source = Files.readString(MAIN.resolve(target[0]), StandardCharsets.UTF_8);
            String body = methodBody(source, target[1]);
            assertThat(body).as(e.getKey() + " -> " + e.getValue() + " checks the content")
                .containsPattern("ClinicalContent\\.require|require\\w*(Findings|Content|Answer|OrderDetails)\\(");
        }
    }

    /** "Controller.method|body class" for every POST/PUT/PATCH with a @RequestBody on the clinical controllers. */
    private static List<String> handlers() throws ClassNotFoundException {
        List<String> out = new ArrayList<>();
        for (String name : CLINICAL_CONTROLLERS) {
            Class<?> type = Class.forName("com.example.hms.controller." + name);
            for (Method m : type.getDeclaredMethods()) {
                if (m.getAnnotation(PostMapping.class) == null && m.getAnnotation(PutMapping.class) == null
                        && m.getAnnotation(PatchMapping.class) == null) {
                    continue;
                }
                for (Parameter p : m.getParameters()) {
                    if (p.getAnnotation(RequestBody.class) != null) {
                        out.add(name + "." + m.getName() + "|" + p.getType().getName());
                    }
                }
            }
        }
        return out;
    }

    /** An all-null instance fails validation on at least one content property. */
    private static boolean refusedEmptyAtTheEdge(Validator validator, Class<?> body) {
        Object empty = emptyInstance(body);
        if (empty == null) {
            return false;
        }
        for (ConstraintViolation<Object> v : validator.validate(empty)) {
            String property = v.getPropertyPath().toString();
            Class<? extends Annotation> constraint = v.getConstraintDescriptor().getAnnotation().annotationType();
            boolean required = REQUIRED_CONSTRAINTS.contains(constraint);
            if (required && !property.isEmpty() && !NOT_CONTENT.matcher(property).matches()) {
                return true;
            }
        }
        return false;
    }

    private static Object emptyInstance(Class<?> body) {
        try {
            Constructor<?> constructor = body.getDeclaredConstructor();
            return constructor.newInstance();
        } catch (ReflectiveOperationException ex) {
            return null;
        }
    }

    private static String methodBody(String source, String method) {
        java.util.regex.Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\([^;{]*\\)\\s*(?:throws [\\w., ]+)?\\{")
            .matcher(source);
        StringBuilder bodies = new StringBuilder();
        while (m.find()) {
            int open = m.end() - 1;
            bodies.append(source, open, SourceScan.closingBrace(source, open) + 1);
        }
        return bodies.toString();
    }
}
