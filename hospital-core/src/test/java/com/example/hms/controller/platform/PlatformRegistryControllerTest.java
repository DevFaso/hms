package com.example.hms.controller.platform;

import com.example.hms.enums.platform.PlatformServiceStatus;
import com.example.hms.enums.platform.PlatformServiceType;
import com.example.hms.payload.dto.DepartmentPlatformServiceLinkResponseDTO;
import com.example.hms.payload.dto.HospitalPlatformServiceLinkResponseDTO;
import com.example.hms.mapper.PlatformIntegrationDescriptorMapper;
import com.example.hms.payload.dto.PlatformOwnershipDTO;
import com.example.hms.payload.dto.PlatformServiceLinkRequestDTO;
import com.example.hms.payload.dto.PlatformServiceRegistrationRequestDTO;
import com.example.hms.payload.dto.PlatformServiceResponseDTO;
import com.example.hms.payload.dto.PlatformServiceUpdateRequestDTO;
import com.example.hms.service.platform.PlatformRegistryService;
import com.example.hms.service.platform.discovery.IntegrationDescriptor;
import com.example.hms.service.platform.discovery.PlatformServiceRegistry;
import com.example.hms.security.JwtTokenProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PlatformRegistryController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(PlatformIntegrationDescriptorMapper.class)
class PlatformRegistryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private PlatformRegistryService platformRegistryService;

    @MockBean
    private JwtTokenProvider jwtTokenProvider;

    @MockBean
    private PlatformServiceRegistry platformServiceRegistry;

    private UUID organizationId;
    private UUID serviceId;
    private UUID hospitalId;
    private UUID departmentId;

    @BeforeEach
    void setUp() {
        organizationId = UUID.randomUUID();
        serviceId = UUID.randomUUID();
        hospitalId = UUID.randomUUID();
        departmentId = UUID.randomUUID();
    }

    @Test
    void catalogEndpointReturnsDescriptors() throws Exception {
        IntegrationDescriptor descriptor = IntegrationDescriptor.builder()
            .id("ehr")
            .serviceType(PlatformServiceType.EHR)
            .displayName("EHR Core Interop")
            .enabled(true)
            .autoProvision(true)
            .managedByPlatform(true)
            .build();

        when(platformServiceRegistry.listIntegrations(anyBoolean(), any(Locale.class))).thenReturn(List.of(descriptor));

        mockMvc.perform(get("/platform/catalog")
                .header("Accept-Language", "en-US")
                .contentType(MediaType.APPLICATION_JSON))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].id").value("ehr"))
            .andExpect(jsonPath("$[0].enabled").value(true))
            .andExpect(jsonPath("$[0].autoProvision").value(true));
    }

    @Test
    void catalogDescriptorLookupReturnsSingleDescriptor() throws Exception {
        IntegrationDescriptor descriptor = IntegrationDescriptor.builder()
            .id("billing")
            .serviceType(PlatformServiceType.BILLING)
            .displayName("Billing Stub")
            .enabled(true)
            .build();

        when(platformServiceRegistry.findIntegration(eq(PlatformServiceType.BILLING), any(Locale.class)))
            .thenReturn(Optional.of(descriptor));

        mockMvc.perform(get("/platform/catalog/BILLING")
                .header("Accept-Language", "en-US"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value("billing"))
            .andExpect(jsonPath("$.serviceType").value("BILLING"));
    }

    @Test
    void catalogDescriptorLookupReturnsNotFoundWhenMissing() throws Exception {
        when(platformServiceRegistry.findIntegration(eq(PlatformServiceType.LIMS), any(Locale.class)))
            .thenReturn(Optional.empty());

        mockMvc.perform(get("/platform/catalog/LIMS")
                .header("Accept-Language", "en-US"))
            .andExpect(status().isNotFound());
    }

    @Test
    void registerOrganizationServiceReturnsCreated() throws Exception {
        PlatformServiceRegistrationRequestDTO requestDTO = PlatformServiceRegistrationRequestDTO.builder()
            .serviceType(PlatformServiceType.EHR)
            .provider("Cerner")
            .managedByPlatform(true)
            .ownership(PlatformOwnershipDTO.builder().ownerTeam("Platform").build())
            .build();

        PlatformServiceResponseDTO responseDTO = PlatformServiceResponseDTO.builder()
            .id(serviceId)
            .organizationId(organizationId)
            .serviceType(PlatformServiceType.EHR)
            .status(PlatformServiceStatus.PENDING)
            .managedByPlatform(true)
            .build();

        when(platformRegistryService.registerOrganizationService(eq(organizationId), any(PlatformServiceRegistrationRequestDTO.class), any(Locale.class)))
            .thenReturn(responseDTO);

        mockMvc.perform(post("/platform/organizations/{organizationId}/services", organizationId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.id").value(serviceId.toString()))
            .andExpect(jsonPath("$.serviceType").value(PlatformServiceType.EHR.name()));
    }

    @Test
    void updateOrganizationServiceReturnsOk() throws Exception {
        PlatformServiceUpdateRequestDTO requestDTO = PlatformServiceUpdateRequestDTO.builder()
            .status(PlatformServiceStatus.ACTIVE)
            .build();

        PlatformServiceResponseDTO responseDTO = PlatformServiceResponseDTO.builder()
            .id(serviceId)
            .organizationId(organizationId)
            .serviceType(PlatformServiceType.BILLING)
            .status(PlatformServiceStatus.ACTIVE)
            .build();

        when(platformRegistryService.updateOrganizationService(eq(organizationId), eq(serviceId), any(PlatformServiceUpdateRequestDTO.class), any(Locale.class)))
            .thenReturn(responseDTO);

        mockMvc.perform(put("/platform/organizations/{organizationId}/services/{serviceId}", organizationId, serviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value(PlatformServiceStatus.ACTIVE.name()));
    }

    @Test
    void listOrganizationServicesReturnsData() throws Exception {
        PlatformServiceResponseDTO responseDTO = PlatformServiceResponseDTO.builder()
            .id(serviceId)
            .organizationId(organizationId)
            .serviceType(PlatformServiceType.ANALYTICS)
            .status(PlatformServiceStatus.ACTIVE)
            .build();

        when(platformRegistryService.listOrganizationServices(eq(organizationId), eq(PlatformServiceStatus.ACTIVE), any(Locale.class)))
            .thenReturn(List.of(responseDTO));

        mockMvc.perform(get("/platform/organizations/{organizationId}/services", organizationId)
                .param("status", PlatformServiceStatus.ACTIVE.name()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].serviceType").value(PlatformServiceType.ANALYTICS.name()));
    }

    @Test
    void linkHospitalToServiceReturnsCreated() throws Exception {
        PlatformServiceLinkRequestDTO requestDTO = PlatformServiceLinkRequestDTO.builder()
            .credentialsReference("secret")
            .build();

        HospitalPlatformServiceLinkResponseDTO responseDTO = HospitalPlatformServiceLinkResponseDTO.builder()
            .id(UUID.randomUUID())
            .hospitalId(hospitalId)
            .organizationServiceId(serviceId)
            .serviceType(PlatformServiceType.LIMS)
            .enabled(true)
            .build();

        when(platformRegistryService.linkHospitalToService(eq(hospitalId), eq(serviceId), any(PlatformServiceLinkRequestDTO.class), any(Locale.class)))
            .thenReturn(responseDTO);

        mockMvc.perform(post("/platform/hospitals/{hospitalId}/services/{serviceId}", hospitalId, serviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestDTO)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.hospitalId").value(hospitalId.toString()))
            .andExpect(jsonPath("$.serviceType").value(PlatformServiceType.LIMS.name()));
    }

    @Test
    void unlinkHospitalFromServiceReturnsNoContent() throws Exception {
        Mockito.doNothing().when(platformRegistryService).unlinkHospitalFromService(eq(hospitalId), eq(serviceId), any(Locale.class));

        mockMvc.perform(delete("/platform/hospitals/{hospitalId}/services/{serviceId}", hospitalId, serviceId))
            .andExpect(status().isNoContent());
    }

    @Test
    void listHospitalServiceLinksReturnsData() throws Exception {
        HospitalPlatformServiceLinkResponseDTO responseDTO = HospitalPlatformServiceLinkResponseDTO.builder()
            .id(UUID.randomUUID())
            .hospitalId(hospitalId)
            .organizationServiceId(serviceId)
            .serviceType(PlatformServiceType.EHR)
            .enabled(true)
            .build();

        when(platformRegistryService.listHospitalServiceLinks(eq(hospitalId), any(Locale.class)))
            .thenReturn(List.of(responseDTO));

        mockMvc.perform(get("/platform/hospitals/{hospitalId}/services", hospitalId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].serviceType").value(PlatformServiceType.EHR.name()));
    }

    @Test
    void linkDepartmentToServiceReturnsCreated() throws Exception {
        DepartmentPlatformServiceLinkResponseDTO responseDTO = DepartmentPlatformServiceLinkResponseDTO.builder()
            .id(UUID.randomUUID())
            .departmentId(departmentId)
            .organizationServiceId(serviceId)
            .serviceType(PlatformServiceType.INVENTORY)
            .enabled(true)
            .build();

        when(platformRegistryService.linkDepartmentToService(eq(departmentId), eq(serviceId), any(PlatformServiceLinkRequestDTO.class), any(Locale.class)))
            .thenReturn(responseDTO);

        mockMvc.perform(post("/platform/departments/{departmentId}/services/{serviceId}", departmentId, serviceId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(PlatformServiceLinkRequestDTO.builder().build())))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.departmentId").value(departmentId.toString()))
            .andExpect(jsonPath("$.serviceType").value(PlatformServiceType.INVENTORY.name()));
    }

    @Test
    void unlinkDepartmentFromServiceReturnsNoContent() throws Exception {
        Mockito.doNothing().when(platformRegistryService).unlinkDepartmentFromService(eq(departmentId), eq(serviceId), any(Locale.class));

        mockMvc.perform(delete("/platform/departments/{departmentId}/services/{serviceId}", departmentId, serviceId))
            .andExpect(status().isNoContent());
    }

    @Test
    void listDepartmentServiceLinksReturnsData() throws Exception {
        DepartmentPlatformServiceLinkResponseDTO responseDTO = DepartmentPlatformServiceLinkResponseDTO.builder()
            .id(UUID.randomUUID())
            .departmentId(departmentId)
            .organizationServiceId(serviceId)
            .serviceType(PlatformServiceType.BILLING)
            .enabled(true)
            .build();

        when(platformRegistryService.listDepartmentServiceLinks(eq(departmentId), any(Locale.class)))
            .thenReturn(List.of(responseDTO));

        mockMvc.perform(get("/platform/departments/{departmentId}/services", departmentId))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].serviceType").value(PlatformServiceType.BILLING.name()));
    }
}
