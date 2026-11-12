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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@AutoConfigureMockMvc(addFilters = false)
@WebMvcTest(
    controllers = MfaController.class,
    excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.example\\.hms\\.security\\..*"
    )
)
@org.springframework.context.annotation.Import(MfaEnrollmentLoginIntegrationTest.LocalMfa.class)
class MfaEnrollmentLoginIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;
    @Autowired private org.springframework.context.MessageSource messageSource;

    @Autowired
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

    @MockitoBean
    private com.example.hms.security.LoginAttemptService loginAttemptService;

    @MockitoBean
    private com.example.hms.security.HospitalUserDetailsService userDetailsService;


    @MockitoBean private com.example.hms.repository.UserMfaEnrollmentRepository enrollments;
    @MockitoBean private com.example.hms.repository.MfaBackupCodeRepository backupCodes;
    @MockitoBean private com.example.hms.security.DowntimeStateService downtime;
    @MockitoBean private com.example.hms.security.provider.ProviderMfaGate providerGate;

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class LocalMfa {
        @org.springframework.context.annotation.Bean
        MfaService realMfa(com.example.hms.repository.UserMfaEnrollmentRepository enrollments,
                          com.example.hms.repository.MfaBackupCodeRepository backups,
                          AuditEventLogService audit) {
            return new MfaService(enrollments, backups,
                new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(), audit,
                new com.example.hms.security.InMemoryLoginAttemptStore());
        }
    }

    private static final long FIXED_STEP = 60_000_000L;
    private final dev.samstevens.totp.code.CodeGenerator codes = new dev.samstevens.totp.code.DefaultCodeGenerator(
        dev.samstevens.totp.code.HashingAlgorithm.SHA1, 6);
    private final java.util.concurrent.atomic.AtomicReference<com.example.hms.model.UserMfaEnrollment> saved =
        new java.util.concurrent.atomic.AtomicReference<>();
    private final java.util.Set<String> spentChallenges = new java.util.HashSet<>();
    private User user;

    @BeforeEach
    void localOnlyCollaborators() {
        user = new User();
        user.setId(UUID.randomUUID());
        user.setUsername("cdx-mfa-admin-" + user.getId());
        user.setActive(true);
        saved.set(null);
        spentChallenges.clear();
        when(userRepository.findByUsername(user.getUsername())).thenReturn(Optional.of(user));
        when(downtime.snapshot()).thenReturn(com.example.hms.security.DowntimeStateService.DowntimeSnapshot.NORMAL);
        var principal = org.springframework.security.core.userdetails.User.withUsername(user.getUsername())
            .password("cdx-not-a-live-password").authorities("ROLE_SUPER_ADMIN").build();
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            principal, null, AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN")));
        when(enrollments.lockByUserIdAndMethod(user.getId(), com.example.hms.enums.MfaMethodType.TOTP))
            .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(enrollments.findByUserIdAndMethod(user.getId(), com.example.hms.enums.MfaMethodType.TOTP))
            .thenAnswer(invocation -> Optional.ofNullable(saved.get()));
        when(enrollments.save(any())).thenAnswer(invocation -> {
            com.example.hms.model.UserMfaEnrollment enrollment = invocation.getArgument(0);
            if (enrollment.getId() == null) enrollment.setId(UUID.randomUUID());
            saved.set(enrollment);
            return enrollment;
        });
        when(enrollments.claimTotpStep(org.mockito.ArgumentMatchers.eq(user.getId()),
            org.mockito.ArgumentMatchers.eq(com.example.hms.enums.MfaMethodType.TOTP), anyLong(), any()))
            .thenAnswer(invocation -> {
                long step = invocation.getArgument(2);
                var enrollment = saved.get();
                if (enrollment == null || (enrollment.getLastTotpStep() != null && enrollment.getLastTotpStep() >= step)) return 0;
                enrollment.setLastTotpStep(step);
                return 1;
            });
        org.springframework.test.util.ReflectionTestUtils.setField(mfaService, "timeProvider",
            (dev.samstevens.totp.time.TimeProvider) () -> FIXED_STEP * 30);
        when(jwtTokenProvider.isMfaToken(any())).thenReturn(true);
        when(jwtTokenProvider.getUsernameFromJWT(any())).thenReturn(user.getUsername());
        when(jwtTokenProvider.getJtiFromToken(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtTokenProvider.getExpiration(any())).thenReturn(new java.util.Date(4_102_444_800_000L));
        when(jwtTokenProvider.mfaTokenSelectedRoles(any())).thenReturn(Optional.of(List.of("ROLE_SUPER_ADMIN")));
        var details = mock(com.example.hms.security.HospitalUserDetails.class);
        doReturn(AuthorityUtils.createAuthorityList("ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN")).when(details).getAuthorities();
        when(userDetailsService.loadUserByUsername(user.getUsername())).thenReturn(details);
        when(tokenBlacklistService.isBlacklisted(any())).thenAnswer(invocation -> spentChallenges.contains(invocation.getArgument(0)));
        when(tokenBlacklistService.claim(any(), anyLong())).thenAnswer(invocation -> spentChallenges.add(invocation.getArgument(0)));
        when(jwtTokenProvider.generateAccessToken(any(TokenUserDescriptor.class), any(java.time.Instant.class))).thenReturn("cdx-full-access");
        when(jwtTokenProvider.generateRefreshToken(any(TokenUserDescriptor.class), any(java.time.Instant.class))).thenReturn("cdx-full-refresh");
    }

    @org.junit.jupiter.api.AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    private String enroll() throws Exception {
        String response = mockMvc.perform(post("/auth/mfa/enroll")).andExpect(status().isOk())
            .andExpect(jsonPath("$.secret").isNotEmpty()).andReturn().getResponse().getContentAsString();
        String secret = objectMapper.readTree(response).get("secret").asText();
        org.assertj.core.api.Assertions.assertThat(saved.get().isVerified()).isFalse();
        return secret;
    }

    private org.springframework.test.web.servlet.ResultActions verifyLogin(String challenge, String code) throws Exception {
        return mockMvc.perform(post("/auth/mfa/verify").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(java.util.Map.of("mfaToken", challenge, "code", code))));
    }

    private void confirm(String secret) throws Exception {
        mockMvc.perform(post("/auth/mfa/verify-enrollment").contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(java.util.Map.of("code", codes.generate(secret, FIXED_STEP - 1)))))
            .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(saved.get().isEnabled()).isTrue();
    }

    private org.springframework.test.web.servlet.ResultActions refused(String token, String code, String reason) throws Exception {
        return verifyLogin(token, code).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(
            messageSource.getMessage(reason, null, org.springframework.context.i18n.LocaleContextHolder.getLocale())));
    }

    private void noSessionIssued() {
        verify(jwtTokenProvider, never()).generateAccessToken(any(TokenUserDescriptor.class), any(java.time.Instant.class));
        verify(jwtTokenProvider, never()).generateRefreshToken(any(TokenUserDescriptor.class), any(java.time.Instant.class));
        verify(refreshTokenCookieService, never()).write(any(), any(), anyLong());
    }

    @Test
    void enrollmentThenSuperAdminLoginAcceptsTheCodeOnceAndPreservesThePickedRole() throws Exception {
        String secret = enroll();
        confirm(secret);
        String current = codes.generate(secret, FIXED_STEP);
        long loginStep = mfaService.currentTotpStep(user.getId(), current).orElseThrow();
        verifyLogin("cdx-challenge-one", current).andExpect(status().isOk())
            .andExpect(jsonPath("$.accessToken").value("cdx-full-access"))
            .andExpect(jsonPath("$.roles.length()").value(1))
            .andExpect(jsonPath("$.roles[0]").value("ROLE_SUPER_ADMIN"));
        refused("cdx-challenge-two", current, "mfa.login.invalidCode")
            .andExpect(jsonPath("$.accessToken").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(spentChallenges).containsExactly("cdx-challenge-one");
        org.springframework.test.util.ReflectionTestUtils.setField(mfaService, "timeProvider",
            (dev.samstevens.totp.time.TimeProvider) () -> (FIXED_STEP + 2) * 30);
        String fresh = codes.generate(secret, FIXED_STEP + 2);
        org.assertj.core.api.Assertions.assertThat(mfaService.currentTotpStep(user.getId(), fresh).orElseThrow())
            .isGreaterThan(loginStep);
        refused("cdx-challenge-one", fresh, "mfa.token.invalid");
        var descriptor = org.mockito.ArgumentCaptor.forClass(TokenUserDescriptor.class);
        verify(jwtTokenProvider, org.mockito.Mockito.times(1)).generateAccessToken(descriptor.capture(), any(java.time.Instant.class));
        verify(jwtTokenProvider, org.mockito.Mockito.times(1)).generateRefreshToken(any(TokenUserDescriptor.class), any(java.time.Instant.class));
        org.assertj.core.api.Assertions.assertThat(descriptor.getValue().roles()).containsExactly("ROLE_SUPER_ADMIN");
        org.assertj.core.api.Assertions.assertThat(saved.get().getLastTotpStep()).isEqualTo(loginStep);
    }

    @Test
    void wrongAndExpiredCodesNeverSpendTheChallengeOrMintAFullSession() throws Exception {
        String secret = enroll();
        confirm(secret);
        Long afterConfirm = saved.get().getLastTotpStep();
        java.util.Set<String> acceptable = new java.util.HashSet<>(java.util.List.of(codes.generate(secret, FIXED_STEP - 1),
            codes.generate(secret, FIXED_STEP), codes.generate(secret, FIXED_STEP + 1)));
        String wrong = "000000";
        while (acceptable.contains(wrong)) wrong = String.format(java.util.Locale.ROOT, "%06d", Integer.parseInt(wrong) + 1);
        String expired = codes.generate(secret, FIXED_STEP - 10);
        long oldStep = FIXED_STEP - 10;
        while (acceptable.contains(expired)) expired = codes.generate(secret, --oldStep);
        org.assertj.core.api.Assertions.assertThat(mfaService.currentTotpStep(user.getId(), wrong)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(mfaService.currentTotpStep(user.getId(), expired)).isEmpty();
        refused("cdx-challenge-wrong", wrong, "mfa.login.invalidCode");
        refused("cdx-challenge-expired", expired, "mfa.login.invalidCode");
        org.assertj.core.api.Assertions.assertThat(spentChallenges).isEmpty();
        org.assertj.core.api.Assertions.assertThat(saved.get().getLastTotpStep()).isEqualTo(afterConfirm);
        noSessionIssued();
    }

    @Test
    void anInvalidChallengeIsRefusedBeforeACurrentCodeCanBeSpent() throws Exception {
        String secret = enroll();
        confirm(secret);
        Long afterConfirm = saved.get().getLastTotpStep();
        when(jwtTokenProvider.isMfaToken("cdx-invalid-challenge")).thenReturn(false);
        refused("cdx-invalid-challenge", codes.generate(secret, FIXED_STEP), "mfa.token.invalid");
        org.assertj.core.api.Assertions.assertThat(spentChallenges).isEmpty();
        org.assertj.core.api.Assertions.assertThat(saved.get().getLastTotpStep()).isEqualTo(afterConfirm);
        noSessionIssued();
    }

    @Test
    void aCorrectlySignedExpiredChallengeIsRefusedByTheRealJwtParserBeforeSpendingTheCode() throws Exception {
        String secret = enroll();
        confirm(secret);
        Long afterConfirm = saved.get().getLastTotpStep();
        var key = io.jsonwebtoken.Jwts.SIG.HS256.key().build();
        var realTokens = new JwtTokenProvider(userDetailsService,
            mock(com.example.hms.security.auth.TenantRoleAssignmentAccessor.class));
        org.springframework.test.util.ReflectionTestUtils.setField(realTokens, "signingKey", key);
        org.springframework.test.util.ReflectionTestUtils.setField(realTokens, "verificationKey", key);
        String fresh = realTokens.generateMfaToken(user.getUsername(), List.of("ROLE_SUPER_ADMIN"));
        org.assertj.core.api.Assertions.assertThat(realTokens.isMfaToken(fresh)).isTrue();
        var claims = io.jsonwebtoken.Jwts.parser().verifyWith(key).build().parseSignedClaims(fresh).getPayload();
        String expired = io.jsonwebtoken.Jwts.builder().claims(claims)
            .expiration(java.util.Date.from(java.time.Instant.now().minusSeconds(300))).signWith(key).compact();
        org.assertj.core.api.Assertions.assertThat(realTokens.isMfaToken(expired)).isFalse();
        for (String token : List.of(fresh, expired)) {
            when(jwtTokenProvider.isMfaToken(token)).thenAnswer(i -> realTokens.isMfaToken(i.getArgument(0)));
            when(jwtTokenProvider.getUsernameFromJWT(token)).thenAnswer(i -> realTokens.getUsernameFromJWT(i.getArgument(0)));
            when(jwtTokenProvider.getJtiFromToken(token)).thenAnswer(i -> realTokens.getJtiFromToken(i.getArgument(0)));
            when(jwtTokenProvider.getExpiration(token)).thenAnswer(i -> realTokens.getExpiration(i.getArgument(0)));
            when(jwtTokenProvider.getIssuedAt(token)).thenAnswer(i -> realTokens.getIssuedAt(i.getArgument(0)));
            when(jwtTokenProvider.mfaTokenSelectedRoles(token)).thenAnswer(i -> realTokens.mfaTokenSelectedRoles(i.getArgument(0)));
        }
        refused(expired, codes.generate(secret, FIXED_STEP), "mfa.token.invalid");
        org.assertj.core.api.Assertions.assertThat(spentChallenges).isEmpty();
        org.assertj.core.api.Assertions.assertThat(saved.get().getLastTotpStep()).isEqualTo(afterConfirm);
        noSessionIssued();
        verifyLogin(fresh, codes.generate(secret, FIXED_STEP)).andExpect(status().isOk());
        verify(jwtTokenProvider).generateAccessToken(any(TokenUserDescriptor.class), any(java.time.Instant.class));
        verify(jwtTokenProvider).generateRefreshToken(any(TokenUserDescriptor.class), any(java.time.Instant.class));
    }
}
