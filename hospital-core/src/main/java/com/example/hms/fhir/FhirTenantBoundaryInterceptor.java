package com.example.hms.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The tenant boundary of the FHIR servlet: every read and search is bounded
 * to the caller's hospital HERE, once, for every resource provider — the
 * ones that exist and any added later. Providers may scope their own queries
 * too (most do); none is trusted to.
 *
 * <p>Three steps, in request order:
 * <ol>
 *   <li><b>Bind.</b> Every request except {@code metadata} must carry a
 *       hospital taken from the authenticated principal
 *       ({@link FhirTenantBoundary#boundHospital}); without one the answer is
 *       403 before any provider runs. A super-admin pins one with
 *       {@code X-Hospital-Id}; anyone else may only pick one they hold. The
 *       caller must also hold, AT that hospital, a role this request needs
 *       ({@link #requiredRoleCodes}), not merely somewhere: Spring Security's
 *       authorities are the union across hospitals, so a doctor at A who is a
 *       receptionist at B would otherwise read B's whole chart.</li>
 *   <li><b>Gate the named resource.</b> A request that names a resource id
 *       ({@code GET Encounter/{id}}, {@code PUT}, {@code Patient/{id}/$everything})
 *       is answered 404 unless that resource is visible at the bound hospital
 *       — with {@code new ResourceNotFoundException(request.getId())}, the
 *       exception every provider throws for an id it does not hold. The
 *       provider is never called, so a row at another hospital, a row that
 *       does not exist and an id that does not parse answer identically, in
 *       status and body: the question asked is only "is it visible here".</li>
 *   <li><b>Filter what leaves.</b> Every resource in a response must be
 *       visible at the bound hospital. A search bundle loses the entries that
 *       are not, and its {@code total} is corrected; a single resource that
 *       is not answers 404. To keep {@code total} exact, {@code _count} and
 *       {@code _offset} are removed from searches before the provider runs,
 *       so the bundle holds the whole (provider-capped) result — the
 *       documented "no paging" contract of this server.</li>
 * </ol>
 *
 * <p>{@code Patient/{id}/$everything} is the one exception to step 3, named
 * in {@link #SELF_SCOPED_OPERATIONS}: its sections follow the patient across
 * hospitals by the E8 treatment-relationship policy ({@code RecordAccessPolicy}),
 * which a per-row "same hospital" filter would silently undo. Step 2 still
 * holds it to a patient registered at the bound hospital — the same gate the
 * service applies.
 */
@Slf4j
@Component
@Interceptor
public class FhirTenantBoundaryInterceptor {

    /** Runs before every other outgoing hook — ResponseHighlighterInterceptor streams HTML at 10000. */
    static final int ORDER = -1000;

    /** Where the bound hospital travels from step 1 to step 3 within one request. */
    static final String BOUND_HOSPITAL = FhirTenantBoundaryInterceptor.class.getName() + ".boundHospital";

    /** The {@code Type/id} step 2 found visible, so step 3 need not ask again for the same resource. */
    static final String GATED_ID = FhirTenantBoundaryInterceptor.class.getName() + ".gatedId";

    /** Operations whose response the operation itself scopes (see the class comment). */
    static final Set<String> SELF_SCOPED_OPERATIONS = Set.of("$everything");

    /** Operations that only read. Anything not listed needs a writer's role. */
    private static final Set<RestOperationTypeEnum> READ_OPERATIONS = Set.of(
        RestOperationTypeEnum.READ, RestOperationTypeEnum.VREAD,
        RestOperationTypeEnum.SEARCH_TYPE, RestOperationTypeEnum.SEARCH_SYSTEM,
        RestOperationTypeEnum.HISTORY_INSTANCE, RestOperationTypeEnum.HISTORY_TYPE,
        RestOperationTypeEnum.HISTORY_SYSTEM, RestOperationTypeEnum.GET_PAGE);

    /** Resource types that carry no patient data and pass step 3 untouched. */
    private static final Set<String> ENVELOPE_TYPES = Set.of("OperationOutcome", "CapabilityStatement");

    private final FhirContext fhirContext;
    private final FhirTenantBoundary boundary;

    public FhirTenantBoundaryInterceptor(FhirContext fhirContext, FhirTenantBoundary boundary) {
        this.fhirContext = fhirContext;
        this.boundary = boundary;
    }

    @Hook(value = Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED, order = ORDER)
    public void bindAndGate(RequestDetails request, RestOperationTypeEnum operation) {
        if (operation == RestOperationTypeEnum.METADATA) {
            return;
        }
        HospitalContext context = HospitalContextHolder.getContextOrEmpty();
        UUID hospitalId = FhirTenantBoundary.boundHospital(context);
        if (hospitalId == null) {
            throw noHospitalScope();
        }
        if (!boundary.holdsRoleAt(context, SecurityContextHolder.getContext().getAuthentication(), hospitalId,
            requiredRoleCodes(request, operation))) {
            throw noRoleHere();
        }
        request.getUserData().put(BOUND_HOSPITAL, hospitalId);

        if (operation == RestOperationTypeEnum.SEARCH_TYPE || operation == RestOperationTypeEnum.SEARCH_SYSTEM) {
            request.removeParameter(Constants.PARAM_COUNT);
            request.removeParameter(Constants.PARAM_OFFSET);
        }

        IIdType named = request.getId();
        if (named != null && named.hasIdPart()) {
            if (!boundary.isVisible(request.getResourceName(), named.getIdPart(), hospitalId)) {
                throw new ResourceNotFoundException(named);
            }
            request.getUserData().put(GATED_ID, request.getResourceName() + "/" + named.getIdPart());
        }
    }

    @Hook(value = Pointcut.SERVER_OUTGOING_RESPONSE, order = ORDER)
    public void filterOutgoing(RequestDetails request, ResponseDetails response) {
        IBaseResource resource = response.getResponseResource();
        String operationName = request.getOperation();
        if (resource == null || request.getRestOperationType() == RestOperationTypeEnum.METADATA
            || (operationName != null && SELF_SCOPED_OPERATIONS.contains(operationName))) {
            return;
        }
        UUID hospitalId = request.getUserData().get(BOUND_HOSPITAL) instanceof UUID bound ? bound : null;
        if (resource instanceof Bundle bundle) {
            filterBundle(bundle, hospitalId);
            return;
        }
        if (ENVELOPE_TYPES.contains(fhirContext.getResourceType(resource))) {
            return;
        }
        if (!alreadyGated(request, resource) && !isVisible(resource, hospitalId)) {
            log.debug("[FHIR] withheld an out-of-scope {} from a {} response",
                fhirContext.getResourceType(resource), request.getRestOperationType());
            IIdType named = request.getId();
            // With no id in the request there is nothing to echo; naming the
            // withheld resource would itself disclose it.
            throw named != null ? new ResourceNotFoundException(named) : new ResourceNotFoundException((IIdType) null);
        }
    }

    /**
     * The roles the caller must hold AT the bound hospital for this request:
     * {@code $export} the hospital admin; a read (read, search, history,
     * paging, {@code $everything}) the chart readers; anything else, the
     * writes, the charting clinicians.
     */
    static Set<String> requiredRoleCodes(RequestDetails request, RestOperationTypeEnum operation) {
        String operationName = request.getOperation();
        if ("$export".equals(operationName)) {
            return FhirTenantBoundary.EXPORT_ROLE_CODES;
        }
        if ((operationName != null && SELF_SCOPED_OPERATIONS.contains(operationName))
            || (operation != null && READ_OPERATIONS.contains(operation))) {
            return FhirTenantBoundary.READ_ROLE_CODES;
        }
        return FhirTenantBoundary.WRITE_ROLE_CODES;
    }

    private void filterBundle(Bundle bundle, UUID hospitalId) {
        Map<String, List<String>> idsByType = new HashMap<>();
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            IBaseResource resource = entry.getResource();
            if (resource != null && resource.getIdElement() != null && resource.getIdElement().hasIdPart()) {
                idsByType.computeIfAbsent(fhirContext.getResourceType(resource), k -> new ArrayList<>())
                    .add(resource.getIdElement().getIdPart());
            }
        }
        Map<String, Set<String>> visibleByType = new HashMap<>();
        if (hospitalId != null) {
            idsByType.forEach((type, ids) -> visibleByType.put(type,
                ENVELOPE_TYPES.contains(type) ? Set.copyOf(ids) : boundary.visibleIdParts(type, ids, hospitalId)));
        }
        int before = bundle.getEntry().size();
        bundle.getEntry().removeIf(entry -> !entryVisible(entry, visibleByType));
        int withheld = before - bundle.getEntry().size();
        if (withheld > 0) {
            log.debug("[FHIR] withheld {} out-of-scope bundle entries", withheld);
            if (bundle.hasTotal()) {
                bundle.setTotal(Math.max(0, bundle.getTotal() - withheld));
            }
        }
    }

    private boolean entryVisible(Bundle.BundleEntryComponent entry, Map<String, Set<String>> visibleByType) {
        IBaseResource resource = entry.getResource();
        if (resource == null || resource.getIdElement() == null || !resource.getIdElement().hasIdPart()) {
            // An entry the boundary cannot identify cannot be vouched for.
            return false;
        }
        Set<String> visible = visibleByType.get(fhirContext.getResourceType(resource));
        return visible != null && visible.contains(resource.getIdElement().getIdPart());
    }

    /** The response is the very resource step 2 already found visible: no second lookup. */
    private boolean alreadyGated(RequestDetails request, IBaseResource resource) {
        Object gated = request.getUserData().get(GATED_ID);
        return gated != null && resource.getIdElement() != null && resource.getIdElement().hasIdPart()
            && gated.equals(fhirContext.getResourceType(resource) + "/" + resource.getIdElement().getIdPart());
    }

    private boolean isVisible(IBaseResource resource, UUID hospitalId) {
        return hospitalId != null
            && resource.getIdElement() != null
            && resource.getIdElement().hasIdPart()
            && boundary.isVisible(fhirContext.getResourceType(resource),
                resource.getIdElement().getIdPart(), hospitalId);
    }

    private static ForbiddenOperationException noRoleHere() {
        return forbidden("The caller holds no role at the bound hospital that may make this FHIR request.");
    }

    private static ForbiddenOperationException noHospitalScope() {
        return forbidden("FHIR requests require an active hospital scope held by the caller; "
            + "a super-admin supplies X-Hospital-Id, anyone else authenticates as a hospital-scoped user.");
    }

    private static ForbiddenOperationException forbidden(String message) {
        OperationOutcome outcome = new OperationOutcome();
        outcome.addIssue()
            .setSeverity(OperationOutcome.IssueSeverity.ERROR)
            .setCode(OperationOutcome.IssueType.FORBIDDEN)
            .setDiagnostics(message);
        return new ForbiddenOperationException(message, outcome);
    }
}
