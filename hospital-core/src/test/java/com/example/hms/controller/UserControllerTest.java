package com.example.hms.controller;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.UserService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Slice tests for {@link UserController}.
 *
 * Key regression guard: registering a SUPER_ADMIN without a hospitalId must return 201,
 * not 400.  The controller previously rejected any non-patient registration that lacked a
 * hospitalId — even SUPER_ADMIN, which is a global/platform role with no hospital context.
 */
@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = UserController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
class UserControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @MockitoBean private UserService userService;
    @MockitoBean private HospitalRepository hospitalRepository;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserRoleHospitalAssignmentRepository assignmentRepository;

    // -------------------------------------------------------------------------
    // adminRegister — SUPER_ADMIN without hospitalId must succeed (201)
    // -------------------------------------------------------------------------

    @Test
    void adminRegister_superAdmin_withoutHospital_returns201() throws Exception {
        AdminSignupRequest req = new AdminSignupRequest();
        req.setUsername("superadmin");
        req.setEmail("superadmin@example.com");
        req.setPassword("Password1!");
        req.setFirstName("Super");
        req.setLastName("Admin");
        req.setPhoneNumber("+22670000000");
        req.setRoleNames(Set.of("ROLE_SUPER_ADMIN"));
        // hospitalId intentionally null — SUPER_ADMIN is a global role

        UserResponseDTO response = new UserResponseDTO();
        response.setId(UUID.randomUUID());
        response.setUsername("superadmin");

        when(userService.createUserWithRolesAndHospital(any())).thenReturn(response);

        mockMvc.perform(post("/users/admin-register")
                .with(SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "tiego", "pw",
                        AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }

    @Test
    void adminRegister_superAdmin_withHospital_returns201() throws Exception {
        AdminSignupRequest req = new AdminSignupRequest();
        req.setUsername("superadmin2");
        req.setEmail("superadmin2@example.com");
        req.setPassword("Password1!");
        req.setFirstName("Super");
        req.setLastName("Admin");
        req.setPhoneNumber("+22670000001");
        req.setRoleNames(Set.of("ROLE_SUPER_ADMIN"));
        req.setHospitalId(UUID.randomUUID());  // optional hospital is fine too

        UserResponseDTO response = new UserResponseDTO();
        response.setId(UUID.randomUUID());
        response.setUsername("superadmin2");

        when(userService.createUserWithRolesAndHospital(any())).thenReturn(response);

        mockMvc.perform(post("/users/admin-register")
                .with(SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "tiego", "pw",
                        AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated());
    }

    @Test
    void adminRegister_hospitalAdmin_withoutHospital_returns400() throws Exception {
        // HOSPITAL_ADMIN *does* require a hospital — must still return 400 when missing.
        AdminSignupRequest req = new AdminSignupRequest();
        req.setUsername("hosp_admin");
        req.setEmail("hosp_admin@example.com");
        req.setPassword("Password1!");
        req.setFirstName("Hospital");
        req.setLastName("Admin");
        req.setPhoneNumber("+22670000002");
        req.setRoleNames(Set.of("ROLE_HOSPITAL_ADMIN"));
        // hospitalId intentionally null AND no hospitalName

        mockMvc.perform(post("/users/admin-register")
                .with(SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "tiego", "pw",
                        AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isBadRequest());
    }
}
