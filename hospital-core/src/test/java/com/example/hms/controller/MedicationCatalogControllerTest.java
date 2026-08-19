package com.example.hms.controller;

import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.service.MedicationCatalogItemService;
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

@WebMvcTest(controllers = MedicationCatalogController.class,
        excludeFilters = @ComponentScan.Filter(type = FilterType.REGEX, pattern = "com\\.example\\.hms\\.security\\..*"))
@AutoConfigureMockMvc(addFilters = false)
class MedicationCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MedicationCatalogItemService catalogService;

    @MockitoBean
    private com.example.hms.controller.support.ControllerAuthUtils authUtils;

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID itemId = UUID.randomUUID();

    private MedicationCatalogItemResponseDTO sampleResponse() {
        return MedicationCatalogItemResponseDTO.builder()
                .id(itemId)
                .nameFr("Paracétamol")
                .genericName("Paracetamol")
                .atcCode("N02BE01")
                .form("Comprimé")
                .strength("500")
                .strengthUnit("mg")
                .category("Antalgique")
                .essentialList(true)
                .active(true)
                .hospitalId(hospitalId)
                .build();
    }

    @Test
    void create_returnsCreated() throws Exception {
        MedicationCatalogItemRequestDTO request = MedicationCatalogItemRequestDTO.builder()
                .nameFr("Paracétamol")
                .genericName("Paracetamol")
                .hospitalId(hospitalId)
                .build();

        when(catalogService.create(any())).thenReturn(sampleResponse());

        mockMvc.perform(post("/medication-catalog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.nameFr").value("Paracétamol"));
    }

    @Test
    void getById_returnsOk() throws Exception {
        when(catalogService.getById(itemId, hospitalId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/medication-catalog/{id}", itemId)
                        .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atcCode").value("N02BE01"));
    }

    @Test
    void list_returnsPaginatedResults() throws Exception {
        // The controller now resolves hospitalId via authUtils.resolveHospitalScope
        // (added so super-admins in global view can list across tenants).
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), eq(false)))
                .thenReturn(hospitalId);
        when(catalogService.listByHospital(eq(hospitalId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/medication-catalog")
                        .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].nameFr").value("Paracétamol"));
    }

    /**
     * Regression for the dev 400 reported on 2026-05-10:
     * GET /api/medication-catalog?page=0&size=20 returned
     * {@code MissingServletRequestParameterException} for super-admin
     * tchico1er in global view. Same fix shape as the InBasket controller
     * (PR #292) and the pharmacy-registry list path in this PR.
     */
    @Test
    void list_superAdminWithoutHospitalId_returns200() throws Exception {
        when(authUtils.resolveHospitalScope(any(), org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(null);
        when(authUtils.hasAuthority(any(), eq("ROLE_SUPER_ADMIN"))).thenReturn(true);
        when(catalogService.listByHospital(org.mockito.ArgumentMatchers.isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/medication-catalog"))
                .andExpect(status().isOk());

        verify(catalogService).listByHospital(org.mockito.ArgumentMatchers.isNull(), any(Pageable.class));
    }

    /** Guard: clinician without scope still 400s on the GET path. */
    @Test
    void list_clinicianWithoutHospitalScope_returns400() throws Exception {
        when(authUtils.resolveHospitalScope(any(), org.mockito.ArgumentMatchers.isNull(), eq(false)))
                .thenReturn(null);
        when(authUtils.hasAuthority(any(), eq("ROLE_SUPER_ADMIN"))).thenReturn(false);

        mockMvc.perform(get("/medication-catalog"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Hospital context is required")));
    }

    @Test
    void search_returnResults() throws Exception {
        when(catalogService.search(eq(hospitalId), eq("para"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/medication-catalog/search")
                        .param("hospitalId", hospitalId.toString())
                        .param("q", "para"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].genericName").value("Paracetamol"));
    }

    @Test
    void update_returnsOk() throws Exception {
        MedicationCatalogItemRequestDTO request = MedicationCatalogItemRequestDTO.builder()
                .nameFr("Paracétamol 1g")
                .genericName("Paracetamol")
                .hospitalId(hospitalId)
                .build();

        MedicationCatalogItemResponseDTO updated = sampleResponse();
        updated.setNameFr("Paracétamol 1g");
        when(catalogService.update(eq(itemId), any())).thenReturn(updated);

        mockMvc.perform(put("/medication-catalog/{id}", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameFr").value("Paracétamol 1g"));
    }

    @Test
    void deactivate_returnsNoContent() throws Exception {
        mockMvc.perform(delete("/medication-catalog/{id}", itemId)
                        .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isNoContent());

        verify(catalogService).deactivate(itemId, hospitalId);
    }
}
