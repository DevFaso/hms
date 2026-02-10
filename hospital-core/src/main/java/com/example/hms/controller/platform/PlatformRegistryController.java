package com.example.hms.controller.platform;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.exception.ResourceNotFoundException;
import com.example.hms.payload.dto.DepartmentPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.PlatformServiceLinkRequestDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceResponseDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import com.example.hms.payload.dto.platform.PlatformIntegrationDescriptorDTO;
import com.example.hms.mapper.PlatformIntegrationDescriptorMapper;
import com.example.hms.service.platform.PlatformRegistryService;
import com.example.hms.service.platform.discovery.PlatformServiceRegistry;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.example.hms.config.SecurityConstants.ROLE_HOSPITAL_ADMIN;
import static com.example.hms.config.SecurityConstants.ROLE_IT_STAFF;
import static com.example.hms.config.SecurityConstants.ROLE_SUPER_ADMIN;

@RestController
@RequestMapping("/platform")
@RequiredArgsConstructor
@CrossOrigin(origins = "http://localhost:4200", maxAge = 3600)
public class PlatformRegistryController {

    private final PlatformRegistryService platformRegistryService;
    private final PlatformServiceRegistry platformServiceRegistry;
    private final PlatformIntegrationDescriptorMapper integrationDescriptorMapper;

    @GetMapping("/catalog")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<List<PlatformIntegrationDescriptorDTO>> listIntegrationCatalog(
        @RequestParam(name = "includeDisabled", defaultValue = "false") boolean includeDisabled,
        Locale locale
    ) {
        List<IntegrationDescriptor> descriptors = platformServiceRegistry.listIntegrations(includeDisabled, locale);
        List<PlatformIntegrationDescriptorDTO> response = integrationDescriptorMapper.toDtoList(descriptors);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/catalog/{serviceType}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<PlatformIntegrationDescriptorDTO> getIntegrationDescriptor(
        @PathVariable PlatformServiceType serviceType,
        Locale locale
    ) {
        IntegrationDescriptor descriptor = platformServiceRegistry.findIntegration(serviceType, locale)
            .orElseThrow(() -> new ResourceNotFoundException("Integration descriptor not found: " + serviceType));
        return ResponseEntity.ok(integrationDescriptorMapper.toDto(descriptor));
    }

    @PostMapping("/organizations/{organizationId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<PlatformServiceResponseDTO> registerOrganizationService(@PathVariable UUID organizationId,
                                                                                   @Valid @RequestBody PlatformServiceRegistrationRequestDTO request,
                                                                                   Locale locale) {
        PlatformServiceResponseDTO response = platformRegistryService.registerOrganizationService(organizationId, request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/organizations/{organizationId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<PlatformServiceResponseDTO> updateOrganizationService(@PathVariable UUID organizationId,
                                                                                @PathVariable UUID serviceId,
                                                                                @Valid @RequestBody PlatformServiceUpdateRequestDTO request,
                                                                                Locale locale) {
        PlatformServiceResponseDTO response = platformRegistryService.updateOrganizationService(organizationId, serviceId, request, locale);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/{organizationId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<PlatformServiceResponseDTO> getOrganizationService(@PathVariable UUID organizationId,
                                                                             @PathVariable UUID serviceId,
                                                                             Locale locale) {
        PlatformServiceResponseDTO response = platformRegistryService.getOrganizationService(organizationId, serviceId, locale);
        return ResponseEntity.ok(response);
    }

    @GetMapping("/organizations/{organizationId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<List<PlatformServiceResponseDTO>> listOrganizationServices(@PathVariable UUID organizationId,
                                                                                      @RequestParam(name = "status", required = false) PlatformServiceStatus status,
                                                                                      Locale locale) {
        List<PlatformServiceResponseDTO> responses = platformRegistryService.listOrganizationServices(organizationId, status, locale);
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/hospitals/{hospitalId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<HospitalPlatformServiceLinkResponseDTO> linkHospitalToService(@PathVariable UUID hospitalId,
                                                                                         @PathVariable UUID serviceId,
                                                                                         @Valid @RequestBody(required = false) PlatformServiceLinkRequestDTO request,
                                                                                         Locale locale) {
        HospitalPlatformServiceLinkResponseDTO response = platformRegistryService.linkHospitalToService(hospitalId, serviceId, request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/hospitals/{hospitalId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<Void> unlinkHospitalFromService(@PathVariable UUID hospitalId,
                                                           @PathVariable UUID serviceId,
                                                           Locale locale) {
        platformRegistryService.unlinkHospitalFromService(hospitalId, serviceId, locale);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/hospitals/{hospitalId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<List<HospitalPlatformServiceLinkResponseDTO>> listHospitalServiceLinks(@PathVariable UUID hospitalId,
                                                                                                  Locale locale) {
        List<HospitalPlatformServiceLinkResponseDTO> responses = platformRegistryService.listHospitalServiceLinks(hospitalId, locale);
        return ResponseEntity.ok(responses);
    }

    @PostMapping("/departments/{departmentId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<DepartmentPlatformServiceLinkResponseDTO> linkDepartmentToService(@PathVariable UUID departmentId,
                                                                                             @PathVariable UUID serviceId,
                                                                                             @Valid @RequestBody(required = false) PlatformServiceLinkRequestDTO request,
                                                                                             Locale locale) {
        DepartmentPlatformServiceLinkResponseDTO response = platformRegistryService.linkDepartmentToService(departmentId, serviceId, request, locale);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/departments/{departmentId}/services/{serviceId}")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<Void> unlinkDepartmentFromService(@PathVariable UUID departmentId,
                                                             @PathVariable UUID serviceId,
                                                             Locale locale) {
        platformRegistryService.unlinkDepartmentFromService(departmentId, serviceId, locale);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/departments/{departmentId}/services")
    @PreAuthorize("hasAnyAuthority('" + ROLE_SUPER_ADMIN + "','" + ROLE_HOSPITAL_ADMIN + "','" + ROLE_IT_STAFF + "')")
    public ResponseEntity<List<DepartmentPlatformServiceLinkResponseDTO>> listDepartmentServiceLinks(@PathVariable UUID departmentId,
                                                                                                      Locale locale) {
        List<DepartmentPlatformServiceLinkResponseDTO> responses = platformRegistryService.listDepartmentServiceLinks(departmentId, locale);
        return ResponseEntity.ok(responses);
    }
}
