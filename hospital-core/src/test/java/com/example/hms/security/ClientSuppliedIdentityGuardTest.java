package com.example.hms.security;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D302/D406/D407/D503 class: a write endpoint must not take, from the
 * client, the identity of who signs, authors, orders or performs it. The
 * server derives that identity from the session ({@link CallerIdentity}).
 *
 * <p>The guard lists every write handler ({@code POST/PUT/PATCH/DELETE}) whose
 * query/path parameters or request-body fields carry such an identity. Each
 * listed one is either cross-checked against the session in its service (and
 * says where), or is pre-existing debt with a reason. The allowance may only
 * shrink: a new endpoint that takes an actor id fails here.
 */
class ClientSuppliedIdentityGuardTest {

    private static final Pattern IDENTITY = Pattern.compile(
        "(?i)^(providerId|signerId|authorId|orderingProviderId|staffId"
            + "|(signedBy|performedBy|authoredBy|recordedBy|verifiedBy|administeredBy|cancelledBy|approvedBy"
            + "|reviewedBy|enteredBy|orderedBy|dispensedBy|collectedBy|revokedBy|finalizedBy)(Staff|User)?Id"
            + "|(signer|author|performer|verifier|recorder)(Staff|User)Id"
            + "|(ordering|discharging|signing|attending|authoring)Provider(Staff|User)?Id"
            + "|(doctor|nurse|midwife|pharmacist|clinician)(Staff|User)Id)$");

    private static final String CHECKED = "checked: CallerIdentity, must be the caller's own staff record (QA D503 class)";
    private static final String SUBJECT = "subject, not actor: the staff member the schedule/booking is FOR";

