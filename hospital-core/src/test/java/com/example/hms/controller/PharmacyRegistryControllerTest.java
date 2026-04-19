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

    private final UUID hospitalId = UUID.randomUUID();
    private final UUID pharmacyId = UUID.randomUUID();

    private PharmacyResponseDTO sampleResponse() {
        return PharmacyResponseDTO.builder()
                .id(pharmacyId)
                .name("Pharmacie Centrale")
                .licenseNumber("PH-2024-001")
                .city("Ouagadougou")
                .fulfillmentMode(PharmacyFulfillmentMode.COMMUNITY)
                .tier(1)
                .active(true)
                .hospitalId(hospitalId)
                .build();
    }

    @Test
    void create_returnsCreated() throws Exception {
        PharmacyRequestDTO request = PharmacyRequestDTO.builder()
                .name("Pharmacie Centrale")
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
        when(pharmacyService.listByHospital(eq(hospitalId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(sampleResponse())));

        mockMvc.perform(get("/pharmacy-registry")
                        .param("hospitalId", hospitalId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].name").value("Pharmacie Centrale"));
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
