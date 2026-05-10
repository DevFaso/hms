package com.example.hms.controller;

import com.example.hms.enums.PharmacyFulfillmentMode;
import com.example.hms.payload.dto.pharmacy.PharmacyRequestDTO;
import com.example.hms.payload.dto.pharmacy.PharmacyResponseDTO;
import com.example.hms.service.PharmacyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = PharmacyRegistryController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class PharmacyRegistryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private PharmacyService pharmacyService;

    @MockitoBean
    private com.example.hms.controller.support.ControllerAuthUtils authUtils;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();

    private PharmacyResponseDTO sampleResponse() {
        return PharmacyResponseDTO.builder()
                .id(pharmacyId)
                .name("Pharmacie Centrale")
                .licenseNumber("PH-2024-001")
                .city("Ouagadougou")
                .fulfillmentMode(PharmacyFulfillmentMode.COMMUNITY.name())
                .active(true)
                .hospitalId(hospitalId)
                .build();
    }

    @Test
    void create_returnsCreated() throws Exception {
        PharmacyRequestDTO request = PharmacyRequestDTO.builder()
                .name("Pharmacie Centrale")
                .fulfillmentMode(PharmacyFulfillmentMode.COMMUNITY)
                .hospitalId(hospitalId)
                .build();

        when(pharmacyService.create(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/pharmacy-registry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Pharmacie Centrale"));
    }

    @Test
    void getById_returnsOk() throws Exception {
        when(pharmacyService.getById(pharmacyId, hospitalId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/pharmacy-registry/{id}", pharmacyId)
                        .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.city").value("Ouagadougou"));
    }

    @Test
    void list_returnsPaginatedResults() throws Exception {
        // The controller now resolves hospitalId via authUtils.resolveHospitalScope
        // (added so super-admins in global view can list across tenants).
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), eq(false)))
                .thenReturn(hospitalId);
        when(pharmacyService.listByHospital(eq(hospitalId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/pharmacy-registry")
                        .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Pharmacie Centrale"));
    }

    /**
     * Regression for the dev 400 reported on 2026-05-10:
     * GET /api/pharmacy-registry?page=0&size=20 returned
     * {@code MissingServletRequestParameterException} for super-admin
     * tchico1er in global view (no JWT scope, no hospitalId param).
     * The controller now lets the null hospitalId fall through for
     * super-admins; the service's repository JPQL drops the filter
     * and returns every active pharmacy across tenants.
     */
    @Test
    void list_superAdminWithoutHospitalId_returns200() throws Exception {
        when(authUtils.resolveHospitalScope(any(), org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(null);
        when(authUtils.hasAuthority(any(), eq("ROLE_SUPER_ADMIN"))).thenReturn(true);
        when(pharmacyService.listByHospital(org.mockito.ArgumentMatchers.isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/pharmacy-registry"))
                .andExpect(status().isOk());

        // Null hospitalId must be carried through to the service so the
        // repository's JPQL can drop its hospital filter.
        verify(pharmacyService).listByHospital(org.mockito.ArgumentMatchers.isNull(), any(Pageable.class));
    }

    /** Guard: clinician without scope still 400s on the GET path. */
    @Test
    void list_clinicianWithoutHospitalScope_returns400() throws Exception {
        when(authUtils.resolveHospitalScope(any(), org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(null);
        when(authUtils.hasAuthority(any(), eq("ROLE_SUPER_ADMIN"))).thenReturn(false);

        mockMvc.perform(get("/pharmacy-registry"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Hospital context is required")));
    }

    /**
     * Regression for the dev 500 reported on 2026-05-10: the frontend's
     * pharmacy-registry modal sent {@code "pharmacyType":"COMMUNITY"} but the
     * backend enum expects {@code COMMUNITY_PHARMACY}. Jackson raised
     * {@code HttpMessageNotReadableException}, and because the global
     * exception handler had no specific mapping for it, the request fell
     * through to the catch-all {@code RuntimeException} handler ⇒ 500.
     * After this PR a Jackson deserialisation failure surfaces as a 400
     * with the original "not one of the values accepted" message.
     */
    @Test
    void create_malformedEnumValue_returns400NotGeneric500() throws Exception {
        String malformedBody = "{"
            + "\"hospitalId\":\"" + hospitalId + "\","
            + "\"name\":\"Pharmacie Centrale\","
            + "\"pharmacyType\":\"COMMUNITY\""    // unknown enum value
            + "}";

        mockMvc.perform(post("/pharmacy-registry")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(malformedBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Malformed request body")));
    }

    @Test
    void search_returnsResults() throws Exception {
        when(pharmacyService.search(eq(hospitalId), eq("centrale"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/pharmacy-registry/search")
                        .param("hospitalId", hospitalId.toString())
                        .param("q", "centrale"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].licenseNumber").value("PH-2024-001"));
    }

    @Test
    void update_returnsOk() throws Exception {
        PharmacyRequestDTO request = PharmacyRequestDTO.builder()
                .name("Pharmacie Centrale Updated")
                .fulfillmentMode(PharmacyFulfillmentMode.COMMUNITY)
                .hospitalId(hospitalId)
                .build();

        PharmacyResponseDTO updated = sampleResponse();
        updated.setName("Pharmacie Centrale Updated");
        when(pharmacyService.update(eq(pharmacyId), any())).thenReturn(updated);

        mockMvc.perform(put("/pharmacy-registry/{id}", pharmacyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Pharmacie Centrale Updated"));
    }

    @Test
    void deactivate_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/pharmacy-registry/{id}", pharmacyId)
                        .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isNoContent());

        verify(pharmacyService).deactivate(pharmacyId, hospitalId);
    }
}
