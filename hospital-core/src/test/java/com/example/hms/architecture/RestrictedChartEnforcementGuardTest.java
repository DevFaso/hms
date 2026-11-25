package com.example.hms.architecture;

import com.example.hms.security.chart.ChartKeys;
import com.example.hms.security.chart.RestrictedChartPolicy;
import com.example.hms.testsupport.ControllerMappingRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3863: a restricted chart (E8 #54) is enforced on every chart read, by
 * three central hooks: {@code RestrictedChartGuardInterceptor} refuses a GET
 * that names the chart ({@link ChartKeys}), {@code RestrictedChartResponseMasking}
 * refuses or masks the chart's records in any JSON answer by their
 * {@code patientId} / {@code patient.id}, and the FHIR boundary does the same
 * for FHIR. None of them is a list of paths, so these rules hold the
 * conventions they read true as controllers are added:
 * <ol>
 *   <li>a GET parameter that names a patient or one of their records is a
 *       key the interceptor reads;</li>
 *   <li>a GET that answers a file (bytes, a stream, a text) for a record id is
 *       keyed, so the interceptor can refuse it (the masking cannot read a
 *       PDF);</li>
 *   <li>a JSON answer that carries clinical content carries it on a record the
 *       masking can attribute to a patient;</li>
 *   <li>every endpoint that opts out says so with {@link RestrictedChartPolicy}
 *       and is recorded below, with its reason.</li>
 * </ol>
 * The way out of a rule is a {@link RestrictedChartPolicy} on the handler,
 * recorded in {@link #RECORDED_POLICIES}, or an entry in the rule's own list
 * with the reason it holds no chart.
 */
@DisplayName("D3863: restricted charts are enforced on every chart read")
class RestrictedChartEnforcementGuardTest {

    /** A parameter name that names a patient, a visit or a record of one. */
    private static final Pattern NAMES_A_CHART = Pattern.compile(
        "(?i).*(patient|encounter|visit|prescription|newborn|mother|infant|baby|admission|note).*");

    /** Rule 1: chart-looking parameters that name something else, and what. */
    private static final Map<String, String> NOT_A_CHART_KEY = Map.ofEntries(
        entry("AdmissionController#getOrderSetsByHospital#admissionType", "a kind of admission, filtering order sets"),
        entry("DispenseBillingDeferralController#list#prescriptionId",
            "a filter on the pharmacy's billing deferrals: money, read by the pharmacy, outside the restriction"),
        entry("PatientEducationController#getResourceById#id", "a library resource, nobody's record"),
        entry("SlotInventoryController#search#visitTypeId", "a kind of visit, filtering free slots"),
        entry("SlotInventoryController#searchPage#visitTypeId", "a kind of visit, filtering free slots"));

    /** Rule 2: file answers keyed by a record the interceptor does not resolve, and why they hold no chart. */
    private static final Map<String, String> FILE_NOT_CHART = Map.ofEntries(
        entry("CashSessionController#sessionPdf", "a till session: money"),
        entry("ChatController#downloadAttachment", "a staff message attachment, answered to the conversation's members"),
        entry("FhirBulkExportStatusController#download",
            "the hospital administrator's bulk export for an integration (system to system), not a chart read"),
        entry("FhirBulkExportStatusController#getStatus", "the status of that export job: counts, no record"),
        entry("HospitalClinicalRulesController#pack", "the hospital's clinical-rule sign-off pack: nobody's record"),
        entry("InvoiceRefundController#receipt", "a refund receipt: money"),
        entry("PrintLabelController#specimenLabel",
            "the specimen tube label (identity and accession), printed by whoever draws the sample"),
        entry("PrintLabelController#stockLotLabel", "a stock lot label: nobody's record"));

    /** Rule 3: answer types with a clinical-looking field and no patient on them, and why. */
    private static final Map<String, String> NOT_PATIENT_CONTENT = Map.ofEntries(
        // Catalogues, configuration, staff and platform records: about no patient.
        entry("AdmissionOrderSetResponseDTO", "an order-set template"),
        entry("AdtIntakeProviderConfigResponseDTO", "an ADT feed's defaults"),
        entry("BedResponseDTO", "a bed"),
        entry("BedBoardEntryDTO#notes", "a note about the bed; the occupant carries its patientId"),
        entry("BloodUnitResponseDTO", "a blood unit in stock"),
        entry("CashSessionDTO", "a till session: money"),
        entry("ComplianceAlertDTO", "an organisation's compliance alert"),
        entry("CredentialRenewalResponseDTO", "a clinician's credential"),
        entry("DrugInteractionDTO", "the drug-interaction catalogue"),
        entry("EducationResourceResponseDTO", "a library resource"),
        entry("HospitalRuleSignoffDTO", "a clinical-rule sign-off"),
        entry("InstrumentOutboxResponseDTO", "an instrument message envelope: the laboratory's (outside the restriction)"),
        entry("InventoryItemResponseDTO", "stock"),
        entry("LabInstrumentResponseDTO", "a laboratory instrument"),
        entry("LabInventoryItemResponseDTO", "laboratory stock"),
        entry("LabQcEventResponseDTO", "a quality-control run, no patient sample"),
        entry("LabReflexRuleResponseDTO", "a reflex-testing rule"),
        entry("LabTestDefinitionResponseDTO", "the test catalogue"),
        entry("LabTestReferenceRangeDTO", "the test catalogue"),
        entry("LabTestValidationStudyResponseDTO", "a method validation study, no patient"),
        entry("OnCallScheduleResponseDTO", "the on-call rota"),
        entry("OrganizationResponseDTO", "an organisation"),
        entry("PacsConnectionTestDTO", "a PACS connection test"),
        entry("PermissionMatrixSnapshotResponseDTO", "the permission matrix"),
        entry("PlatformReleaseWindowResponseDTO", "a platform release window"),
        entry("PlatformServiceMetadataDTO", "an integration descriptor"),
        entry("ProInstrumentViewDTO", "a questionnaire instrument's licence"),
        entry("ProviderResponseDTO", "a provider facility"),
        entry("ProviderVerificationHistoryEntryDTO", "a provider facility's verification"),
        entry("SessionTemplateResponseDTO", "a schedule template"),
        entry("StaffAvailabilityResponseDTO", "staff rota"),
        entry("StaffLeaveResponseDTO", "staff rota"),
        entry("StaffShiftResponseDTO", "staff rota"),
        entry("UserRecoveryContactDTO", "the caller's own account"),
        // Pharmacy stock and its legal registers: the pharmacy's work, outside the restriction (fulfilment roles).
        entry("ControlledBalance", "a controlled-drug stock balance"),
        entry("ControlledRegisterDrugDTO", "a controlled drug in the register"),
        entry("ControlledRegisterEntryDTO", "the controlled-drug register the pharmacy keeps by law"),
        entry("DeferredFill", "the pharmacy's daily reconciliation"),
        entry("Difference", "the pharmacy's daily reconciliation"),
        entry("LotBalance", "the pharmacy's daily reconciliation"),
        entry("StockLine", "the pharmacy's daily reconciliation"),
        entry("PharmacyRefundDTO", "a pharmacy refund: money"),
        entry("QuarantineLineDTO", "stock put in quarantine"),
        entry("ReturnableDispenseDTO", "stock returns, worked by the pharmacy"),
        entry("StockLotResponseDTO", "a stock lot"),
        // Answers to the caller's own clinical act, safety-critical at the point of care.
        entry("AllergyConflictDTO",
            "the allergy check on the order the caller is signing (a POST): prescribing safely needs it"),
        entry("EmergencyReleaseOptionsDTO",
            "emergency blood release for a request: withholding the antibody warning would endanger the patient"),
        entry("Entry", "the intake/output entry the caller just recorded (a POST); the summary GET is keyed"),
        entry("PriorDose", "the earlier dose shown when the caller continues a held order (a POST)"),
        entry("TriageSubmissionResponseDTO", "the triage the caller just submitted (a POST)"),
        entry("SignatureResponseDTO", "who signed a report and when: the signature audit, not the report"));

    /** Rule 4: every handler that opts out, by its mode. A new one is reviewed here. */
    private static final Map<String, RestrictedChartPolicy.Mode> RECORDED_POLICIES = Map.ofEntries(
        entry("AppointmentController#getAllAppointments", RestrictedChartPolicy.Mode.MASK),
        entry("AppointmentController#getAppointmentsByPatientId", RestrictedChartPolicy.Mode.MASK),
        entry("BedManagementController#getAvailableBeds", RestrictedChartPolicy.Mode.MASK),
        entry("PatientController#getAddressHistory", RestrictedChartPolicy.Mode.MASK),
        entry("PatientController#getPatientById", RestrictedChartPolicy.Mode.MASK),
        entry("PatientHospitalRegistrationController#getRegistrations", RestrictedChartPolicy.Mode.MASK),
        entry("PatientRecallController#list", RestrictedChartPolicy.Mode.MASK),
        entry("ReceptionController#getPatientSnapshot", RestrictedChartPolicy.Mode.MASK),
        entry("PrintLabelController#wristband", RestrictedChartPolicy.Mode.NONE),
        entry("BillingInvoiceController", RestrictedChartPolicy.Mode.NONE),
        entry("BreakGlassController", RestrictedChartPolicy.Mode.NONE),
        entry("EligibilityController", RestrictedChartPolicy.Mode.NONE),
        entry("EmpiController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientChannelPreferenceController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientConsentController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientDisclosureController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientGuarantorController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientInsuranceController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientPhotoController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientPrimaryCareController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientRecipientVerificationController", RestrictedChartPolicy.Mode.NONE),
        entry("PatientRoiController", RestrictedChartPolicy.Mode.NONE),
        entry("PharmacyClaimController", RestrictedChartPolicy.Mode.NONE),
        entry("PharmacyPaymentController", RestrictedChartPolicy.Mode.NONE),
        entry("PharmacySaleController", RestrictedChartPolicy.Mode.NONE),
        entry("RecordAccessController", RestrictedChartPolicy.Mode.NONE),
        entry("RoiRecordExportController", RestrictedChartPolicy.Mode.NONE),
        entry("TreatmentConsentController", RestrictedChartPolicy.Mode.NONE));

    private static final Pattern CLINICAL_FIELD = Pattern.compile(
        "(?i).*(chronic|condition|allerg|diagnos|vital|medication|prescri|labresult|narrative|organism|acuity"
            + "|soap|assessment|chiefcomplaint|problem|complaint|clinical|historysummary|careteamnotes|notes?"
            + "|followupinstruction|symptom|immuniz|procedure|bloodtype|gestation|bloodgroup|antibody|triage"
            + "|findings|impression|hospitalcourse|encountersummary|result).*");

    private record Handler(String name, String pattern, Set<RequestMethod> methods, HandlerMethod method) {
        boolean isGet() {
            return methods.isEmpty() || methods.contains(RequestMethod.GET);
        }

        boolean ownRecord() {
            return pattern.startsWith("/me/");
        }
    }

    private static List<Handler> handlers() {
        List<Handler> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> e
                : ControllerMappingRegistry.mapping().getHandlerMethods().entrySet()) {
            HandlerMethod h = e.getValue();
            String name = h.getBeanType().getSimpleName() + "#" + h.getMethod().getName();
            for (String pattern : e.getKey().getPathPatternsCondition().getPatternValues()) {
                out.add(new Handler(name, pattern, e.getKey().getMethodsCondition().getMethods(), h));
            }
        }
        assertThat(out).as("the mapping registry must be read").hasSizeGreaterThan(500);
        return out;
    }

    private static RestrictedChartPolicy policyOf(HandlerMethod method) {
        RestrictedChartPolicy own = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(),
            RestrictedChartPolicy.class);
        return own != null ? own
            : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), RestrictedChartPolicy.class);
    }

    private static String nameOf(MethodParameter parameter) {
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        RequestParam param = parameter.getParameterAnnotation(RequestParam.class);
        String declared = path != null ? (!path.value().isEmpty() ? path.value() : path.name())
            : param != null ? (!param.value().isEmpty() ? param.value() : param.name()) : "";
        return declared.isEmpty() ? parameter.getParameterName() : declared;
    }

    /** True when the interceptor resolves one of the handler's parameters to a patient. */
    private static boolean keyed(Handler handler) {
        for (MethodParameter parameter : handler.method().getMethodParameters()) {
            String name = nameOf(parameter);
            if (parameter.hasParameterAnnotation(PathVariable.class)
                && ChartKeys.ofPathVariable(handler.pattern(), name).isPresent()) {
                return true;
            }
            if (parameter.hasParameterAnnotation(RequestParam.class) && ChartKeys.PARAMETERS.contains(name)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("rule 1: a GET parameter that names a patient or a record of one is a key the guard reads")
    void chartParametersAreKeys() {
        Set<String> unread = new TreeSet<>();
        Set<String> used = new HashSet<>();
        for (Handler handler : handlers()) {
            if (!handler.isGet() || handler.ownRecord() || policyOf(handler.method()) != null) {
                continue;
            }
            for (MethodParameter parameter : handler.method().getMethodParameters()) {
                boolean path = parameter.hasParameterAnnotation(PathVariable.class);
                boolean param = parameter.hasParameterAnnotation(RequestParam.class);
                Class<?> type = parameter.getParameterType();
                if ((!path && !param) || !(type == UUID.class || type == String.class)) {
                    continue;
                }
                String name = nameOf(parameter);
                boolean read = path ? ChartKeys.ofPathVariable(handler.pattern(), name).isPresent()
                    : ChartKeys.PARAMETERS.contains(name);
                boolean chartish = NAMES_A_CHART.matcher(name).matches()
                    || ("id".equals(name) && NAMES_A_CHART.matcher(handler.pattern().split("/\\{")[0]).matches());
                String key = handler.name() + "#" + name;
                if (chartish && !read) {
                    if (NOT_A_CHART_KEY.containsKey(key)) {
                        used.add(key);
                    } else {
                        unread.add(key + "  " + handler.pattern());
                    }
                }
            }
        }
        assertThat(unread)
            .as("teach ChartKeys (and RestrictedChartGate#ownerOf) to resolve the parameter to its patient, mark the"
                + " handler @RestrictedChartPolicy with its reason, or record here what else it names")
            .isEmpty();
        assertThat(new TreeSet<>(NOT_A_CHART_KEY.keySet())).as("recorded and no longer met").isSubsetOf(used);
    }

    @Test
    @DisplayName("rule 2: a GET answering a file for a record id is keyed, so the guard can refuse it")
    void fileAnswersAreKeyed() {
        Set<String> unkeyed = new TreeSet<>();
        Set<String> used = new HashSet<>();
        for (Handler handler : handlers()) {
            if (!handler.isGet() || handler.ownRecord() || policyOf(handler.method()) != null
                || !answersAFile(handler.method()) || keyed(handler)) {
                continue;
            }
            boolean byRecord = handler.method().getMethodParameters().length > 0
                && java.util.Arrays.stream(handler.method().getMethodParameters())
                    .anyMatch(p -> p.hasParameterAnnotation(PathVariable.class) && p.getParameterType() == UUID.class);
            if (!byRecord) {
                continue;
            }
            if (FILE_NOT_CHART.containsKey(handler.name())) {
                used.add(handler.name());
            } else {
                unkeyed.add(handler.name() + "  " + handler.pattern());
            }
        }
        assertThat(unkeyed)
            .as("a file answer cannot be masked: key it in ChartKeys, mark it @RestrictedChartPolicy, or record"
                + " here why it holds no chart")
            .isEmpty();
        assertThat(new TreeSet<>(FILE_NOT_CHART.keySet())).as("recorded and no longer met").isSubsetOf(used);
    }

    private static boolean answersAFile(HandlerMethod method) {
        Type type = method.getMethod().getGenericReturnType();
        if (type instanceof ParameterizedType parameterized && parameterized.getRawType() == ResponseEntity.class) {
            type = parameterized.getActualTypeArguments()[0];
        }
        if (type instanceof ParameterizedType parameterized) {
            type = parameterized.getRawType();
        }
        return type == byte[].class || type == String.class || type == void.class
            || (type instanceof Class<?> raw && (Resource.class.isAssignableFrom(raw)
                || StreamingResponseBody.class.isAssignableFrom(raw)));
    }

    @Test
    @DisplayName("rule 3: clinical content in a JSON answer sits on a record the masking can attribute to a patient")
    void clinicalContentIsAttributable() {
        Set<String> found = new TreeSet<>();
        Set<String> used = new HashSet<>();
        for (Handler handler : handlers()) {
            RestrictedChartPolicy policy = policyOf(handler.method());
            if (handler.ownRecord() || (policy != null && policy.value() == RestrictedChartPolicy.Mode.NONE)
                || (handler.isGet() && policy == null && keyed(handler))) {
                continue;
            }
            walk(handler.method().getMethod().getGenericReturnType(), false, new HashSet<>(), handler.name(),
                found, used);
        }
        Map<String, String> byType = new TreeMap<>();
        for (String hit : found) {
            byType.putIfAbsent(hit.substring(0, hit.indexOf(' ')), hit);
        }
        assertThat(byType.values())
            .as("put patientId on the record that carries the field (the masking reads it), or record here why the"
                + " field is about no patient")
            .isEmpty();
        assertThat(new TreeSet<>(NOT_PATIENT_CONTENT.keySet())).as("recorded and no longer met").isSubsetOf(used);
    }

    private static void walk(Type type, boolean attributed, Set<String> seen, String handler, Set<String> found,
                             Set<String> used) {
        if (type instanceof WildcardType wildcard) {
            for (Type bound : wildcard.getUpperBounds()) {
                walk(bound, attributed, seen, handler, found, used);
            }
        } else if (type instanceof ParameterizedType parameterized) {
            walk(parameterized.getRawType(), attributed, seen, handler, found, used);
            for (Type argument : parameterized.getActualTypeArguments()) {
                walk(argument, attributed, seen, handler, found, used);
            }
        } else if (type instanceof Class<?> raw && raw.isArray()) {
            walk(raw.getComponentType(), attributed, seen, handler, found, used);
        } else if (type instanceof Class<?> raw && raw.getName().startsWith("com.example.hms.") && !raw.isEnum()) {
            List<Field> fields = fieldsOf(raw);
            boolean here = attributed || carriesPatient(raw, fields);
            if (!seen.add(raw.getName() + ":" + here)) {
                return;
            }
            for (Field field : fields) {
                if (!here && isText(field) && CLINICAL_FIELD.matcher(field.getName()).matches()) {
                    String key = raw.getSimpleName() + "#" + field.getName();
                    if (NOT_PATIENT_CONTENT.containsKey(key)) {
                        used.add(key);
                    } else if (NOT_PATIENT_CONTENT.containsKey(raw.getSimpleName())) {
                        used.add(raw.getSimpleName());
                    } else {
                        found.add(key + " via " + handler);
                    }
                }
                walk(field.getGenericType(), here, seen, handler, found, used);
            }
        }
    }

    /** Clinical text: a string, or a list of strings; never an id, a count or a flag. */
    private static boolean isText(Field field) {
        String name = field.getName();
        if (name.endsWith("Id") || name.endsWith("Ids")) {
            return false;
        }
        if (CharSequence.class.isAssignableFrom(field.getType())) {
            return true;
        }
        return java.util.Collection.class.isAssignableFrom(field.getType())
            && field.getGenericType() instanceof ParameterizedType p
            && p.getActualTypeArguments().length == 1 && p.getActualTypeArguments()[0] == String.class;
    }

    /** The masking attributes the record: a {@code patientId}, a {@code patient}, or the patient record itself. */
    private static boolean carriesPatient(Class<?> raw, List<Field> fields) {
        boolean hasId = false;
        for (Field field : fields) {
            if ("patientId".equals(field.getName()) || "patient".equals(field.getName())) {
                return true;
            }
            hasId |= "id".equals(field.getName());
        }
        return hasId && raw.getSimpleName().startsWith("Patient");
    }

    private static List<Field> fieldsOf(Class<?> raw) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> c = raw; c != null && c.getName().startsWith("com.example.hms."); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
        }
        return fields;
    }

    @Test
    @DisplayName("rule 4: every opt-out is recorded, with its mode and a reason")
    void everyOptOutIsRecorded() {
        Map<String, RestrictedChartPolicy.Mode> declared = new TreeMap<>();
        for (Handler handler : handlers()) {
            HandlerMethod method = handler.method();
            RestrictedChartPolicy own = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(),
                RestrictedChartPolicy.class);
            RestrictedChartPolicy onClass = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(),
                RestrictedChartPolicy.class);
            if (own != null) {
                assertThat(own.reason()).as(handler.name()).isNotBlank();
                declared.put(handler.name(), own.value());
            } else if (onClass != null) {
                assertThat(onClass.reason()).as(handler.name()).isNotBlank();
                declared.put(method.getBeanType().getSimpleName(), onClass.value());
            }
        }
        assertThat(declared).as("an opt-out of the restricted-chart rule is reviewed: record it here")
            .isEqualTo(new TreeMap<>(RECORDED_POLICIES));
    }
}
