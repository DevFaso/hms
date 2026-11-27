package com.example.hms.controller;

import com.example.hms.payload.dto.medication.MedicationCatalogItemRequestDTO;
import com.example.hms.payload.dto.medication.MedicationCatalogItemResponseDTO;
import com.example.hms.service.MedicationCatalogItemService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
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

    /** Go-live D2 (V385): the approval circuit behind the version endpoints. */
    @MockitoBean
    private com.example.hms.service.formulary.MedicationCatalogVersionService versionService;

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
    void create_returnsAcceptedWithTheVersionAwaitingApproval() throws Exception {
        MedicationCatalogItemRequestDTO request = MedicationCatalogItemRequestDTO.builder()
                .nameFr("Paracétamol")
                .genericName("Paracetamol")
                .hospitalId(hospitalId)
                // Required on a creation: every new entry proposes a version (R29: refused at the edge).
                .changeReason("Référencé à la LNME")
                .build();

        // Go-live D2 (V385): a new entry is proposed, not created: 202 and the version awaiting approval.
        when(catalogService.create(any())).thenReturn(
            com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO.builder()
                .itemLabel("Paracétamol")
                .status(com.example.hms.enums.MedicationCatalogVersionStatus.PENDING_APPROVAL)
                .build());

        mockMvc.perform(post("/medication-catalog")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.itemLabel").value("Paracétamol"));
    }

    @Test
    void getById_returnsOk() throws Exception {
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), eq(false))).thenReturn(hospitalId);
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
        when(authUtils.resolveHospitalScope(any(), isNull(), eq(false)))
                .thenReturn(null);
        when(authUtils.hasAuthority(any(), eq("ROLE_SUPER_ADMIN"))).thenReturn(true);
        when(catalogService.listByHospital(isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/medication-catalog"))
                .andExpect(status().isOk());

        verify(catalogService).listByHospital(isNull(), any(Pageable.class));
    }

    /** Guard: clinician without scope still 400s on the GET path. */
    @Test
    void list_clinicianWithoutHospitalScope_returns400() throws Exception {
        when(authUtils.resolveHospitalScope(any(), isNull(), eq(false)))
                .thenReturn(null);
        when(authUtils.hasAuthority(any(), eq("ROLE_SUPER_ADMIN"))).thenReturn(false);

        mockMvc.perform(get("/medication-catalog"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.anyOf(org.hamcrest.Matchers.is("pharmacy.hospitalContext.required"),
                                org.hamcrest.Matchers.startsWith("Select a hospital"))));
    }

    @Test
    void search_returnResults() throws Exception {
        // QA D922: the requested hospital goes through the caller's acting scope.
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), eq(false))).thenReturn(hospitalId);
        when(catalogService.search(eq(hospitalId), eq("para"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/medication-catalog/search")
                        .param("hospitalId", hospitalId.toString())
                        .param("q", "para"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].genericName").value("Paracetamol"));
    }

    @Test
    void search_withoutHospitalId_usesTheActingHospital() throws Exception {
        // QA D922: the portal never sends hospitalId; every search answered 400.
        when(authUtils.resolveHospitalScope(any(), isNull(), eq(false)))
                .thenReturn(hospitalId);
        when(catalogService.search(eq(hospitalId), eq("morph"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/medication-catalog/search").param("q", "morph"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].genericName").value("Paracetamol"));
    }

    @Test
    void search_withNoActingHospital_isRefused() throws Exception {
        mockMvc.perform(get("/medication-catalog/search").param("q", "morph"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void search_superAdminInGlobalView_searchesTheWholeCatalogue() throws Exception {
        // Review of #975: the list served the super-admin's global view; the search refused it.
        when(authUtils.hasAuthority(any(), eq("ROLE_SUPER_ADMIN"))).thenReturn(true);
        when(catalogService.search(isNull(), eq("morph"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/medication-catalog/search").param("q", "morph"))
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
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), eq(false))).thenReturn(hospitalId);
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

    // r66 D2901: what the portal sends. Neither call names a hospital; both answered 4xx.

    @Test
    void deactivate_withoutHospitalId_isNotABadRequest() throws Exception {
        mockMvc.perform(delete("/medication-catalog/{id}", itemId))
                .andExpect(status().isNoContent());

        verify(catalogService).deactivate(itemId, null);
    }

    @Test
    void update_withoutHospitalId_reachesTheService_whichActsAtTheCallersHospital() throws Exception {
        MedicationCatalogItemRequestDTO request = MedicationCatalogItemRequestDTO.builder()
                .nameFr("Zidovudine 300 mg")
                .genericName("zidovudine")
                .sensitivityCategory(com.example.hms.enums.SensitivityCategory.HIV)
                .build();
        when(catalogService.update(eq(itemId), any())).thenReturn(sampleResponse());

        mockMvc.perform(put("/medication-catalog/{id}", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<MedicationCatalogItemRequestDTO> sent =
            org.mockito.ArgumentCaptor.forClass(MedicationCatalogItemRequestDTO.class);
        verify(catalogService).update(eq(itemId), sent.capture());
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getHospitalId()).isNull();
        org.assertj.core.api.Assertions.assertThat(sent.getValue().getSensitivityCategory())
            .isEqualTo(com.example.hms.enums.SensitivityCategory.HIV);
        verify(authUtils, never()).resolveHospitalScope(any(), any(), eq(false));
    }

    @Test
    void update_namingAHospitalTheCallerMayNotActAt_neverReachesTheService() throws Exception {
        // A hospital in the BODY is narrowed by the controller (the interceptor only sees parameters).
        MedicationCatalogItemRequestDTO request = MedicationCatalogItemRequestDTO.builder()
                .nameFr("Paracétamol 1g")
                .genericName("Paracetamol")
                .hospitalId(hospitalId)
                .build();
        when(authUtils.resolveHospitalScope(any(), eq(hospitalId), eq(false)))
                .thenThrow(new com.example.hms.exception.BusinessException("pharmacy.hospitalContext.required"));

        mockMvc.perform(put("/medication-catalog/{id}", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().is4xxClientError());

        verify(catalogService, never()).update(any(), any());
    }

    @Test
    void getById_withoutHospitalId_readsAtTheActingHospital() throws Exception {
        when(authUtils.resolveHospitalScope(any(), isNull(), eq(false))).thenReturn(hospitalId);
        when(catalogService.getById(itemId, hospitalId)).thenReturn(sampleResponse());

        mockMvc.perform(get("/medication-catalog/{id}", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.atcCode").value("N02BE01"));
    }

    // ── Go-live D2 (V385): the approval circuit ──

    private static com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO version(
            com.example.hms.enums.MedicationCatalogVersionStatus status) {
        return com.example.hms.payload.dto.medication.MedicationCatalogVersionResponseDTO.builder()
            .id(UUID.randomUUID()).itemLabel("Paracétamol 500 mg").status(status)
            .kind(com.example.hms.enums.MedicationCatalogVersionKind.FORMULARY).versionNumber(2).build();
    }

    @Test
    void theQueueIsListedByStatusAndKind_andIsNotReadAsAnEntryId() throws Exception {
        when(versionService.list(eq(com.example.hms.enums.MedicationCatalogVersionStatus.PENDING_APPROVAL),
                eq(com.example.hms.enums.MedicationCatalogVersionKind.DOSE_LIMIT), any()))
            .thenReturn(new org.springframework.data.domain.PageImpl<>(java.util.List.of(
                version(com.example.hms.enums.MedicationCatalogVersionStatus.PENDING_APPROVAL))));

        mockMvc.perform(get("/medication-catalog/versions")
                        .param("status", "PENDING_APPROVAL").param("kind", "DOSE_LIMIT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].itemLabel").value("Paracétamol 500 mg"));

        verify(catalogService, never()).getById(any(), any());
    }

    @Test
    void anEntrysHistoryIsRead() throws Exception {
        when(versionService.history(itemId)).thenReturn(java.util.List.of(
            version(com.example.hms.enums.MedicationCatalogVersionStatus.APPROVED)));

        mockMvc.perform(get("/medication-catalog/{id}/versions", itemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("APPROVED"))
                .andExpect(jsonPath("$[0].versionNumber").value(2));
    }

    @Test
    void anApprovalNeedsNoBody_andARefusalCarriesItsReason() throws Exception {
        UUID versionId = UUID.randomUUID();
        when(versionService.approve(versionId, null))
            .thenReturn(version(com.example.hms.enums.MedicationCatalogVersionStatus.APPROVED));
        when(versionService.reject(versionId, "Dosage non référencé"))
            .thenReturn(version(com.example.hms.enums.MedicationCatalogVersionStatus.REJECTED));

        mockMvc.perform(post("/medication-catalog/versions/{versionId}/approve", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        mockMvc.perform(post("/medication-catalog/versions/{versionId}/reject", versionId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Dosage non référencé\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));

        verify(versionService).approve(versionId, null);
        verify(versionService).reject(versionId, "Dosage non référencé");
    }

    @Test
    void aVersionIsWithdrawn_andAnEntryIsSubmittedAsItStands() throws Exception {
        UUID versionId = UUID.randomUUID();
        when(versionService.withdraw(versionId))
            .thenReturn(version(com.example.hms.enums.MedicationCatalogVersionStatus.WITHDRAWN));
        when(catalogService.requestFormularyApproval(itemId, "Revue initiale")).thenReturn(sampleResponse());

        mockMvc.perform(post("/medication-catalog/versions/{versionId}/withdraw", versionId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WITHDRAWN"));
        mockMvc.perform(post("/medication-catalog/{id}/approval-request", itemId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Revue initiale\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nameFr").value("Paracétamol"));

        verify(catalogService).requestFormularyApproval(itemId, "Revue initiale");
    }
}
