package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(authorities = "ROLE_SUPER_ADMIN")
class HospitalControllerIT extends BaseIT {

    private static final String API_CONTEXT = "/api";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private HospitalRepository hospitalRepository;

    @Autowired
    private OrganizationRepository organizationRepository;

    @Autowired
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @BeforeEach
    void cleanDatabase() {
        departmentRepository.deleteAll();
        assignmentRepository.deleteAll();
        hospitalRepository.deleteAll();
        organizationRepository.deleteAll();
    }

    @Test
    @DisplayName("GET /hospitals filters by organization id")
    void getAllHospitalsFiltersByOrganization() throws Exception {
        Organization orgA = createOrganization("ORG-A");
        Organization orgB = createOrganization("ORG-B");

        Hospital orgHospital = createHospital("Org A Clinic", "ORGAC", "Ouagadougou", "Centre", orgA);
        createHospital("Org B Clinic", "ORGBB", "Bobo-Dioulasso", "Hauts-Bassins", orgB);
        createHospital("Independent Clinic", "INDEP", "Kaya", "Centre-Nord", null);

        mockMvc.perform(get(API_CONTEXT + "/hospitals").contextPath(API_CONTEXT)
                        .param("organizationId", orgA.getId().toString())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(orgHospital.getId().toString()))
                .andExpect(jsonPath("$[0].organizationId").value(orgA.getId().toString()))
                .andExpect(jsonPath("$[0].code").value("ORGAC"));
    }

    @Test
    @DisplayName("GET /hospitals returns only unassigned when requested")
    void getAllHospitalsUnassignedOnly() throws Exception {
        Organization orgA = createOrganization("ORG-C");
        createHospital("Chain Clinic", "CHAIN", "Ouagadougou", "Centre", orgA);
        Hospital unassigned = createHospital("Unassigned Clinic", "FREEH", "Fada", "Est", null);

        mockMvc.perform(get(API_CONTEXT + "/hospitals").contextPath(API_CONTEXT)
                        .param("unassignedOnly", "true")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(unassigned.getId().toString()))
                .andExpect(jsonPath("$[0].organizationId").value(nullValue()));
    }

    @Test
    @DisplayName("GET /hospitals supports case-insensitive city filtering")
    void getAllHospitalsCityFilter() throws Exception {
        createHospital("Capital Clinic", "CAP01", "Ouagadougou", "Centre", null);
        createHospital("Western Clinic", "WEST1", "Bobo-Dioulasso", "Hauts-Bassins", null);

        mockMvc.perform(get(API_CONTEXT + "/hospitals").contextPath(API_CONTEXT)
                        .param("city", "ouaga")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].city").value("Ouagadougou"));
    }

    private Organization createOrganization(String code) {
        Organization organization = Organization.builder()
                .name("Org " + code)
                .code(code)
                .type(OrganizationType.HOSPITAL_CHAIN)
                .active(true)
                .build();
        return organizationRepository.save(organization);
    }

    private Hospital createHospital(String name, String code, String city, String state, Organization organization) {
        Hospital hospital = Hospital.builder()
                .name(name)
                .code(code)
                .city(city)
                .state(state)
                .country("Burkina Faso")
                .phoneNumber("+226-555-0000")
                .active(true)
                .build();
        hospital.setOrganization(organization);
        return hospitalRepository.save(hospital);
    }
}
