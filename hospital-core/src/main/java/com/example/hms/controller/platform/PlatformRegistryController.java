package com.example.hms.controller.platform;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.DepartmentPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.PlatformServiceLinkRequestDTO;
import com.example.hms.payload.dto.PlatformServiceLinkUpdateRequestDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceResponseDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import com.example.hms.payload.dto.platform.PlatformIntegrationDescriptorDTO;
import com.example.hms.mapper.PlatformIntegrationDescriptorMapper;
import com.example.hms.security.audit.WriteAudited;
import com.example.hms.service.platform.PlatformRegistryService;
import com.example.hms.service.platform.discovery.PlatformServiceRegistry;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;

/**
 * Platform registry: the integration catalog, organization services and their
 * hospital / department links.
 *
 * <p>Every write is SUPER_ADMIN only. Links used to admit HOSPITAL_ADMIN with
 * no check that the hospital was theirs, so one hospital's administrator could
 * link, disable or delete another tenant's integration; the only screen that
 * drives these endpoints is the super-admin Platform Management page.
 *
 * <p>Reads that are not held to one hospital are SUPER_ADMIN only too: an
 * organization's services and a department's links were readable by any
 * HOSPITAL_ADMIN by id. HOSPITAL_ADMIN keeps the catalog and the per-hospital
 * link list, which HospitalIdNarrowingInterceptor holds to their hospitals.
 */
@RestController
@WriteAudited(skip = true, reason = "service emits PLATFORM_REGISTRY_UPDATED for every registry write")
@RequestMapping("/platform")
@RequiredArgsConstructor
public class PlatformRegistryController {

    private final PlatformRegistryService platformRegistryService;
    private final PlatformServiceRegistry platformServiceRegistry;
    private final PlatformIntegrationDescriptorMapper integrationDescriptorMapper;