    /**
     * Known endpoints → why they are acceptable. Only shrink this map.
     * "checked" entries compare the id with the session in the service; the
     * others are recorded debt (tasklist.md, Standing platform debt).
     */
    private static final Map<String, String> ALLOWED = Map.ofEntries(
        Map.entry("AdmissionController#dischargePatient{dischargingProviderId}", CHECKED),
        Map.entry("AppointmentController#createAppointment{staffId}", SUBJECT),
        Map.entry("AppointmentController#searchAppointments{staffId}", "read: a search filter sent as a POST body"),
        Map.entry("AppointmentController#updateAppointment{staffId}", SUBJECT),
        Map.entry("DigitalSignatureController#signReport{signedByStaffId}", "checked: QA D71, must equal the caller's staff record"),
        Map.entry("DischargeApprovalController#approve{doctorStaffId}", CHECKED),
        Map.entry("DischargeApprovalController#cancel?staffId", CHECKED),
        Map.entry("DischargeApprovalController#reject{doctorStaffId}", CHECKED),
        Map.entry("DischargeApprovalController#requestDischarge{nurseStaffId}", CHECKED),
        Map.entry("DischargeSummaryController#createDischargeSummary{dischargingProviderId}", CHECKED),
        Map.entry("DischargeSummaryController#finalizeDischargeSummary?providerId", "checked: QA D503, optional cross-check; the signer is the session"),
        Map.entry("DischargeSummaryController#updateDischargeSummary{dischargingProviderId}", "ignored: an update never changes the provider"),
        Map.entry("EncounterController#addEncounterNoteAddendum{authorStaffId}", "checked: QA D406, resolveSessionNoteAuthor"),
        Map.entry("EncounterController#addEncounterNoteAddendum{authorUserId}", "checked: QA D406, resolveSessionNoteAuthor"),
        Map.entry("EncounterController#create{note.authorStaffId}", "checked: QA D406, EncounterServiceImpl create passes the nested note through resolveSessionNoteAuthor"),
        Map.entry("EncounterController#create{note.authorUserId}", "checked: QA D406, EncounterServiceImpl create passes the nested note through resolveSessionNoteAuthor"),
        Map.entry("EncounterController#update{note.authorStaffId}", "checked: QA D406, EncounterServiceImpl update passes the nested note through resolveSessionNoteAuthor"),
        Map.entry("EncounterController#update{note.authorUserId}", "checked: QA D406, EncounterServiceImpl update passes the nested note through resolveSessionNoteAuthor"),
        Map.entry("EncounterController#create{staffId}", "subject, not actor: the clinician the encounter is assigned to"),
        Map.entry("EncounterController#update{staffId}", "subject, not actor: the clinician the encounter is assigned to"),
        Map.entry("EncounterController#upsertEncounterNote{authorStaffId}", "checked: QA D406, resolveSessionNoteAuthor"),
        Map.entry("EncounterController#upsertEncounterNote{authorUserId}", "checked: QA D406, resolveSessionNoteAuthor"),
        Map.entry("EncounterTreatmentController#addTreatmentToEncounter{staffId}", CHECKED),
        Map.entry("ImagingOrderController#updateStatus{performedByUserId}", "ignored: the session is recorded (QA D503 class, this PR)"),
        Map.entry("ImagingResultController#createReport{performedByStaffId}", "subject, not actor: the technologist who acquired the study, named by the reporting radiologist; same-hospital check"),
        Map.entry("ImagingResultController#updateReport{performedByStaffId}", "subject, not actor: the technologist who acquired the study, named by the reporting radiologist; same-hospital check"),
        Map.entry("IsolationController#startPrecaution{orderedByStaffId}", CHECKED),
        Map.entry("LabTestValidationStudyController#create{performedByUserId}", "checked: CallerIdentity.resolveActorUserId, must be the caller (QA D503 class)"),
        Map.entry("LabTestValidationStudyController#update{performedByUserId}", "checked: CallerIdentity.resolveActorUserId, a new performer must be the caller (QA D503 class)"),
        Map.entry("LaborController#addEntry{recordedByStaffId}", CHECKED),
        Map.entry("MaternalHistoryController#createMaternalHistory{recordedByStaffId}", "ignored: the recorder is the session user's staff record"),
        Map.entry("MaternalHistoryController#updateMaternalHistory{recordedByStaffId}", "ignored: the recorder is the session user's staff record"),
        Map.entry("MedicalHistoryController#createFamilyHistory{recordedByStaffId}", CHECKED),
        Map.entry("MedicalHistoryController#createImmunization{administeredByStaffId}", CHECKED),
        Map.entry("MedicalHistoryController#createSocialHistory{recordedByStaffId}", CHECKED),
        Map.entry("MedicalHistoryController#updateFamilyHistory{recordedByStaffId}", CHECKED),
        Map.entry("MedicalHistoryController#updateImmunization{administeredByStaffId}", CHECKED),
        Map.entry("MedicalHistoryController#updateSocialHistory{recordedByStaffId}", CHECKED),
        Map.entry("NewbornAssessmentController#recordAssessment{recordedByStaffId}", CHECKED),
        Map.entry("OnCallScheduleController#create{staffId}", SUBJECT),
        Map.entry("OnCallScheduleController#update{staffId}", SUBJECT),
        Map.entry("PatientPortalController#scheduleMyAppointment{staffId}", "subject, not actor: the clinician the patient books with"),
        Map.entry("PatientVitalSignController#recordVital{recordedByStaffId}", CHECKED),
        Map.entry("PostpartumCareController#recordObservation{recordedByStaffId}", CHECKED),
        Map.entry("PrenatalSchedulingController#generateSchedule{staffId}", SUBJECT),
        Map.entry("PrescriptionController#create{staffId}", "checked: QA D84, prescription.prescriber.must.be.self"),
        Map.entry("PrescriptionController#update{staffId}", "checked: QA D84, prescription.prescriber.must.be.self"),
        Map.entry("SessionTemplateController#create{staffId}", SUBJECT),
        Map.entry("SessionTemplateController#update{staffId}", SUBJECT),
        Map.entry("StaffAvailabilityController#create{staffId}", SUBJECT),
        Map.entry("StaffSchedulingController#bulkScheduleShifts{staffId}", SUBJECT),
        Map.entry("StaffSchedulingController#requestLeave{staffId}", SUBJECT),
        Map.entry("StaffSchedulingController#scheduleShift{staffId}", SUBJECT),
        Map.entry("StaffSchedulingController#updateShift{staffId}", SUBJECT),
        Map.entry("TransferController#cancelTransfer{cancelledByStaffId}", CHECKED),
        Map.entry("TransfusionController#startAdministration{verifiedByStaffId}", "checked: the independent second checker, an active staff member at this hospital and never the caller"),
        Map.entry("TreatmentPlanController#create{authorStaffId}", CHECKED),
        Map.entry("TreatmentPlanController#update{authorStaffId}", "checked: an edit keeps the author; a new author must be the caller")
    );

