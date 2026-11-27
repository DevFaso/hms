package com.example.hms.security.tenant;

import com.example.hms.enums.FacilityType;
import com.example.hms.exception.HospitalScopeRefusedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.security.context.HospitalContextRequestOverrides;
import com.example.hms.service.recordaccess.RecordAccessPolicy;
import com.example.hms.service.support.UserAccountAccess;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.lang.NonNull;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

import java.lang.reflect.AnnotatedElement;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The hospital, the patient and the staff member a request BODY names are
 * measured against the hospital the caller acts at, for every handler at once
 * and before the handler (or its validation) runs. It is the body counterpart
 * of {@link HospitalIdNarrowingInterceptor}, which does this for a
 * {@code hospitalId} path variable or request parameter.
 *
 * <ul>
 *   <li><b>{@code hospitalId}</b>: one the caller holds a role at (on a write
 *       outside the patient portal, a role other than PATIENT). The request
 *       then acts there (the scope is narrowed to it, so the write is recorded
 *       and audited at that hospital); any other hospital is refused with 403,
 *       audited. A hospital of the organisation an organisation admin
 *       administers passes un-narrowed: the administration services bound it
 *       ({@code UserAccountAccess}).</li>
 *   <li><b>{@code patientId}</b>: a patient the caller could open at the acting
 *       hospital: registered there, or in the care of its staff
 *       ({@link RecordAccessPolicy}, the rule of {@code PatientChartAccess}).
 *       Anyone else answers 404, exactly as an unknown id does.</li>
 *   <li><b>{@code staffId}</b> and every {@code ...StaffId}: a staff member of
 *       the acting hospital, or the caller's own staff record. Anyone else
 *       answers 404, as an unknown id does.</li>
 * </ul>
 *
 * <p>Left to the handler: a verified super-admin (who may name any hospital);
 * a patient-only caller (bounded by ownership of the record, not by a
 * hospital); a caller acting at a provider facility (a pharmacy or laboratory
 * reaches a patient through the prescription or the order it received); a
 * request with no tenant context (a partner API key, a test without the filter
 * chain); a handler marked {@link HospitalScopeExempt} (its hospital) or
 * {@link RequestBodyScopeExempt} (the fields it names).
 *
 * <p>The body is walked through its nested request objects and lists.
 * {@code RequestBodyScopeCoverageTest} holds every handler to this.
 *
 * <p>Dependencies are providers: this advice is in every {@code @WebMvcTest}
 * slice, where none of them exists and no tenant context is set.
 */
@ControllerAdvice
public class RequestBodyScopeAdvice extends RequestBodyAdviceAdapter {

    public static final String HOSPITAL_ID = "hospitalId";
    public static final String PATIENT_ID = "patientId";
    public static final String STAFF_ID = "staffId";
    public static final String STAFF_ID_SUFFIX = "StaffId";

    /** The request types walked: the application's own payload classes. */
    public static final String PAYLOAD_PACKAGE = "com.example.hms.payload";

    private static final String PATIENT_NOT_FOUND = "patient.notFound";
    private static final String STAFF_NOT_FOUND = "staff.notFound";
    private static final int MAX_DEPTH = 4;

    private static final Map<Class<?>, List<Method>> ACCESSORS = new ConcurrentHashMap<>();

    private final ObjectProvider<ActingScopeResolver> resolverProvider;
    private final ObjectProvider<UserAccountAccess> accountAccessProvider;
    private final ObjectProvider<PatientHospitalRegistrationRepository> registrationProvider;
    private final ObjectProvider<RecordAccessPolicy> recordAccessProvider;
    private final ObjectProvider<StaffRepository> staffProvider;

    public RequestBodyScopeAdvice(ObjectProvider<ActingScopeResolver> resolverProvider,
                                  ObjectProvider<UserAccountAccess> accountAccessProvider,
                                  ObjectProvider<PatientHospitalRegistrationRepository> registrationProvider,
                                  ObjectProvider<RecordAccessPolicy> recordAccessProvider,
                                  ObjectProvider<StaffRepository> staffProvider) {
        this.resolverProvider = resolverProvider;
        this.accountAccessProvider = accountAccessProvider;
        this.registrationProvider = registrationProvider;
        this.recordAccessProvider = recordAccessProvider;
        this.staffProvider = staffProvider;
    }

