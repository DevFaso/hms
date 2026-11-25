package com.example.hms.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.interceptor.api.Hook;
import ca.uhn.fhir.interceptor.api.Interceptor;
import ca.uhn.fhir.interceptor.api.Pointcut;
import ca.uhn.fhir.rest.api.RestOperationTypeEnum;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.api.server.ResponseDetails;
import ca.uhn.fhir.rest.server.exceptions.BaseServerResponseException;
import ca.uhn.fhir.rest.server.exceptions.ForbiddenOperationException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.security.chart.RestrictedChartGate;
import com.example.hms.utility.MessageUtil;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * QA D3863: the FHIR servlet keeps a restricted chart (E8 #54) the way the
 * REST API does, through the same {@link RestrictedChartGate}.
 *
 * <ul>
 *   <li>{@code Patient/{id}/$everything} for a blocked patient is refused
 *       before the operation runs;</li>
 *   <li>a resource that references a blocked patient (an Encounter, an
 *       Observation, a Condition...) is withheld from a search bundle, and
 *       refused when it is the answer itself. The Patient resource stays: it
 *       is the identity, as the patient page header is.</li>
 * </ul>
 *
 * <p>Ordered just after {@link FhirTenantBoundaryInterceptor}, so it only ever
 * sees what the tenant boundary already let through.
 */
@Component
@Interceptor
public class FhirRestrictedChartInterceptor {

    static final int ORDER = FhirTenantBoundaryInterceptor.ORDER + 1;
    private static final String PATIENT = "Patient";

    private final FhirContext fhirContext;
    private final ObjectProvider<RestrictedChartGate> gateProvider;

    public FhirRestrictedChartInterceptor(FhirContext fhirContext, ObjectProvider<RestrictedChartGate> gateProvider) {
        this.fhirContext = fhirContext;
        this.gateProvider = gateProvider;
    }

    @Hook(value = Pointcut.SERVER_INCOMING_REQUEST_PRE_HANDLED, order = ORDER)
    public void gateEverything(RequestDetails request, RestOperationTypeEnum operation) {
        if (operation == RestOperationTypeEnum.METADATA || !"$everything".equals(request.getOperation())
            || !PATIENT.equals(request.getResourceName())) {
            return;
        }
        IIdType named = request.getId();
        UUID patientId = named != null && named.hasIdPart() ? parse(named.getIdPart()) : null;
        RestrictedChartGate gate = gateProvider.getIfAvailable();
        if (gate != null && patientId != null && gate.isBlocked(patientId)) {
            throw toFhir(gate.refusal(patientId), named);
        }
    }

    @Hook(value = Pointcut.SERVER_OUTGOING_RESPONSE, order = ORDER)
    public void withholdRestricted(RequestDetails request, ResponseDetails response) {
        IBaseResource resource = response.getResponseResource();
        RestrictedChartGate gate = gateProvider.getIfAvailable();
        if (resource == null || gate == null || !gate.isSubject() || !gate.anyRestricted()) {
            return;
        }
        if (resource instanceof Bundle bundle) {
            int before = bundle.getEntry().size();
            bundle.getEntry().removeIf(entry -> entry.getResource() != null
                && !gate.blockedAmong(patientsReferenced(entry.getResource())).isEmpty());
            int withheld = before - bundle.getEntry().size();
            if (withheld > 0 && bundle.hasTotal()) {
                bundle.setTotal(Math.max(0, bundle.getTotal() - withheld));
            }
            return;
        }
        Set<UUID> blocked = gate.blockedAmong(patientsReferenced(resource));
        if (!blocked.isEmpty()) {
            throw toFhir(gate.refusal(blocked.iterator().next()), request.getId());
        }
    }

    /** The patients a non-Patient resource is about: every populated {@code Patient/<uuid>} reference. */
    private Set<UUID> patientsReferenced(IBaseResource resource) {
        Set<UUID> patients = new HashSet<>();
        if (PATIENT.equals(fhirContext.getResourceType(resource))) {
            return patients;
        }
        for (Reference reference : fhirContext.newTerser().getAllPopulatedChildElementsOfType(resource,
                Reference.class)) {
            IIdType target = reference.getReferenceElement();
            if (target != null && PATIENT.equals(target.getResourceType()) && target.hasIdPart()) {
                UUID id = parse(target.getIdPart());
                if (id != null) {
                    patients.add(id);
                }
            }
        }
        return patients;
    }

    private static BaseServerResponseException toFhir(RuntimeException refusal, IIdType named) {
        if (refusal instanceof ChartRestrictedException restricted) {
            String message = MessageUtil.resolve(restricted.getMessage());
            OperationOutcome outcome = new OperationOutcome();
            outcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.FORBIDDEN)
                .setDiagnostics(message);
            return new ForbiddenOperationException(message, outcome);
        }
        return named != null ? new ResourceNotFoundException(named) : new ResourceNotFoundException((IIdType) null);
    }

    private static UUID parse(String idPart) {
        try {
            return idPart == null ? null : UUID.fromString(idPart);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