    @GetMapping("/catalog")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "')")
    public ResponseEntity<List<PlatformIntegrationDescriptorDTO>> listIntegrationCatalog(
        @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled,
        Locale locale
    ) {
        List<IntegrationDescriptor> descriptors = platformServiceRegistry.listIntegrations(includeDisabled, locale);
        List<PlatformIntegrationDescriptorDTO> response = integrationDescriptorMapper.toDtoList(descriptors);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/catalog/{serviceType}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "')")
    public ResponseEntity<PlatformIntegrationDescriptorDTO> getIntegrationDescriptor(
        @PathVariable PlatformServiceType serviceType,
        Locale locale
    ) {
        IntegrationDescriptor descriptor = platformServiceRegistry.findIntegration(serviceType, locale)
            .orElseThrow(() -> new ResourceNotFoundException("integration.descriptor.notFound", serviceType));
        return ResponseEntity.ok(integrationDescriptorMapper.toDto(descriptor));
    }

    @PostMapping("/organizations/{organizationId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<PlatformServiceResponseDTO> registerOrganizationService(@PathVariable UUID organizationId,
                                                                                   @Valid @RequestBody PlatformServiceRegistrationRequestDTO request,
                                                                                   Locale locale) {
        PlatformServiceResponseDTO response = platformRegistryService.registerOrganizationService(organizationId, request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/organizations/{organizationId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<PlatformServiceResponseDTO> updateOrganizationService(@PathVariable UUID organizationId,
                                                                                @PathVariable UUID serviceId,
                                                                                @Valid @RequestBody PlatformServiceUpdateRequestDTO request,
                                                                                Locale locale) {
        PlatformServiceResponseDTO response = platformRegistryService.updateOrganizationService(organizationId, serviceId, request, locale);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/{organizationId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<PlatformServiceResponseDTO> getOrganizationService(@PathVariable UUID organizationId,
                                                                             @PathVariable UUID serviceId,
                                                                             Locale locale) {
        PlatformServiceResponseDTO response = platformRegistryService.getOrganizationService(organizationId, serviceId, locale);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/{organizationId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<List<PlatformServiceResponseDTO>> listOrganizationServices(@PathVariable UUID organizationId,
                                                                                      @RequestParam(name = "status", required = false) PlatformServiceStatus status,
                                                                                      Locale locale) {
        List<PlatformServiceResponseDTO> responses = platformRegistryService.listOrganizationServices(organizationId, status, locale);
        return ResponseEntity.ok(responses);
    }

    /**
     * D3: every hospital link of one service, across the organization's
     * hospitals, in one call. SUPER_ADMIN only — it spans hospitals, and the
     * per-hospital list is the read a hospital administrator has.
     */
    @GetMapping("/organizations/{organizationId}/services/{serviceId}/hospital-links")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<List<HospitalPlatformServiceLinkResponseDTO>> listServiceHospitalLinks(@PathVariable UUID organizationId,
                                                                                                 @PathVariable UUID serviceId,
                                                                                                 Locale locale) {
        return ResponseEntity.ok(platformRegistryService.listServiceHospitalLinks(organizationId, serviceId, locale));
    }

    @PostMapping("/hospitals/{hospitalId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<HospitalPlatformServiceLinkResponseDTO> linkHospitalToService(@PathVariable UUID hospitalId,
                                                                                         @PathVariable UUID serviceId,
                                                                                         @Valid @RequestBody(required = false) PlatformServiceLinkRequestDTO request,
                                                                                         Locale locale) {
        HospitalPlatformServiceLinkResponseDTO response = platformRegistryService.linkHospitalToService(hospitalId, serviceId, request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /** D2: enable or disable an existing link; POST would answer 409 for a link that already exists. */
    @PutMapping("/hospitals/{hospitalId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<HospitalPlatformServiceLinkResponseDTO> updateHospitalServiceLink(@PathVariable UUID hospitalId,
                                                                                            @PathVariable UUID serviceId,
                                                                                            @Valid @RequestBody PlatformServiceLinkUpdateRequestDTO request,
                                                                                            Locale locale) {
        return ResponseEntity.ok(platformRegistryService.updateHospitalServiceLink(hospitalId, serviceId, request, locale));
    }

    @DeleteMapping("/hospitals/{hospitalId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<Void> unlinkHospitalFromService(@PathVariable UUID hospitalId,
                                                           @PathVariable UUID serviceId,
                                                           Locale locale) {
        platformRegistryService.unlinkHospitalFromService(hospitalId, serviceId, locale);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hospitals/{hospitalId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "')")
    public ResponseEntity<List<HospitalPlatformServiceLinkResponseDTO>> listHospitalServiceLinks(@PathVariable UUID hospitalId,
                                                                                                  Locale locale) {
        List<HospitalPlatformServiceLinkResponseDTO> responses = platformRegistryService.listHospitalServiceLinks(hospitalId, locale);
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/departments/{departmentId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<DepartmentPlatformServiceLinkResponseDTO> linkDepartmentToService(@PathVariable UUID departmentId,
                                                                                             @PathVariable UUID serviceId,
                                                                                             @Valid @RequestBody(required = false) PlatformServiceLinkRequestDTO request,
                                                                                             Locale locale) {
        DepartmentPlatformServiceLinkResponseDTO response = platformRegistryService.linkDepartmentToService(departmentId, serviceId, request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/departments/{departmentId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<Void> unlinkDepartmentFromService(@PathVariable UUID departmentId,
                                                             @PathVariable UUID serviceId,
                                                             Locale locale) {
        platformRegistryService.unlinkDepartmentFromService(departmentId, serviceId, locale);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/departments/{departmentId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "')")
    public ResponseEntity<List<DepartmentPlatformServiceLinkResponseDTO>> listDepartmentServiceLinks(@PathVariable UUID departmentId,
                                                                                                      Locale locale) {
        List<DepartmentPlatformServiceLinkResponseDTO> responses = platformRegistryService.listDepartmentServiceLinks(departmentId, locale);
        return ResponseEntity.ok(responses);
    }
}
