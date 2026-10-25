package com.example.hms.controller;

import com.example.hms.payload.dto.AdminSignupRequest;
import com.example.hms.payload.dto.UserResponseDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.service.UserService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    @MockitoBean private com.example.hms.service.PatientAccountRegistrationService patientAccountRegistrationService;
    @MockitoBean private HospitalRepository hospitalRepository;
    @MockitoBean private UserRepository userRepository;
    @MockitoBean private UserRoleHospitalAssignmentRepository assignmentRepository;
    @MockitoBean private com.example.hms.utility.RoleValidator roleValidator;

    // -------------------------------------------------------------------------
    // adminRegister — SUPER_ADMIN without hospitalId must succeed (201)
    // -------------------------------------------------------------------------

    @Test
    void adminRegister_surfacesActivationDeliveryReportToTheRegistrar() throws Exception {
        // The send paths record outcomes while the controller has the tracker
        // armed (the AFTER_COMMIT listener runs on this same thread); the
        // drained report must ride the 201 body so a dead transport is no
        // longer a green success. The stub records mid-call exactly like the
        // real send path does.
        AdminSignupRequest req = new AdminSignupRequest();
        req.setUsername("newstaff");
        req.setEmail("staff@example.com");
        req.setPassword("Password1!");
        req.setFirstName("New");
        req.setLastName("Staff");
        req.setPhoneNumber("+22670000002");
        req.setRoleNames(Set.of("ROLE_SUPER_ADMIN"));

        UserResponseDTO response = new UserResponseDTO();
        response.setId(UUID.randomUUID());
        when(userService.createUserWithRolesAndHospital(any())).thenAnswer(inv -> {
            com.example.hms.utility.ActivationDeliveryTracker.report(
                com.example.hms.payload.dto.NotificationDeliveryStatusDTO.builder()
                    .channel("EMAIL").purpose("ACTIVATION").outcome("NOT_CONFIGURED")
                    .target("s***@example.com").build());
            return response;
        });

        mockMvc.perform(post("/users/admin-register")
                .with(SecurityMockMvcRequestPostProcessors.authentication(
                    new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        "tiego", "pw",
                        AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(req)))
            .andExpect(status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.activationDelivery[0].outcome").value("NOT_CONFIGURED"))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.activationDelivery[0].channel").value("EMAIL"));
    }

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

    // -------------------------------------------------------------------------
    // QA D626 (regression of D329): staff onboarding must not register a patient
    // -------------------------------------------------------------------------

    private AdminSignupRequest patientSignup(String role, Boolean fromPatientForm) {
        AdminSignupRequest req = new AdminSignupRequest();
        req.setEmail("qa-onb@example.test");
        req.setFirstName("QA");
        req.setLastName("Onb");
        req.setPhoneNumber("+22670027075");
        req.setRoleNames(Set.of(role));
        req.setHospitalId(UUID.randomUUID());
        req.setPatientRegistration(fromPatientForm);
        return req;
    }

    private org.springframework.test.web.servlet.ResultActions registerAs(AdminSignupRequest req, String authority)
        throws Exception {
        return mockMvc.perform(post("/users/admin-register")
            .with(SecurityMockMvcRequestPostProcessors.authentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    "qa", "pw", AuthorityUtils.createAuthorityList(authority))))
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(req)));
    }

    @Test
    void qaD626_hospitalAdminStaffOnboardingWithRolePatient_isRefusedAndWritesNothing() throws Exception {
        for (String role : new String[] {"ROLE_PATIENT", "PATIENT", "role_patient"}) {
            registerAs(patientSignup(role, null), "ROLE_HOSPITAL_ADMIN")
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.message").isNotEmpty());
            registerAs(patientSignup(role, false), "ROLE_SUPER_ADMIN").andExpect(status().isBadRequest());
        }
        verify(userService, never()).createUserWithRolesAndHospital(any());
    }

    @Test
    void qaD626_thePatientFormRegistersTheAccountAndTheRecordTogether() throws Exception {
        UserResponseDTO response = new UserResponseDTO();
        response.setId(UUID.randomUUID());
        response.setPatientId(UUID.randomUUID());
        when(patientAccountRegistrationService.registerPatient(any(), any())).thenReturn(response);

        AdminSignupRequest req = patientSignup("PATIENT", true);
        com.example.hms.payload.dto.PatientRequestDTO patient = new com.example.hms.payload.dto.PatientRequestDTO();
        patient.setFirstName("QA");
        patient.setLastName("Onb");
        req.setPatient(patient);
        registerAs(req, "ROLE_HOSPITAL_ADMIN")
            .andExpect(status().isCreated())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.patientId").value(response.getPatientId().toString()));
        // The account-only path is never taken for a patient registration.
        verify(userService, never()).createUserWithRolesAndHospital(any());
    }

    // QA D626 (verification): the flag is the client's word; without the
    // patient record it would mint a PATIENT account with no record.
    @Test
    void qaD626_patientRegistrationFlagWithoutThePatientRecord_isRefusedAndWritesNothing() throws Exception {
        for (String authority : new String[] {"ROLE_HOSPITAL_ADMIN", "ROLE_SUPER_ADMIN"}) {
            registerAs(patientSignup("PATIENT", true), authority)
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                    .jsonPath("$.message").isNotEmpty());
        }
        verify(userService, never()).createUserWithRolesAndHospital(any());
        verify(patientAccountRegistrationService, never()).registerPatient(any(), any());
    }

    // The receptionist path (roles forced to PATIENT, no flag needed) is
    // covered through the real filter chain by UserEndpointAuthorizationIT
    // receptionistCompensationDelete: this slice runs without filters, so the
    // controller's Authentication argument is null here.

    @Test
    void qaD626_staffOnboardingOfAStaffRoleIsUnchanged() throws Exception {
        UserResponseDTO response = new UserResponseDTO();
        response.setId(UUID.randomUUID());
        when(userService.createUserWithRolesAndHospital(any())).thenReturn(response);

        registerAs(patientSignup("ROLE_DOCTOR", null), "ROLE_HOSPITAL_ADMIN").andExpect(status().isCreated());
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
            .andExpect(status().isBadRequest())
            // D201: the refusal carries a sentence, never an empty body
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.message").isNotEmpty());
    }

    @Test
    void adminRegister_hospitalAdmin_withoutHospitalId_registersIntoTheActingHospital() throws Exception {
        // D201: the portal's Users > "Inscrire" sent no hospitalId; the
        // hospital admin's acting hospital is the hospital of the new account.
        UUID hospitalA = UUID.randomUUID();
        com.example.hms.security.context.HospitalContextHolder.setContext(
            com.example.hms.security.context.HospitalContext.builder()
                .hospitalAdmin(true)
                .activeHospitalId(hospitalA)
                .permittedHospitalIds(Set.of(hospitalA))
                .build());
        try {
            AdminSignupRequest req = new AdminSignupRequest();
            req.setEmail("awa@example.com");
            req.setFirstName("Awa");
            req.setLastName("Traore");
            req.setPhoneNumber("+22670000003");
            req.setRoleNames(Set.of("ROLE_NURSE"));
            UserResponseDTO dto = new UserResponseDTO();
            dto.setId(UUID.randomUUID());
            when(userService.createUserWithRolesAndHospital(any())).thenReturn(dto);

            mockMvc.perform(post("/users/admin-register")
                    .with(SecurityMockMvcRequestPostProcessors.user("qa.admin.a")
                        .authorities(AuthorityUtils.createAuthorityList("ROLE_HOSPITAL_ADMIN")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated());

            org.mockito.ArgumentCaptor<AdminSignupRequest> sent =
                org.mockito.ArgumentCaptor.forClass(AdminSignupRequest.class);
            verify(userService).createUserWithRolesAndHospital(sent.capture());
            org.assertj.core.api.Assertions.assertThat(sent.getValue().getHospitalId()).isEqualTo(hospitalA);
        } finally {
            com.example.hms.security.context.HospitalContextHolder.clear();
        }
    }

    // -------------------------------------------------------------------------
    // PATCH /users/{id}/restore
    // -------------------------------------------------------------------------

    @Test
    void restoreUser_asHospitalAdmin_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(userService).restoreUser(any(UUID.class));

        mockMvc.perform(patch("/users/{id}/restore", id)
                .contentType(MediaType.APPLICATION_JSON)
                .with(SecurityMockMvcRequestPostProcessors.user("admin")
                    .authorities(AuthorityUtils.createAuthorityList("ROLE_HOSPITAL_ADMIN"))))
            .andExpect(status().isNoContent());
    }

    @Test
    void restoreUser_asSuperAdmin_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(userService).restoreUser(any(UUID.class));

        mockMvc.perform(patch("/users/{id}/restore", id)
                .contentType(MediaType.APPLICATION_JSON)
                .with(SecurityMockMvcRequestPostProcessors.user("superadmin")
                    .authorities(AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"))))
            .andExpect(status().isNoContent());
    }

    // -------------------------------------------------------------------------
    // includeDeleted — visibility follows restore rights
    // -------------------------------------------------------------------------

    private void authenticateAs(String username, String... authorities) {
        // Directly on the holders: the slice runs with addFilters=false, so
        // neither request.getUserPrincipal() nor the request-post-processor
        // route reaches the controller. The super-admin signal canSeeDeleted()
        // reads is RoleValidator's, stubbed as it answers for such a token.
        authenticateAs(username,
            java.util.Arrays.asList(authorities).contains("ROLE_SUPER_ADMIN"), authorities);
    }

    private void authenticateAs(String username, boolean superAdminFlag, String... authorities) {
        org.springframework.security.core.context.SecurityContextHolder.getContext()
            .setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                    username, "n/a", AuthorityUtils.createAuthorityList(authorities)));
        when(roleValidator.isSuperAdminFromJwtClaim()).thenReturn(superAdminFlag);
    }

    @org.junit.jupiter.api.AfterEach
    void clearSecurityContext() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    void inflatedSuperAdminAuthority_withoutTheVerifiedFlag_getsTheLiveView() throws Exception {
        // canSeeDeleted() asks RoleValidator's super-admin signal, not the
        // authorities collection.
        when(userService.getAllUsers(0, 10, false, false))
                .thenReturn(org.springframework.data.domain.Page.empty());
        authenticateAs("impostor", false, "ROLE_SUPER_ADMIN");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/users").param("onlyDeleted", "true"))
            .andExpect(status().isOk());

        verify(userService).getAllUsers(0, 10, false, false);
    }

    @Test
    void includeDeleted_isHonouredForSuperAdmin() throws Exception {
        when(userService.getAllUsers(0, 10, true, false))
                .thenReturn(org.springframework.data.domain.Page.empty());
        authenticateAs("superadmin", "ROLE_SUPER_ADMIN");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/users").param("includeDeleted", "true"))
            .andExpect(status().isOk());

        verify(userService).getAllUsers(0, 10, true, false);
    }

    @Test
    void onlyDeleted_pagesTheGhostWorklistForSuperAdmin() throws Exception {
        when(userService.getAllUsers(0, 10, false, true))
                .thenReturn(org.springframework.data.domain.Page.empty());
        authenticateAs("superadmin", "ROLE_SUPER_ADMIN");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/users").param("onlyDeleted", "true"))
            .andExpect(status().isOk());

        verify(userService).getAllUsers(0, 10, false, true);
    }

    @Test
    void includeDeleted_isSilentlyIgnoredForHospitalAdmin() throws Exception {
        // The user directory is global, so honouring this for a
        // hospital-scoped admin would let one tenant enumerate another
        // tenant's deleted identities. Silently the live view - the
        // parameter is a capability, not a promise.
        when(userService.getAllUsers(0, 10, false, false))
                .thenReturn(org.springframework.data.domain.Page.empty());
        authenticateAs("hadmin", "ROLE_HOSPITAL_ADMIN");

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .get("/users").param("includeDeleted", "true").param("onlyDeleted", "true"))
            .andExpect(status().isOk());

        verify(userService).getAllUsers(0, 10, false, false);
    }

    @Test
    void restoreUser_serviceThrows_propagatesError() throws Exception {
        UUID id = UUID.randomUUID();
        doThrow(new com.example.hms.exception.ResourceNotFoundException("User not found: " + id))
                .when(userService).restoreUser(any(UUID.class));

        mockMvc.perform(patch("/users/{id}/restore", id)
                .contentType(MediaType.APPLICATION_JSON)
                .with(SecurityMockMvcRequestPostProcessors.user("superadmin")
                    .authorities(AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN"))))
            .andExpect(status().isNotFound());
    }
}