    @Test
    void noWriteEndpointTakesAnActorIdentityFromTheClientUnlessListed() throws Exception {
        TreeSet<String> found = new TreeSet<>();
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.hms")) {
            Class<?> controller = Class.forName(definition.getBeanClassName());
            scan(controller, found);
        }
        assertThat(found)
            .as("write endpoints taking an actor identity from the client; derive it from the session "
                + "(CallerIdentity) or, if it is cross-checked, list it in ALLOWED with the reason")
            .isSubsetOf(ALLOWED.keySet());
        assertThat(ALLOWED.keySet())
            .as("ALLOWED entries that no longer match anything: remove them (the allowance only shrinks)")
            .isSubsetOf(found);
    }

    static void scan(Class<?> controller, Set<String> found) {
        for (Method method : controller.getDeclaredMethods()) {
            if (!isWrite(method)) {
                continue;
            }
            String where = controller.getSimpleName() + "#" + method.getName();
            for (Parameter parameter : method.getParameters()) {
                inspect(where, parameter, found);
            }
        }
    }

    private static void inspect(String where, Parameter parameter, Set<String> found) {
        RequestParam requestParam = parameter.getAnnotation(RequestParam.class);
        PathVariable pathVariable = parameter.getAnnotation(PathVariable.class);
        if (requestParam != null || pathVariable != null) {
            String name = requestParam != null && !requestParam.name().isEmpty() ? requestParam.name()
                : requestParam != null && !requestParam.value().isEmpty() ? requestParam.value()
                : pathVariable != null && !pathVariable.name().isEmpty() ? pathVariable.name()
                : pathVariable != null && !pathVariable.value().isEmpty() ? pathVariable.value()
                : parameter.getName();
            if (IDENTITY.matcher(name).matches() && pathVariable == null) {
                found.add(where + "?" + name);
            }
            return;
        }
        // G2: a @ModelAttribute or an unannotated non-simple parameter is bound
        // from the request like a body, so its fields count too.
        boolean bound = parameter.getAnnotation(RequestBody.class) != null
            || parameter.getAnnotation(ModelAttribute.class) != null
            || (parameter.getAnnotations().length == 0 && !BeanUtils.isSimpleProperty(parameter.getType()));
        if (bound) {
            walk(where, "", parameter.getParameterizedType(), found, new HashSet<>());
        }
    }

    /**
     * G1: walks a bound type: its own and inherited fields, nested DTOs under
     * com.example.hms, and the element types of collections, maps and arrays.
     * A nested match is recorded as {@code where{outer.inner}}.
     */
    private static void walk(String where, String prefix, Type type, Set<String> found, Set<Class<?>> visiting) {
        if (type instanceof ParameterizedType parameterized) {
            for (Type argument : parameterized.getActualTypeArguments()) {
                walk(where, prefix, argument, found, visiting);
            }
            walk(where, prefix, parameterized.getRawType(), found, visiting);
            return;
        }
        if (type instanceof GenericArrayType array) {
            walk(where, prefix, array.getGenericComponentType(), found, visiting);
            return;
        }
        if (type instanceof WildcardType wildcard) {
            for (Type upper : wildcard.getUpperBounds()) {
                walk(where, prefix, upper, found, visiting);
            }
            return;
        }
        if (!(type instanceof Class<?> raw)) {
            return;
        }
        if (raw.isArray()) {
            walk(where, prefix, raw.getComponentType(), found, visiting);
            return;
        }
        if (!raw.getName().startsWith("com.example.hms") || raw.isEnum() || !visiting.add(raw)) {
            return;
        }
        for (Class<?> c = raw; c != null && c.getName().startsWith("com.example.hms"); c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (IDENTITY.matcher(field.getName()).matches()) {
                    found.add(where + "{" + prefix + field.getName() + "}");
                }
                walk(where, prefix + field.getName() + ".", field.getGenericType(), found, visiting);
            }
        }
        visiting.remove(raw);
    }

    private static boolean isWrite(Method method) {
        if (method.isAnnotationPresent(PostMapping.class) || method.isAnnotationPresent(PutMapping.class)
            || method.isAnnotationPresent(PatchMapping.class) || method.isAnnotationPresent(DeleteMapping.class)) {
            return true;
        }
        // G2: @RequestMapping(method = POST/PUT/PATCH/DELETE) is a write too.
        RequestMapping mapping = method.getAnnotation(RequestMapping.class);
        return mapping != null && List.of(mapping.method()).stream().anyMatch(m -> m == RequestMethod.POST
            || m == RequestMethod.PUT || m == RequestMethod.PATCH || m == RequestMethod.DELETE);
    }

    // Fixtures are not @RestController, so the classpath scan never sees them.

    record ItemDto(String performedByStaffId, String note) { }

    record OuterDto(List<ItemDto> items, ItemDto[] array, ItemDto single) { }

    record NodeDto(NodeDto parent, String signerStaffId) { }

    static class FormDto {
        String authorStaffId;
    }

    static class Fixture {
        @PostMapping("/a") void nested(@RequestBody OuterDto body) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
        @PostMapping("/b") void bodyList(@RequestBody List<ItemDto> body) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
        @PostMapping("/c") void cyclic(@RequestBody NodeDto body) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
        @RequestMapping(value = "/d", method = RequestMethod.POST) void legacy(@RequestBody ItemDto body) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
        @RequestMapping(value = "/e", method = RequestMethod.GET) void legacyRead(@RequestBody ItemDto body) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
        @PostMapping("/f") void model(@ModelAttribute FormDto form) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
        @PostMapping("/g") void unannotated(FormDto form) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
        @PostMapping("/h") void simple(String staffId) {
            // Intentionally empty: the guard reads only this signature, never calls it.
        }
    }

    @Test
    void g1RecursesIntoNestedDtosCollectionsAndArraysWithoutLooping() {
        Set<String> found = new TreeSet<>();
        scan(Fixture.class, found);
        assertThat(found).contains(
            "Fixture#nested{items.performedByStaffId}",
            "Fixture#nested{array.performedByStaffId}",
            "Fixture#nested{single.performedByStaffId}",
            "Fixture#bodyList{performedByStaffId}",
            "Fixture#cyclic{signerStaffId}");
    }

    @Test
    void g2CoversRequestMappingWritesAndFormBoundParameters() {
        Set<String> found = new TreeSet<>();
        scan(Fixture.class, found);
        assertThat(found).contains(
            "Fixture#legacy{performedByStaffId}",
            "Fixture#model{authorStaffId}",
            "Fixture#unannotated{authorStaffId}")
            .noneMatch(f -> f.startsWith("Fixture#legacyRead") || f.startsWith("Fixture#simple"));
    }
}
