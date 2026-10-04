package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceCatalog;
import com.example.hms.cdshooks.service.CdsHookContext;
import com.example.hms.cdshooks.service.CdsHookRegistry;
import com.example.hms.cdshooks.service.CdsHookService;
import com.example.hms.controller.CdsAcknowledgementController;
import com.example.hms.controller.support.ControllerAuthUtils;
import com.example.hms.exception.ChartRestrictedException;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.service.support.PatientChartAccess;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * <a href="https://cds-hooks.hl7.org/1.0/">CDS Hooks 1.0</a> service endpoints.
 *
 * <p>Discovery is intentionally public (per the spec — clients enumerate
 * services before authenticating). It lists service ids, hooks, titles and
 * prefetch templates only; no patient data.
 *
 * <p>Invocation is not. Every service reads the chart of the patient that
 * {@code context.patientId} names — the patient-view summary returns their
 * active allergies and problem list verbatim, the order services test drafts
 * against their allergies and active prescriptions — and the services
 * themselves resolve that patient unscoped. So the controller decides, once
 * and before any service runs:
 * <ol>
 *   <li><b>who</b> may ask: the clinicians who act on a card, the same list
 *       that may acknowledge one ({@link CdsAcknowledgementController#CLINICIAN_ROLES});
 *       a patient, a receptionist or an administrator is refused with 403;</li>
 *   <li><b>about whom</b>: the patient must be readable at the caller's
 *       hospital under the chart rule every other chart read applies
 *       ({@link PatientChartAccess#require}: registration there, or a
 *       treatment relationship the record-access policy honours).</li>
 * </ol>
 * A patient the caller may not read — at another hospital, restricted, or
 * scope unresolved — answers exactly as an unknown patient does: {@code 200}
 * with no cards, which is also what every service already returns for an id
 * it cannot find. An empty card list is a valid CDS Hooks answer ("no
 * advice"), so a partner EHR sees no difference, and neither does a prober.
 */
@RestController
@RequestMapping(value = "/cds-services", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CDS Hooks", description = "Clinical Decision Support hook services (HL7 CDS Hooks 1.0)")
public class CdsHooksController {

    private final CdsHookRegistry registry;
    private final PatientChartAccess patientChartAccess;
    private final ControllerAuthUtils authUtils;

    public CdsHooksController(CdsHookRegistry registry,
                              PatientChartAccess patientChartAccess,
                              ControllerAuthUtils authUtils) {
        this.registry = registry;
        this.patientChartAccess = patientChartAccess;
        this.authUtils = authUtils;
    }

    @GetMapping
    @Operation(summary = "Discovery — list available CDS services")
    public ResponseEntity<CdsServiceCatalog> discover() {
        return ResponseEntity.ok(new CdsServiceCatalog(registry.descriptors()));
    }

    @PostMapping(value = "/{serviceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(CdsAcknowledgementController.CLINICIAN_ROLES)
    @Operation(summary = "Invoke a CDS service for the given hook context",
        description = "Clinical roles only. Returns no cards for a patient the caller cannot read "
            + "at their hospital, exactly as for an unknown patient.",
        security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<CdsHookResponse> invoke(
        @PathVariable("serviceId") String serviceId,
        @RequestBody CdsHookRequest request,
        Authentication auth
    ) {
        CdsHookService service = registry.findById(serviceId).orElse(null);
        if (service == null) return ResponseEntity.notFound().build();

        // Per spec, the service ignores requests for hooks it does not advertise.
        if (request.hook() != null
            && !request.hook().equals(service.descriptor().hook())) {
            return ResponseEntity.badRequest().build();
        }
        if (!callerMayReadContextPatient(request, auth)) {
            return ResponseEntity.ok(CdsHookResponse.empty());
        }
        return ResponseEntity.ok(service.evaluate(request));
    }

    /**
     * True only when {@code context.patientId} names a patient the caller may
     * read at their resolved hospital. A missing or unparseable id is false:
     * every service answers no cards for it anyway, and deciding it here means
     * a future service cannot forget to.
     */
    private boolean callerMayReadContextPatient(CdsHookRequest request, Authentication auth) {
        UUID patientId = CdsHookContext.requirePatientId(request);
        if (patientId == null) return false;
        authUtils.requireAuth(auth);
        UUID hospitalId = authUtils.resolveHospitalScope(auth, null, false);
        try {
            patientChartAccess.require(patientId, hospitalId);
            return true;
        } catch (ResourceNotFoundException | ChartRestrictedException refused) {
            // Unknown, foreign and restricted all read as "no advice": a
            // restricted chart is opened (break-the-glass) on the chart page,
            // not through a decision-support probe.
            return false;
        }
    }
}
