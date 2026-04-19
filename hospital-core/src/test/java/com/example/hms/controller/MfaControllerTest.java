package com.example.hms.controller;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.UserCredentialLifecycleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = MfaController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
class MfaControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MfaService mfaService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private AuditEventLogService auditEventLogService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private UserCredentialLifecycleService userCredentialLifecycleService;

    @MockitoBean
    private UserRoleHospitalAssignmentRepository assignmentRepository;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USERNAME = "doctor@hms.com";
    private User testUser;

    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(USER_ID);
        testUser.setUsername(USERNAME);

        // Set up authenticated principal
        UserDetails principal = org.springframework.security.core.userdetails.User
                .withUsername(USERNAME)
                .password("ignored")
                .authorities("ROLE_DOCTOR")
                .build();
        var auth = new UsernamePasswordAuthenticationToken(
                principal, null, AuthorityUtils.createAuthorityList("ROLE_DOCTOR"));
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(userRepository.findByUsername(USERNAME)).thenReturn(Optional.of(testUser));
    }

    // ── Enroll ──

    @Test
    void enroll_returnsSecretAndBackupCodes() throws Exception {
        var result = new MfaService.MfaEnrollmentResult(
                UUID.randomUUID(), "BASE32SECRET", "otpauth://totp/HMS:doctor@hms.com?secret=BASE32SECRET",
                "data:image/png;base64,fake",
                List.of("AAAA1111", "BBBB2222"));

        when(mfaService.enrollTotp(any(User.class))).thenReturn(result);

        mockMvc.perform(post("/api/auth/mfa/enroll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("BASE32SECRET"))
                .andExpect(jsonPath("$.otpauthUri").isNotEmpty())
                .andExpect(jsonPath("$.backupCodes").isArray())
                .andExpect(jsonPath("$.backupCodes.length()").value(2));
    }

    // ── Verify Enrollment ──

    @Test
    void verifyEnrollment_validCode_returnsSuccess() throws Exception {
        when(mfaService.verifyEnrollment(USER_ID, "123456")).thenReturn(true);

        mockMvc.perform(post("/api/auth/mfa/verify-enrollment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("MFA enrollment verified successfully."));
    }

    @Test
    void verifyEnrollment_invalidCode_returnsBadRequest() throws Exception {
        when(mfaService.verifyEnrollment(USER_ID, "000000")).thenReturn(false);

        mockMvc.perform(post("/api/auth/mfa/verify-enrollment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid TOTP code."));
    }

    // ── Status ──

    @Test
    void status_returnsMfaEnabled() throws Exception {
        when(mfaService.isMfaEnabled(USER_ID)).thenReturn(true);

        mockMvc.perform(get("/api/auth/mfa/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(true));
    }

    @Test
    void status_returnsMfaDisabled() throws Exception {
        when(mfaService.isMfaEnabled(USER_ID)).thenReturn(false);

        mockMvc.perform(get("/api/auth/mfa/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
    }

    // ── Verify Login (MFA challenge) ──

    @Test
    void verifyMfa_validCode_returnsFullJwt() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.verifyCode(USER_ID, "654321")).thenReturn(true);
        when(assignmentRepository.findByUser_IdAndActiveTrue(USER_ID)).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(TokenUserDescriptor.class))).thenReturn("access-jwt");
        when(jwtTokenProvider.generateRefreshToken(any(TokenUserDescriptor.class))).thenReturn("refresh-jwt");
        when(jwtTokenProvider.resolvePreferredRole(any())).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-jwt"));
    }

    @Test
    void verifyMfa_invalidMfaToken_returns401() throws Exception {
        when(jwtTokenProvider.isMfaToken("expired-token")).thenReturn(false);

        mockMvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"expired-token\",\"code\":\"654321\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired MFA token."));
    }

    @Test
    void verifyMfa_invalidCode_returns401() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.verifyCode(USER_ID, "999999")).thenReturn(false);
        when(mfaService.verifyBackupCode(USER_ID, "999999")).thenReturn(false);

        mockMvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"999999\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid MFA code."));
    }

    @Test
    void verifyMfa_backupCode_succeeds() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.verifyCode(USER_ID, "BACKUP01")).thenReturn(false);
        when(mfaService.verifyBackupCode(USER_ID, "BACKUP01")).thenReturn(true);
        when(assignmentRepository.findByUser_IdAndActiveTrue(USER_ID)).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(TokenUserDescriptor.class))).thenReturn("access-jwt");
        when(jwtTokenProvider.generateRefreshToken(any(TokenUserDescriptor.class))).thenReturn("refresh-jwt");
        when(jwtTokenProvider.resolvePreferredRole(any())).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(post("/api/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"BACKUP01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"));
    }
}
