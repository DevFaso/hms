package com.example.hms.controller;

import com.example.hms.model.User;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.service.AuditEventLogService;
import com.example.hms.service.MfaService;
import com.example.hms.service.UserCredentialLifecycleService;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
import java.util.OptionalLong;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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

    @MockitoBean
    private com.example.hms.security.RefreshTokenCookieService refreshTokenCookieService;

    @MockitoBean
    private com.example.hms.security.IdleSessionGate idleSessionGate;

    @MockitoBean
    private com.example.hms.security.TokenBlacklistService tokenBlacklistService;

    @MockitoBean
    private com.example.hms.security.GlobalSessionRevocationService globalSessionRevocationService;

    private static final UUID USER_ID = UUID.randomUUID();
    private static final String USERNAME = "doctor@hms.com";
    private User testUser;

    @BeforeEach
    void setUp() {
        // The user's wrong-code budget is not exhausted unless a test says so.
        when(mfaService.reserveCodeAttempt(any())).thenReturn(true);

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
                List.of("AAAA1111", "BBBB2222"), false);

        when(mfaService.enrollTotp(any(User.class), any(), org.mockito.ArgumentMatchers.isNull())).thenReturn(result);

        mockMvc.perform(post("/auth/mfa/enroll"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("BASE32SECRET"))
                .andExpect(jsonPath("$.otpauthUri").isNotEmpty())
                .andExpect(jsonPath("$.backupCodes").isArray())
                .andExpect(jsonPath("$.backupCodes.length()").value(2));
    }

    // ── Verify Enrollment ──

    @Test
    void verifyEnrollment_validCode_returnsSuccess() throws Exception {
        when(mfaService.verifyEnrollment(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq("123456"), any()))
                .thenReturn(true);

        mockMvc.perform(post("/auth/mfa/verify-enrollment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("MFA enrollment verified successfully."));
    }

    @Test
    void verifyEnrollment_invalidCode_returnsBadRequest() throws Exception {
        when(mfaService.verifyEnrollment(org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq("000000"), any()))
                .thenReturn(false);

        mockMvc.perform(post("/auth/mfa/verify-enrollment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid TOTP code."));
    }

    // ── Status ──

    @Test
    void status_returnsMfaEnabled() throws Exception {
        when(mfaService.isMfaEnabled(USER_ID)).thenReturn(true);

        mockMvc.perform(get("/auth/mfa/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(true));
    }

    @Test
    void status_returnsMfaDisabled() throws Exception {
        when(mfaService.isMfaEnabled(USER_ID)).thenReturn(false);

        mockMvc.perform(get("/auth/mfa/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mfaEnabled").value(false));
    }

    // ── Verify Login (MFA challenge) ──


    /** A current TOTP code of time-step 42, which this sign-in can still claim. */
    private void currentTotp(String code) {
        when(mfaService.currentTotpStep(USER_ID, code)).thenReturn(OptionalLong.of(42L));
        when(mfaService.claimTotpStep(USER_ID, 42L)).thenReturn(true);
    }

    @Test
    void verifyMfa_validCode_returnsFullJwt() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        claimable("mfa-tok-123");
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        currentTotp("654321");
        when(assignmentRepository.findByUser_IdAndActiveTrue(USER_ID)).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(TokenUserDescriptor.class))).thenReturn("access-jwt");
        when(jwtTokenProvider.generateRefreshToken(any(TokenUserDescriptor.class))).thenReturn("refresh-jwt");
        when(jwtTokenProvider.resolvePreferredRole(any())).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"))
                .andExpect(jsonPath("$.refreshToken").value("refresh-jwt"));
    }

    @Test
    void verifyMfa_invalidMfaToken_returns401() throws Exception {
        when(jwtTokenProvider.isMfaToken("expired-token")).thenReturn(false);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"expired-token\",\"code\":\"654321\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired MFA token."));
    }

    @Test
    void verifyMfa_invalidCode_returns401() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.matchingBackupCode(USER_ID, "999999")).thenReturn(Optional.empty());

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"999999\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid MFA code."));
    }

    @Test
    void verifyMfa_backupCode_succeeds() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        claimable("mfa-tok-123");
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        UUID backupId = UUID.randomUUID();
        when(mfaService.matchingBackupCode(USER_ID, "BACKUP01")).thenReturn(Optional.of(backupId));
        when(mfaService.spendBackupCode(backupId)).thenReturn(true);
        when(assignmentRepository.findByUser_IdAndActiveTrue(USER_ID)).thenReturn(List.of());
        when(jwtTokenProvider.generateAccessToken(any(TokenUserDescriptor.class))).thenReturn("access-jwt");
        when(jwtTokenProvider.generateRefreshToken(any(TokenUserDescriptor.class))).thenReturn("refresh-jwt");
        when(jwtTokenProvider.resolvePreferredRole(any())).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"BACKUP01\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-jwt"));
    }

    // ── The challenge token is single-use ──

    @Test
    void verifyMfa_spentChallenge_returns401_beforeAnyCodeIsTried() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("mfa-tok-123")).thenReturn("jti-1");
        when(tokenBlacklistService.isBlacklisted("jti-1")).thenReturn(true);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired MFA token."));
        verifyNoInteractions(mfaService);
    }

    @Test
    void verifyMfa_challengeIssuedBeforeAForceLogout_returns401() throws Exception {
        java.util.Date issuedAt = new java.util.Date();
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getIssuedAt("mfa-tok-123")).thenReturn(issuedAt);
        when(globalSessionRevocationService.revokes(issuedAt)).thenReturn(true);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(mfaService);
    }

    /** The challenge has a jti, and this verify is the one that claims it. */
    private java.util.Date claimable(String challenge) {
        java.util.Date exp = new java.util.Date(System.currentTimeMillis() + 300_000);
        when(jwtTokenProvider.getJtiFromToken(challenge)).thenReturn("jti-1");
        when(jwtTokenProvider.getExpiration(challenge)).thenReturn(exp);
        when(tokenBlacklistService.claim("jti-1", exp.getTime())).thenReturn(true);
        return exp;
    }

    @Test
    void verifyMfa_success_spendsTheChallenge() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        java.util.Date exp = claimable("mfa-tok-123");
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        currentTotp("654321");
        when(assignmentRepository.findByUser_IdAndActiveTrue(USER_ID)).thenReturn(List.of());
        when(jwtTokenProvider.resolvePreferredRole(any())).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isOk());
        verify(tokenBlacklistService).claim("jti-1", exp.getTime());
    }

    @Test
    void verifyMfa_wrongCode_doesNotSpendTheChallenge() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("mfa-tok-123")).thenReturn("jti-1");
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"999999\"}"))
                .andExpect(status().isUnauthorized());
        verify(tokenBlacklistService, never())
                .claim(any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void verifyMfa_aChallengeAnotherVerifyClaimedFirst_mintsNothing() throws Exception {
        java.util.Date exp = new java.util.Date(System.currentTimeMillis() + 300_000);
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("mfa-tok-123")).thenReturn("jti-1");
        when(jwtTokenProvider.getExpiration("mfa-tok-123")).thenReturn(exp);
        when(tokenBlacklistService.claim("jti-1", exp.getTime())).thenReturn(false);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        currentTotp("654321");

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isUnauthorized());
        verify(jwtTokenProvider, never()).generateAccessToken(any(TokenUserDescriptor.class));
    }

    @Test
    void verifyMfa_aLostClaim_spendsNoBackupCodeAndRecordsNoTotpUse() throws Exception {
        java.util.Date exp = new java.util.Date(System.currentTimeMillis() + 300_000);
        UUID backupId = UUID.randomUUID();
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getJtiFromToken("mfa-tok-123")).thenReturn("jti-1");
        when(jwtTokenProvider.getExpiration("mfa-tok-123")).thenReturn(exp);
        when(tokenBlacklistService.claim("jti-1", exp.getTime())).thenReturn(false);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.matchingBackupCode(USER_ID, "12345678")).thenReturn(Optional.of(backupId));

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"12345678\"}"))
                .andExpect(status().isUnauthorized());

        verify(mfaService, never()).spendBackupCode(any());
        verify(mfaService, never()).claimTotpStep(any(), anyLong());
    }

    @Test
    void verifyMfa_aBackupCodeSpentByAnotherRequestInBetween_signsNobodyIn() throws Exception {
        UUID backupId = UUID.randomUUID();
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        claimable("mfa-tok-123");
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.matchingBackupCode(USER_ID, "12345678")).thenReturn(Optional.of(backupId));
        when(mfaService.spendBackupCode(backupId)).thenReturn(false);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"12345678\"}"))
                .andExpect(status().isUnauthorized())
                // The challenge is spent: "invalid code" would invite a retry that cannot work.
                .andExpect(jsonPath("$.message").value("That code was just used. Sign in again."));
        verify(jwtTokenProvider, never()).generateAccessToken(any(TokenUserDescriptor.class));
    }

    // ── Round 4: replay and the per-user budget ──

    @Test
    void verifyMfa_aTotpCodeAlreadyAccepted_signsNobodyIn() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        claimable("mfa-tok-123");
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.currentTotpStep(USER_ID, "654321")).thenReturn(OptionalLong.of(42L));
        when(mfaService.claimTotpStep(USER_ID, 42L)).thenReturn(false);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("That code was just used. Sign in again."));
        verify(jwtTokenProvider, never()).generateAccessToken(any(TokenUserDescriptor.class));
    }

    @Test
    void verifyMfa_theUsersWrongCodeBudgetSpent_429_beforeAnyCodeIsChecked() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        when(mfaService.reserveCodeAttempt(USER_ID)).thenReturn(false);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isTooManyRequests());
        verify(mfaService, never()).currentTotpStep(any(), any());
        verify(mfaService, never()).matchingBackupCode(any(), any());
        verify(tokenBlacklistService, never()).claim(any(), anyLong());
    }

    @Test
    void verifyMfa_success_clearsTheWrongCodeCount() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        claimable("mfa-tok-123");
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        currentTotp("654321");
        when(assignmentRepository.findByUser_IdAndActiveTrue(USER_ID)).thenReturn(List.of());
        when(jwtTokenProvider.resolvePreferredRole(any())).thenReturn("ROLE_DOCTOR");

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isOk());
        verify(mfaService).clearCodeAttempts(USER_ID);
    }

    @Test
    void verifyMfa_aWrongCode_keepsTheWrongCodeCount() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"999999\"}"))
                .andExpect(status().isUnauthorized());
        verify(mfaService).reserveCodeAttempt(USER_ID);
        verify(mfaService, never()).clearCodeAttempts(any());
    }

    @Test
    void verifyMfa_aChallengeThatExpiresWhileTheCodeIsChecked_is401_not500() throws Exception {
        when(jwtTokenProvider.isMfaToken("mfa-tok-123")).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT("mfa-tok-123")).thenReturn(USERNAME);
        currentTotp("654321");
        // isSpentOrRevoked read it fine; by the claim, it has expired.
        when(jwtTokenProvider.getJtiFromToken("mfa-tok-123"))
                .thenReturn(null)
                .thenThrow(new io.jsonwebtoken.ExpiredJwtException(null, null, "expired"));

        mockMvc.perform(post("/auth/mfa/verify")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mfaToken\":\"mfa-tok-123\",\"code\":\"654321\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid or expired MFA token."));
    }
}
