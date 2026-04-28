package com.example.hms.cdshooks;

import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookRequest;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsHookResponse;
import com.example.hms.cdshooks.dto.CdsHookDtos.CdsServiceCatalog;
import com.example.hms.cdshooks.service.CdsHookRegistry;
import com.example.hms.cdshooks.service.CdsHookService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <a href="https://cds-hooks.hl7.org/1.0/">CDS Hooks 1.0</a> service endpoints.
 *
 * <p>Discovery is intentionally public (per the spec — clients enumerate
 * services before authenticating). Invocations require the same bearer
 * authentication as the rest of the API; tenant scope is preserved by the
 * underlying repositories.
 */
@RestController
@RequestMapping(value = "/cds-services", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CDS Hooks", description = "Clinical Decision Support hook services (HL7 CDS Hooks 1.0)")
public class CdsHooksController {

    private final CdsHookRegistry registry;

    public CdsHooksController(CdsHookRegistry registry) {
        this.registry = registry;
    }

    @GetMapping
    @Operation(summary = "Discovery — list available CDS services")
    public ResponseEntity<CdsServiceCatalog> discover() {
        return ResponseEntity.ok(new CdsServiceCatalog(registry.descriptors()));
    }

    @PostMapping(value = "/{serviceId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Invoke a CDS service for the given hook context")
    public ResponseEntity<CdsHookResponse> invoke(
        @PathVariable("serviceId") String serviceId,
        @RequestBody CdsHookRequest request
    ) {
        CdsHookService service = registry.findById(serviceId).orElse(null);
        if (service == null) return ResponseEntity.notFound().build();

        // Per spec, the service ignores requests for hooks it does not advertise.
        if (request.hook() != null
            && !request.hook().equals(service.descriptor().hook())) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(service.evaluate(request));
    }
}
