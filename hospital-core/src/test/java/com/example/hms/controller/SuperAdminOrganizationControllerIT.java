package com.example.hms.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@AutoConfigureMockMvc
@Transactional
class SuperAdminOrganizationControllerIT extends com.example.hms.BaseIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private HospitalRepository hospitalRepository;

    private Locale originalLocale;

    @BeforeEach
    void captureLocale() {
        originalLocale = LocaleContextHolder.getLocale();
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }

    @AfterEach
    void resetLocale() {
        LocaleContextHolder.setLocale(originalLocale);
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"SUPER_ADMIN"})
    void createOrganization_returns201() throws Exception {
        Map<String, Object> payload = Map.of(
            "name", "Controller IT Health",
            "code", "ctrl-it",
            "timezone", "UTC",
            "contactEmail", "controller-it@example.org"
        );

        mockMvc.perform(post("/api/super-admin/organizations")
                .contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)))
            .andExpect(status().isCreated());

        long count = organizationRepository.count();
        assertThat(count).isGreaterThan(0);
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"SUPER_ADMIN"})
    void assignHospitalToOrganization_linksHospital() throws Exception {
        Organization organization = organizationRepository.save(Organization.builder()
            .name("Assign Org")
            .code("ASSIGN-ORG")
            .type(OrganizationType.PRIVATE_PRACTICE)
            .build());

        Hospital hospital = hospitalRepository.save(Hospital.builder()
            .name("Assign Hospital")
            .code("ASSIGN-HOSP")
            .active(true)
            .build());

        mockMvc.perform(post("/api/super-admin/organizations/{organizationId}/hospitals/{hospitalId}",
                organization.getId(), hospital.getId())
                .contextPath("/api"))
            .andExpect(status().isOk());

        Hospital updated = hospitalRepository.findById(hospital.getId()).orElseThrow();
        assertThat(updated.getOrganization()).isNotNull();
        assertThat(updated.getOrganization().getId()).isEqualTo(organization.getId());
    }

    @Test
    @WithMockUser(username = "superadmin", roles = {"SUPER_ADMIN"})
    void unassignHospitalFromOrganization_clearsLink() throws Exception {
        Organization organization = organizationRepository.save(Organization.builder()
            .name("Unassign Org")
            .code("UNASSIGN-ORG")
            .type(OrganizationType.PRIVATE_PRACTICE)
            .build());

        Hospital hospital = Hospital.builder()
            .name("Unassign Hospital")
            .code("UNASSIGN-HOSP")
            .active(true)
            .organization(organization)
            .build();
        hospital = hospitalRepository.save(hospital);

        mockMvc.perform(delete("/api/super-admin/organizations/{organizationId}/hospitals/{hospitalId}",
                organization.getId(), hospital.getId())
                .contextPath("/api"))
            .andExpect(status().isOk());

        Hospital updated = hospitalRepository.findById(hospital.getId()).orElseThrow();
        assertThat(updated.getOrganization()).isNull();
    }
}
