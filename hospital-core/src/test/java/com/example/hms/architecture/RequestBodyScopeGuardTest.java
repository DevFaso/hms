package com.example.hms.architecture;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.tenant.ActingScope;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.security.tenant.RequestBodyScopeAdvice;
import com.example.hms.service.recordaccess.RecordAccessDecision;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.support.UserAccountAccess;
import com.example.hms.testsupport.ControllerMappingRegistry;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * QA r81 D4260: a request body that names a hospital, a patient or a staff member is measured
 * against the hospital the caller acts at, on every handler, before the handler runs.
 *
 * <p>A staff member is also named as a provider, a physician, a clinician or a user: those names are
 * recorded too (r81 review).
 *
 * <p>{@link RequestBodyScopeAdvice} is the one place that does it. This guard walks Spring's mapping
 * registry, finds every handler whose {@code @RequestBody} carries {@code hospitalId},
 * {@code patientId}, {@code staffId} or a {@code ...StaffId} (through nested request objects and
 * lists), builds that body naming a row of ANOTHER hospital and runs the real advice on it as a
 * caller of one hospital:
 * <ol>
 *   <li>a hospital the caller holds no role at is refused (403) on every handler;</li>
 *   <li>a patient the caller could not open at its hospital, and a staff member of another
 *       hospital, answer as an unknown id (404) on every handler, unless the handler lifts the
 *       field with a reason: that set is frozen in {@link #LIFTED}, it only shrinks;</li>
 *   <li>a body field that names a hospital, a patient or a staff member in another way (a code,
 *       a username, a second hospital) is outside the advice, so each one is recorded in
 *       {@link #NAMED_OTHERWISE} with the rule its service applies. A new one fails here until it
 *       is decided.</li>
 * </ol>
 * The behaviour end to end is in {@code VisitWriteScopeIT}.
 */
@DisplayName("r81 D4260: a request body's hospital, patient and staff are those of the acting hospital")
class RequestBodyScopeGuardTest {

    private static final UUID OWN_HOSPITAL = UUID.randomUUID();
    private static final UUID CALLER = UUID.randomUUID();

    private static final String PARENT_ROW = "the service takes the patient from the in-scope parent row the body"
        + " names and refuses a body naming another (PatientWriteRegistrationCoverageTest records it)";

    private static final String STAFF_OF_THE_VISIT = "EncounterServiceImpl requires the clinician to be a staff member"
        + " of the visit's hospital with an active assignment there";
    private static final String APPOINTMENT_HOSPITAL = "AppointmentServiceImpl.requireHospitalScope: the hospital it"
        + " resolves is one the caller holds";
    private static final String APPOINTMENT_PATIENT = "AppointmentServiceImpl: PatientRegistrationGuard at the booking"
        + " hospital";
    private static final String APPOINTMENT_STAFF = "AppointmentServiceImpl books only a clinician of the booking"
        + " hospital";
    private static final String A_SEARCH_FILTER = "a search filter (a read): the listing is bounded by the caller's"
        + " hospitals";
    private static final String SUPER_ADMIN_ONLY = "a super-admin endpoint: a verified super-admin may name any hospital";
    private static final String MERGE_IN_SCOPE = "EmpiServiceImpl merges two patients only inside the caller's scope";
    private static final String ANOTHER_HOSPITAL_BY_DESIGN = "names the OTHER hospital of a referral, a transfer or a"
        + " performing laboratory, never the hospital the row is written at";
    private static final String PHYSICIAN_OF_THE_STAY = "AdmissionServiceImpl.requirePhysician: a physician at the"
        + " stay's hospital (and, for the admitting or discharging one, the caller unless a super-admin)";
    private static final String SUPER_ADMIN_AUDIT_FILTER = "a filter of the super-admin audit search (a read)";
    private static final String AUTHOR_IS_THE_CALLER = "EncounterServiceImpl.resolveSessionNoteAuthor: the author is"
        + " the signed-in user; any other id is refused";
    private static final String RELEASE_AUTHORITY = "OPositiveReleaseAuthorityService / IncompatibleReleaseDecisions:"
        + " an active holder of the required role at the release's hospital";
    private static final String IGNORED_REGISTRAR = "not read: the registrar is the caller"
        + " (UserRoleHospitalAssignmentServiceImpl.requireCallerAccount)";
    private static final String HOLDER_IN_REACH = "UserRoleHospitalAssignmentServiceImpl: the holder must be in the"
        + " caller's reach (UserAccountAccess.holderReach) and the grant passes requireMayGrant";
    private static final String STAFF_OF_THE_HOSPITAL = "r81: the service resolves only a staff member of the acting"
        + " hospital; any other answers 404 as an unknown id";
    /** Fields whose rule is a follow-up of the r81 review; this list only shrinks. */
    private static final String FOLLOW_UP = "follow-up of the r81 review: its rule is being settled";

    /** {@code Controller#handler} -> the fields it lifts and why. Frozen: it only shrinks. */
    private static final Map<String, String> LIFTED = Map.ofEntries(
        entry("PatientHospitalRegistrationController#registerPatient", "patientId: linking a patient registered at"
            + " another hospital of the organisation is the handler's purpose; the service bounds whom the desk links"),
        entry("BreakGlassController#declare", "patientId: an emergency access declaration reaches a patient the"
            + " acting hospital does not hold; bounded to the patients the caller's search finds, and audited"),
        entry("LabResultController#createLabResult", "patientId: " + PARENT_ROW),
        entry("LabResultController#updateLabResult", "patientId: " + PARENT_ROW),
        entry("DispenseController#dispense", "patientId: " + PARENT_ROW),
        entry("DispenseController#markReady", "patientId: " + PARENT_ROW),
        entry("PharmacyClaimController#create", "patientId: " + PARENT_ROW),
        entry("PharmacyPaymentController#create", "patientId: " + PARENT_ROW),
        entry("PharmacySaleController#create", "patientId: an over-the-counter sale; the patient is optional and"
            + " resolved through the hospital-scoped finder"));

    /**
     * {@code RequestType#field} -> the rule its service applies. A body field that names a hospital, a
     * patient or a staff member under another name than the advice reads.
     */
    private static final Map<String, String> NAMED_OTHERWISE = Map.ofEntries(
        entry("EncounterRequestDTO#hospitalIdentifier", "r81 D4260: EncounterServiceImpl requires the hospital it"
            + " resolves to be the acting hospital (TenantOwnership), whichever field named it"),
        entry("EncounterRequestDTO#patientIdentifier", "EncounterServiceImpl requires a registration at the visit's"
            + " hospital, which is the acting hospital"),
        entry("EncounterRequestDTO#staffIdentifier", STAFF_OF_THE_VISIT),
        entry("EncounterRequestDTO#staffEmail", STAFF_OF_THE_VISIT),
        entry("AppointmentRequestDTO#hospitalCode", APPOINTMENT_HOSPITAL),
        entry("AppointmentRequestDTO#patientEmail", APPOINTMENT_PATIENT),
        entry("AppointmentRequestDTO#patientUsername", APPOINTMENT_PATIENT),
        entry("AppointmentRequestDTO#staffEmail", APPOINTMENT_STAFF),
        entry("AppointmentRequestDTO#staffUsername", APPOINTMENT_STAFF),
        entry("AppointmentFilterDTO#patientEmail", A_SEARCH_FILTER),
        entry("AppointmentFilterDTO#staffEmail", A_SEARCH_FILTER),
        entry("BillingInvoiceRequestDTO#patientEmail", "BillingInvoiceServiceImpl resolves the patient among those"
            + " registered at the invoice's hospital"),
        entry("PrescriptionRequestDTO#patientIdentifier", "PrescriptionServiceImpl: PatientRegistrationGuard at the"
            + " prescriber's hospital"),
        entry("PatientHospitalRegistrationRequestDTO#patientUsername", "the desk links a patient registered at"
            + " another hospital of the organisation; the service bounds whom it may link (see LIFTED)"),
        entry("UserRoleHospitalAssignmentRequestDTO#hospitalCode", "UserAccountAccess.requireMayGrant: a role is"
            + " granted only at a hospital the caller administers"),
        entry("UserRoleAssignmentBulkImportRequestDTO#defaultHospitalId", "each imported row is granted through"
            + " UserAccountAccess.requireMayGrant, as a single assignment is"),
        entry("SuperAdminUserBulkImportRequestDTO#defaultHospitalId", SUPER_ADMIN_ONLY),
        entry("SuperAdminLabOrderCreateRequestDTO#hospitalIdentifier", SUPER_ADMIN_ONLY),
        entry("SuperAdminLabOrderCreateRequestDTO#patientIdentifier", SUPER_ADMIN_ONLY),
        entry("SuperAdminLabOrderCreateRequestDTO#orderingStaffIdentifier", SUPER_ADMIN_ONLY),
        entry("EmpiPatientMergeRequestDTO#primaryPatientId", MERGE_IN_SCOPE),
        entry("EmpiPatientMergeRequestDTO#secondaryPatientId", MERGE_IN_SCOPE),
        entry("NewbornAssessmentRequestDTO#newbornPatientId", "NewbornAssessmentServiceImpl accepts only a baby"
            + " registered from the delivery the assessment belongs to"),
        entry("GeneralReferralRequestDTO#receivingHospitalId", ANOTHER_HOSPITAL_BY_DESIGN),
        entry("LabOrderRequestDTO#performingHospitalId", ANOTHER_HOSPITAL_BY_DESIGN),
        entry("LaborTransferRequestDTO#destinationHospitalId", ANOTHER_HOSPITAL_BY_DESIGN),
        entry("AdmissionDischargeRequestDTO#dischargingProviderId", PHYSICIAN_OF_THE_STAY),
        entry("AdmissionRequestDTO#admittingProviderId", PHYSICIAN_OF_THE_STAY),
        entry("AdmissionRequestDTO#attendingPhysicianId", PHYSICIAN_OF_THE_STAY),
        entry("AdmissionUpdateRequestDTO#attendingPhysicianId", PHYSICIAN_OF_THE_STAY),
        entry("AdtIntakeProviderConfigRequestDTO#admittingProviderId", "AdtIntakeProviderConfigServiceImpl: an active"
            + " staff member of the body's hospital holding an admitting role there"),
        entry("AuditSearchRequestDTO#impersonatorUserId", SUPER_ADMIN_AUDIT_FILTER),
        entry("AuditSearchRequestDTO#userId", SUPER_ADMIN_AUDIT_FILTER),
        entry("DischargeSummaryRequestDTO#dischargingProviderId", "DischargeSummaryServiceImpl: a treating clinician"
            + " of the acting hospital (TenantOwnership), the caller unless a super-admin"),
        entry("EmergencyReleaseRequestDTO#authoriserUserId", RELEASE_AUTHORITY),
        entry("EmergencyReleaseRequestDTO#biologistUserId", RELEASE_AUTHORITY),
        entry("EmergencyReleaseRequestDTO#orderingPhysicianUserId", RELEASE_AUTHORITY),
        entry("EncounterNoteAddendumRequestDTO#authorUserId", AUTHOR_IS_THE_CALLER),
        entry("EncounterNoteRequestDTO#authorUserId", AUTHOR_IS_THE_CALLER),
        entry("GeneralReferralRequestDTO#receivingProviderId", "GeneralReferralServiceImpl: a physician working at"
            + " the receiving hospital, which is another hospital by design"),
        entry("GeneralReferralRequestDTO#referringProviderId", "GeneralReferralServiceImpl.requireReferrerIsCaller:"
            + " the caller, a prescriber of the sending hospital"),
        entry("ImagingOrderSignatureRequestDTO#providerUserId", "ImagingOrderServiceImpl: the signer is the signed-in"
            + " user; any other id is refused"),
        entry("ImagingOrderStatusUpdateRequestDTO#performedByUserId", "not read: the performer is the signed-in user"
            + " (ImagingExamRecord)"),
        entry("ImagingReportUpsertRequestDTO#interpretingProviderId", "ImagingReportServiceImpl.resolveStaff: a staff"
            + " member of the report's hospital"),
        entry("ImpersonationStartRequestDTO#targetUserId", SUPER_ADMIN_ONLY),
        entry("LabTestValidationStudyRequestDTO#performedByUserId", "CallerIdentity.resolveActorUserId: the caller,"
            + " unless a verified super-admin"),
        entry("NurseHandoffCreateRequestDTO#recipientUserId", "NurseTaskServiceImpl: an active holder of a hand-off"
            + " role at the acting hospital"),
        entry("NurseMedicationAdministrationRequestDTO#witnessUserId", "ControlledAdministrationWitness: an active"
            + " holder of a witness role at the prescription's hospital"),
        entry("ObgynReferralAcknowledgeRequestDTO#obgynUserId", "r81: ObgynReferralServiceImpl accepts only an active"
            + " staff member of the referral's hospital; any other answers 404 as an unknown user"),
        entry("PatientRequestDTO#userId", FOLLOW_UP),
        entry("RecallRequestDTO#preferredProviderId", STAFF_OF_THE_HOSPITAL),
        entry("RuleSignoffRequestDTO#signerUserId", "HospitalRuleSignoffService: an active staff member of the"
            + " hospital"),
        entry("SmartPhraseRequestDTO#ownerUserId", "SmartPhraseServiceImpl.applyOwnershipDefaults: the caller for a"
            + " personal phrase, none for a hospital or global one"),
        entry("StaffRequestDTO#userEmail", FOLLOW_UP),
        entry("TransfusionAdministrationRequestDTO#verifiedByUserId", "TransfusionServiceImpl: an active staff member"
            + " of the transfusion's hospital"),
        entry("UserRoleAssignmentBulkImportRequestDTO#registeredByUserId", IGNORED_REGISTRAR),
        entry("UserRoleAssignmentMultiRequestDTO#registeredByUserId", IGNORED_REGISTRAR),
        entry("UserRoleAssignmentMultiRequestDTO#userId", HOLDER_IN_REACH),
        entry("UserRoleAssignmentMultiRequestDTO#userIdentifier", HOLDER_IN_REACH),
        entry("UserRoleHospitalAssignmentRequestDTO#registeredByUserId", IGNORED_REGISTRAR),
        entry("UserRoleHospitalAssignmentRequestDTO#userId", HOLDER_IN_REACH),
        entry("UserRoleHospitalAssignmentRequestDTO#userIdentifier", HOLDER_IN_REACH),
        entry("WaitlistEntryRequestDTO#preferredProviderId", STAFF_OF_THE_HOSPITAL));

    private static final Pattern NAMES_A_TENANT_ROW = Pattern.compile(
        "(?i).*(hospital|patient|staff|provider|physician|clinician|doctor|nurse|practitioner|prescriber|surgeon"
            + "|midwife|pharmacist|technician|user|assignee)(Id|Identifier|Code|Username|Email|Mrn)$");

    private static final JsonMapper JSON = JsonMapper.builder()
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build();

    @AfterEach
    void clearScope() {
        HospitalContextHolder.clear();
    }

    @Test
    @DisplayName("every handler whose body names a hospital refuses one the caller holds no role at")
    void aHospitalTheCallerDoesNotHoldIsRefusedOnEveryHandler() {
        Set<String> notRefused = new TreeSet<>();
        Set<String> unbuildable = new TreeSet<>();
        int checked = 0;
        for (BodySite site : sites()) {
            for (List<String> path : site.paths()) {
                if (!RequestBodyScopeAdvice.HOSPITAL_ID.equals(last(path))) {
                    continue;
                }
                checked++;
                Object body = build(site, path, UUID.randomUUID());
                if (body == null) {
                    unbuildable.add(site.key() + " " + path);
                } else if (!(run(site, body) instanceof HospitalScopeRefusedException)) {
                    notRefused.add(site.key() + " " + path);
                }
            }
        }
        assertThat(checked).as("the scan found the handlers whose body names a hospital").isGreaterThan(100);
        assertThat(unbuildable).as("bodies this guard could not build").isEmpty();
        assertThat(notRefused)
            .as("handlers that accept a body naming a hospital the caller holds no role at" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("every handler whose body names a patient or a staff member answers 404 outside the acting hospital")
    void aPatientOrStaffOutsideTheActingHospitalIsUnknownOnEveryHandler() {
        Set<String> notRefused = new TreeSet<>();
        Set<String> unbuildable = new TreeSet<>();
        Map<String, Set<String>> lifted = new TreeMap<>();
        int checked = 0;
        for (BodySite site : sites()) {
            Set<String> liftedHere = RequestBodyScopeAdvice.liftedFields(site.handler().getMethod());
            if (!liftedHere.isEmpty()) {
                lifted.put(site.key(), new TreeSet<>(liftedHere));
            }
            for (List<String> path : site.paths()) {
                String field = last(path);
                if (RequestBodyScopeAdvice.HOSPITAL_ID.equals(field) || liftedHere.contains(field)) {
                    continue;
                }
                checked++;
                Object body = build(site, path, UUID.randomUUID());
                if (body == null) {
                    unbuildable.add(site.key() + " " + path);
                } else if (!(run(site, body) instanceof ResourceNotFoundException)) {
                    notRefused.add(site.key() + " " + path);
                }
            }
        }
        assertThat(checked).as("the scan found the handlers whose body names a patient or staff").isGreaterThan(80);
        assertThat(unbuildable).as("bodies this guard could not build").isEmpty();
        assertThat(notRefused)
            .as("handlers that accept a body naming a patient or a staff member outside the acting hospital"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(lifted.keySet())
            .as("handlers lifting a field: a new one is a decision, add it to LIFTED with its reason; one that no"
                + " longer lifts anything is deleted from it")
            .containsExactlyInAnyOrderElementsOf(LIFTED.keySet());
        lifted.forEach((key, fields) -> assertThat(LIFTED.get(key))
            .as("%s lifts %s", key, fields).startsWith(String.join(", ", fields) + ":"));
    }

    @Test
    @DisplayName("a body field naming a hospital, patient or staff under another name is a recorded decision")
    void everyOtherNameForATenantRowIsRecorded() {
        Set<String> found = new TreeSet<>();
        Set<Class<?>> seen = new HashSet<>();
        for (BodySite site : sites(true)) {
            collectOtherNames(site.bodyType(), found, seen, 0);
        }
        assertThat(found)
            .as("request body fields naming a hospital, a patient or a staff member that RequestBodyScopeAdvice does"
                + " not read: record each in NAMED_OTHERWISE with the rule its service applies, or rename the field")
            .containsExactlyInAnyOrderElementsOf(NAMED_OTHERWISE.keySet());
        assertThat(NAMED_OTHERWISE.entrySet().stream().filter(e -> FOLLOW_UP.equals(e.getValue()))
            .map(Map.Entry::getKey).toList())
            .as("fields still waiting for a bounded rule: this list only shrinks")
            .isSubsetOf("PatientRequestDTO#userId", "StaffRequestDTO#userEmail");
    }

    /* ---------------------------------------------------------------------
       Running the real advice as a caller of one hospital
       --------------------------------------------------------------------- */

    private static RuntimeException run(BodySite site, Object body) {
        HospitalContextHolder.clear();
        HospitalContextHolder.setContext(HospitalContext.builder()
            .principalUserId(CALLER)
            .principalUsername("guard")
            .permittedHospitalIds(Set.of(OWN_HOSPITAL))
            .activeHospitalId(OWN_HOSPITAL)
            .hospitalFacilityTypes(Map.of(OWN_HOSPITAL, FacilityType.HOSPITAL))
            .build());
        try {
            advice().afterBodyRead(body, null, site.parameter(), site.parameter().getGenericParameterType(),
                JacksonJsonHttpMessageConverter.class);
            return null;
        } catch (RuntimeException refusal) {
            return refusal;
        } finally {
            HospitalContextHolder.clear();
        }
    }

    private static RequestBodyScopeAdvice advice() {
        ActingScopeResolver resolver = mock(ActingScopeResolver.class);
        when(resolver.narrowTo(any())).thenReturn(new ActingScope.Refused(ActingScope.Reason.NOT_PERMITTED));
        UserAccountAccess access = mock(UserAccountAccess.class);
        when(access.organisationHospitalIds()).thenReturn(Set.of());
        PatientHospitalRegistrationRepository registrations = mock(PatientHospitalRegistrationRepository.class);
        RecordAccessPolicy policy = mock(RecordAccessPolicy.class);
        when(policy.decide(any(), any(), any())).thenAnswer(call -> RecordAccessDecision.refused(
            call.getArgument(1), call.getArgument(2), call.getArgument(0), null, null));
        StaffRepository staff = mock(StaffRepository.class);
        List<Object[]> elsewhere = new ArrayList<>();
        elsewhere.add(new Object[] {UUID.randomUUID(), UUID.randomUUID()});
        when(staff.findHospitalIdAndUserIdById(any())).thenReturn(elsewhere);
        return new RequestBodyScopeAdvice(provider(resolver), provider(access), provider(registrations),
            provider(policy), provider(staff));
    }

    private static ObjectProvider<ActingScopeResolver> provider(ActingScopeResolver bean) {
        return new FixedProvider<>(bean);
    }

    private static ObjectProvider<UserAccountAccess> provider(UserAccountAccess bean) {
        return new FixedProvider<>(bean);
    }

    private static ObjectProvider<PatientHospitalRegistrationRepository> provider(
        PatientHospitalRegistrationRepository bean) {
        return new FixedProvider<>(bean);
    }

    private static ObjectProvider<RecordAccessPolicy> provider(RecordAccessPolicy bean) {
        return new FixedProvider<>(bean);
    }

    private static ObjectProvider<StaffRepository> provider(StaffRepository bean) {
        return new FixedProvider<>(bean);
    }

    /** A provider over one bean. */
    private record FixedProvider<B>(B bean) implements ObjectProvider<B> {
        @Override
        public B getObject() {
            return bean;
        }

        @Override
        public B getIfAvailable() {
            return bean;
        }
    }

    /* ---------------------------------------------------------------------
       The handlers and their bodies
       --------------------------------------------------------------------- */

    /** A handler's {@code @RequestBody} and the paths to the scoped ids inside it. */
    private record BodySite(HandlerMethod handler, MethodParameter parameter, Class<?> bodyType,
                            List<List<String>> paths) {
        String key() {
            return handler.getBeanType().getSimpleName() + "#" + handler.getMethod().getName();
        }
    }

    private static List<BodySite> sites() {
        return sites(false);
    }

    private static List<BodySite> sites(boolean withoutScopedIdsToo) {
        Map<String, BodySite> sites = new TreeMap<>();
        for (HandlerMethod handler : ControllerMappingRegistry.mapping().getHandlerMethods().values()) {
            for (MethodParameter parameter : handler.getMethodParameters()) {
                if (!parameter.hasParameterAnnotation(RequestBody.class)) {
                    continue;
                }
                ResolvableType declared = ResolvableType.forMethodParameter(parameter);
                boolean list = Collection.class.isAssignableFrom(declared.toClass());
                Class<?> type = list ? declared.asCollection().getGeneric(0).toClass() : declared.toClass();
                if (!RequestBodyScopeAdvice.isPayload(type)) {
                    continue;
                }
                List<List<String>> paths = new ArrayList<>();
                collectPaths(type, new ArrayList<>(), paths, 0);
                if (!paths.isEmpty() || withoutScopedIdsToo) {
                    BodySite site = new BodySite(handler, parameter, type, paths);
                    sites.put(site.key() + "/" + parameter.getParameterIndex(), site);
                }
            }
        }
        return List.copyOf(sites.values());
    }

    /** The paths the advice walks: scoped ids, through nested request objects and lists. */
    private static void collectPaths(Class<?> type, List<String> prefix, List<List<String>> paths, int depth) {
        if (depth > 3 || prefix.contains(type.getName())) {
            return;
        }
        for (Method accessor : RequestBodyScopeAdvice.accessors(type)) {
            String field = RequestBodyScopeAdvice.fieldName(accessor);
            List<String> path = new ArrayList<>(prefix);
            path.add(field);
            if (accessor.getReturnType() == UUID.class) {
                paths.add(path);
                continue;
            }
            ResolvableType returned = ResolvableType.forMethodReturnType(accessor);
            Class<?> nested = Collection.class.isAssignableFrom(accessor.getReturnType())
                ? returned.asCollection().getGeneric(0).toClass()
                : accessor.getReturnType();
            if (nested != null && RequestBodyScopeAdvice.isPayload(nested) && nested != type) {
                List<String> marked = new ArrayList<>(path);
                if (Collection.class.isAssignableFrom(accessor.getReturnType())) {
                    marked.set(marked.size() - 1, field + "[]");
                }
                collectPaths(nested, marked, paths, depth + 1);
            }
        }
    }

    private static String last(List<String> path) {
        return path.get(path.size() - 1);
    }

    /** The body, as the converter would read it, with one id at {@code path}; {@code null} when it cannot be built. */
    private static Object build(BodySite site, List<String> path, UUID id) {
        StringBuilder json = new StringBuilder();
        for (int i = 0; i < path.size(); i++) {
            String step = path.get(i);
            boolean list = step.endsWith("[]");
            json.append("{\"").append(list ? step.substring(0, step.length() - 2) : step).append("\":");
            if (i == path.size() - 1) {
                json.append('"').append(id).append('"');
            } else if (list) {
                json.append('[');
            }
        }
        String closing = closing(path);
        try {
            return JSON.readValue(json + closing, site.bodyType());
        } catch (RuntimeException ex) {
            // A request type the plain mapper cannot read alone: fill the one field directly.
            return fill(site.bodyType(), path, 0, id);
        }
    }

    private static final org.springframework.objenesis.SpringObjenesis OBJENESIS =
        new org.springframework.objenesis.SpringObjenesis();

    private static Object fill(Class<?> type, List<String> path, int index, UUID id) {
        String step = path.get(index);
        boolean list = step.endsWith("[]");
        Field field = field(type, list ? step.substring(0, step.length() - 2) : step);
        if (field == null || type.isRecord()) {
            return null;
        }
        Object instance = OBJENESIS.newInstance(type);
        Object value = id;
        if (index < path.size() - 1) {
            Class<?> nested = list ? ResolvableType.forField(field).asCollection().getGeneric(0).toClass()
                : field.getType();
            Object inner = fill(nested, path, index + 1, id);
            if (inner == null) {
                return null;
            }
            value = list ? List.of(inner) : inner;
        }
        try {
            field.setAccessible(true);
            field.set(instance, value);
            return instance;
        } catch (ReflectiveOperationException | RuntimeException ex) {
            return null;
        }
    }

    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(name)) {
                    return field;
                }
            }
        }
        return null;
    }

    private static String closing(List<String> path) {
        StringBuilder closing = new StringBuilder();
        for (int i = path.size() - 1; i >= 0; i--) {
            closing.append('}');
            if (i > 0 && path.get(i - 1).endsWith("[]")) {
                closing.append(']');
            }
        }
        return closing.toString();
    }

    private static void collectOtherNames(Class<?> type, Set<String> found, Set<Class<?>> seen, int depth) {
        if (depth > 3 || !RequestBodyScopeAdvice.isPayload(type) || !seen.add(type)) {
            return;
        }
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                String name = field.getName();
                boolean idOrText = field.getType() == UUID.class || field.getType() == String.class;
                if (idOrText && NAMES_A_TENANT_ROW.matcher(name).matches()
                    && !(field.getType() == UUID.class && RequestBodyScopeAdvice.isScopedField(name))) {
                    found.add(type.getSimpleName() + "#" + name);
                }
                Class<?> nested = Collection.class.isAssignableFrom(field.getType())
                    ? ResolvableType.forField(field).asCollection().getGeneric(0).toClass()
                    : field.getType();
                if (nested != null) {
                    collectOtherNames(nested, found, seen, depth + 1);
                }
            }
        }
    }
}