    @Override
    public boolean supports(@NonNull MethodParameter methodParameter, @NonNull Type targetType,
                            @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    @NonNull
    public Object afterBodyRead(@NonNull Object body, @NonNull HttpInputMessage inputMessage,
                                @NonNull MethodParameter parameter, @NonNull Type targetType,
                                @NonNull Class<? extends HttpMessageConverter<?>> converterType) {
        HospitalContext tenant = HospitalContextHolder.getContext().orElse(null);
        ActingScopeResolver resolver = resolverProvider.getIfAvailable();
        if (tenant == null || resolver == null || resolver.isVerifiedSuperAdmin()
            || ActingScopeResolver.scopeOf(tenant) instanceof ActingScope.PatientOwned) {
            return body;
        }
        NamedIds named = new NamedIds();
        collect(body, named, 0);
        if (named.isEmpty()) {
            return body;
        }
        Method handler = parameter.getMethod();
        if (!named.hospitalIds.isEmpty() && !isHospitalExempt(handler, parameter.getContainingClass())) {
            for (UUID hospitalId : named.hospitalIds) {
                actAt(resolver, hospitalId);
            }
        }
        // Read again: the body's hospital may just have become the acting one.
        HospitalContext acting = HospitalContextHolder.getContextOrEmpty();
        Set<UUID> hospitals = actingHospitals(acting);
        if (hospitals.isEmpty() || actsAtAProviderFacility(acting, hospitals)) {
            return body;
        }
        Set<String> lifted = liftedFields(handler);
        if (!lifted.contains(PATIENT_ID)) {
            for (UUID patientId : named.patientIds) {
                requirePatientInReach(patientId, acting, hospitals);
            }
        }
        for (Map.Entry<String, UUID> staff : named.staffIds) {
            if (!lifted.contains(staff.getKey())) {
                requireStaffOfActingHospital(staff.getValue(), acting, hospitals);
            }
        }
        return body;
    }

    /* ---------------------------------------------------------------------
       The hospital
       --------------------------------------------------------------------- */

    private void actAt(ActingScopeResolver resolver, UUID hospitalId) {
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        if (hospitalId.equals(ActingScopeResolver.pinnedHospitalIdOf(context))) {
            return;
        }
        if (!worksAt(context, hospitalId)) {
            // A hospital of the organisation an organisation admin administers passes
            // un-narrowed, before narrowTo: it is not a refusal, so none is audited.
            if (insideOrganisationScope(hospitalId)) {
                return;
            }
            // Not held: narrowTo audits the refusal and leaves the scope as it was.
            if (resolver.narrowTo(hospitalId) instanceof ActingScope.Refused(ActingScope.Reason reason)) {
                throw new HospitalScopeRefusedException(reason, ActingScopeResolver.refusalMessage(reason), hospitalId);
            }
            return;
        }
        if (!HospitalContextHolder.isSealed() && !HospitalContextHolder.isNarrowed()) {
            resolver.narrowTo(hospitalId);
        }
        // Read or narrowed already, to another hospital the caller holds: the handler's
        // own resolveHospitalScope and its service decide, as they did before.
    }

    /**
     * A hospital the caller may act at on this request: one they hold a role at,
     * and outside the patient portal not one where they are only a
     * patient ({@link HospitalContextRequestOverrides#actsOnlyAsPatientAt}).
     */
    private static boolean worksAt(HospitalContext context, UUID hospitalId) {
        return context.getPermittedHospitalIds().contains(hospitalId)
            && !HospitalContextRequestOverrides.actsOnlyAsPatientAt(context, hospitalId);
    }

    private boolean insideOrganisationScope(UUID hospitalId) {
        UserAccountAccess access = accountAccessProvider.getIfAvailable();
        return access != null && access.organisationHospitalIds().contains(hospitalId);
    }

    public static boolean isHospitalExempt(Method handler, Class<?> controller) {
        return handler != null && (AnnotatedElementUtils.hasAnnotation(handler, HospitalScopeExempt.class)
            || AnnotatedElementUtils.hasAnnotation(controller, HospitalScopeExempt.class));
    }

    public static Set<String> liftedFields(AnnotatedElement handler) {
        RequestBodyScopeExempt exempt = handler == null ? null
            : AnnotatedElementUtils.findMergedAnnotation(handler, RequestBodyScopeExempt.class);
        return exempt == null ? Set.of() : Set.of(exempt.fields());
    }

    /** The pinned hospital, else (several held, none named) every hospital the caller may act at. */
    private static Set<UUID> actingHospitals(HospitalContext context) {
        UUID pinned = ActingScopeResolver.pinnedHospitalIdOf(context);
        if (pinned != null) {
            return Set.of(pinned);
        }
        if (context.getPermittedHospitalIds() == null) {
            return Set.of();
        }
        Set<UUID> held = new LinkedHashSet<>();
        for (UUID hospitalId : context.getPermittedHospitalIds()) {
            if (worksAt(context, hospitalId)) {
                held.add(hospitalId);
            }
        }
        return held;
    }

    private static boolean actsAtAProviderFacility(HospitalContext context, Set<UUID> hospitals) {
        Map<UUID, FacilityType> types = context.getHospitalFacilityTypes();
        if (types == null) {
            return false;
        }
        for (UUID hospitalId : hospitals) {
            FacilityType type = types.get(hospitalId);
            if (type != null && type.isProvider()) {
                return true;
            }
        }
        return false;
    }

    /* ---------------------------------------------------------------------
       The patient and the staff member
       --------------------------------------------------------------------- */

    private void requirePatientInReach(UUID patientId, HospitalContext context, Set<UUID> hospitals) {
        PatientHospitalRegistrationRepository registrations = registrationProvider.getIfAvailable();
        if (registrations == null || registrations.existsByPatientIdAndHospitalIdIn(patientId, hospitals)) {
            return;
        }
        RecordAccessPolicy policy = recordAccessProvider.getIfAvailable();
        if (policy != null) {
            for (UUID hospitalId : hospitals) {
                if (policy.decide(context.getPrincipalUserId(), patientId, hospitalId).permitted()) {
                    return;
                }
            }
        }
        throw new ResourceNotFoundException(PATIENT_NOT_FOUND, patientId);
    }

    private void requireStaffOfActingHospital(UUID staffId, HospitalContext context, Set<UUID> hospitals) {
        StaffRepository staff = staffProvider.getIfAvailable();
        if (staff == null) {
            return;
        }
        for (Object[] row : staff.findHospitalIdAndUserIdById(staffId)) {
            boolean here = row[0] instanceof UUID hospitalId && hospitals.contains(hospitalId);
            boolean self = row[1] instanceof UUID userId && userId.equals(context.getPrincipalUserId());
            if (here || self) {
                return;
            }
        }
        throw new ResourceNotFoundException(STAFF_NOT_FOUND, staffId);
    }

    /* ---------------------------------------------------------------------
       Reading the body
       --------------------------------------------------------------------- */

    /** The ids a body names, through its nested request objects and lists. */
    static final class NamedIds {
        final List<UUID> hospitalIds = new ArrayList<>();
        final List<UUID> patientIds = new ArrayList<>();
        final List<Map.Entry<String, UUID>> staffIds = new ArrayList<>();

        boolean isEmpty() {
            return hospitalIds.isEmpty() && patientIds.isEmpty() && staffIds.isEmpty();
        }
    }

    static void collect(Object body, NamedIds named, int depth) {
        if (body == null || depth > MAX_DEPTH) {
            return;
        }
        if (body instanceof Collection<?> many) {
            for (Object element : many) {
                collect(element, named, depth + 1);
            }
            return;
        }
        if (!isPayload(body.getClass())) {
            return;
        }
        for (Method accessor : ACCESSORS.computeIfAbsent(body.getClass(), RequestBodyScopeAdvice::accessors)) {
            Object value;
            try {
                value = accessor.invoke(body);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                // A derived getter that cannot answer on a half-filled body names no id.
                continue;
            }
            if (value instanceof UUID id) {
                String field = fieldName(accessor);
                if (HOSPITAL_ID.equals(field)) {
                    named.hospitalIds.add(id);
                } else if (PATIENT_ID.equals(field)) {
                    named.patientIds.add(id);
                } else {
                    named.staffIds.add(Map.entry(field, id));
                }
            } else {
                collect(value, named, depth + 1);
            }
        }
    }

    public static boolean isPayload(Class<?> type) {
        return type.getName().startsWith(PAYLOAD_PACKAGE) && !type.isEnum();
    }

    /** True for the body fields this advice measures. */
    public static boolean isScopedField(String field) {
        return HOSPITAL_ID.equals(field) || PATIENT_ID.equals(field) || isStaffField(field);
    }

    public static boolean isStaffField(String field) {
        return STAFF_ID.equals(field) || field.endsWith(STAFF_ID_SUFFIX);
    }

    /** The accessors worth calling on a request type: its scoped ids, its nested request objects, its lists. */
    public static List<Method> accessors(Class<?> type) {
        List<Method> found = new ArrayList<>();
        for (Method method : type.getMethods()) {
            if (method.getParameterCount() != 0 || Modifier.isStatic(method.getModifiers())
                || method.getDeclaringClass() == Object.class) {
                continue;
            }
            String field = fieldName(method);
            if (field == null) {
                continue;
            }
            Class<?> returned = method.getReturnType();
            boolean wanted = returned == UUID.class
                ? isScopedField(field)
                : Collection.class.isAssignableFrom(returned) || isPayload(returned);
            if (wanted) {
                found.add(method);
            }
        }
        return List.copyOf(found);
    }

    /** {@code getHospitalId()} and a record's {@code hospitalId()} both read as {@code hospitalId}. */
    public static String fieldName(Method accessor) {
        String name = accessor.getName();
        if (name.startsWith("get") && name.length() > 3) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        return accessor.getDeclaringClass().isRecord() ? name : null;
    }
}
