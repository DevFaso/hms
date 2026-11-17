package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.model.Hospital;
import com.example.hms.model.PasswordResetToken;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.HospitalFixtures;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PasswordResetTokenRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.security.RefreshTokenCookieService;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import com.example.hms.service.EmailService;
import com.example.hms.service.SmsService;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * X3: real authentication, signing/parsing, filter chain, persisted account state and
 * reset transactions. Only delivery gateways are mocks. No @WithMockUser, fake
 * AuthenticationManager or disabled filters. The test-profile stores are local;
 * this does not claim deployed Redis or OIDC-provider evidence.
 */
@AutoConfigureMockMvc
@Import({OidcResourceServerIntegrationTest.OidcTestConfig.class, AccountLifecycleSecurityIT.LocalOidc.class})
class AccountLifecycleSecurityIT extends BaseIT {
    private static final String PASSWORD = "Original-Lifecycle-2026!";
    private static final String REPLACEMENT = "Recovered-Lifecycle-2026!";
    private static final SecretKey SIGNING_KEY = Jwts.SIG.HS256.key().build();
    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final Pattern LINK_TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    @DynamicPropertySource
    static void processLocalSigningKey(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "base64:" + Base64.getEncoder().encodeToString(SIGNING_KEY.getEncoded()));
    }

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private HospitalRepository hospitals;
    @Autowired private OrganizationRepository organizations;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private PasswordResetTokenRepository resets;
    @Autowired private PasswordEncoder encoder;
    @Autowired private LoginAttemptService attempts;
    @Autowired private KeycloakJwtFixture keycloak;
    @Autowired private List<RequestMappingHandlerMapping> handlerMappings;
    @MockitoBean private EmailService email;
    @MockitoBean private SmsService sms;

    private Hospital hospital;
    private String clientIp;

    @BeforeEach
    void fixtureHospital() {
        clientIp = "192.0.2." + SEQUENCE.incrementAndGet();
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var organization = organizations.save(HospitalFixtures.organization("X3 Network " + suffix, "X3O" + suffix));
        hospital = hospitals.save(HospitalFixtures.hospital(organization, "X3 Hospital " + suffix, "X3H" + suffix));
        when(sms.deliversRealSms()).thenReturn(true);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void logoutRevokesRealAccessAndRefreshTokensForWebAndMobile(boolean cookie) throws Exception {
        User user = account(false);
        Session session = login(user, PASSWORD);
        protectedRead(user, session.access()).andExpect(status().isOk());
        // Prove the refresh path works too, then revoke its newly rotated pair.
        session = refreshed(session.refresh());
        protectedRead(user, session.access()).andExpect(status().isOk());

        var logout = postApi("/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + session.access());
        if (cookie) logout.cookie(new Cookie(RefreshTokenCookieService.COOKIE_NAME, session.refresh()));
        else logout.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("refreshToken", session.refresh())));
        var response = mvc.perform(logout).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeaders(HttpHeaders.SET_COOKIE)).anySatisfy(value ->
            assertThat(value).contains(RefreshTokenCookieService.COOKIE_NAME + "=", "Max-Age=0", "HttpOnly"));

        Session revoked = session;
        assertAll("both credentials must be unusable after logout",
            () -> protectedRead(user, revoked.access()).andExpect(status().isUnauthorized()),
            () -> refresh(revoked.refresh()).andExpect(status().isUnauthorized()));
        // Repeated sign-out cannot revive either credential.
        mvc.perform(logout).andExpect(status().isOk());
        refresh(session.refresh()).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @CsvSource({"false,CURRENT", "true,CURRENT", "false,PASSWORD_LEGACY", "true,PASSWORD_LEGACY",
        "false,AUTH_LEGACY", "true,AUTH_LEGACY"})
    void emailAndSmsResetChangeThePasswordConsumeTheLinkAndRevokeOldTokens(boolean phoneOnly, ResetRoute route) throws Exception {
        User user = account(phoneOnly);
        Session session = login(user, PASSWORD);
        String oldAccess = backdated(session.access());
        String oldRefresh = backdated(session.refresh());
        protectedRead(user, oldAccess).andExpect(status().isOk());

        // The existing lockout threshold is exercised through real failed HTTP logins.
        for (int i = 0; i < 5; i++) badLogin(user, "Wrong-Lifecycle-2026!");
        assertThat(attempts.isLocked(user.getUsername())).isTrue();
        String raw = requestLink(user, phoneOnly, route);
        var before = resets.findByTokenHash(hash(raw)).orElseThrow();
        assertThat(before.getConsumedAt()).isNull();
        assertThat(before.getTokenHash()).isNotEqualTo(raw);

        confirm(raw, REPLACEMENT, route).andExpect(status().isNoContent());
        assertThat(encoder.matches(REPLACEMENT, users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        var consumed = resets.findByTokenHash(hash(raw)).orElseThrow().getConsumedAt();
        assertThat(consumed).isNotNull();
        assertThat(attempts.isLocked(user.getUsername())).isFalse();
        protectedRead(user, oldAccess).andExpect(status().isUnauthorized());
        refresh(oldRefresh).andExpect(status().isUnauthorized());
        badLogin(user, PASSWORD).andExpect(status().isUnauthorized());
        Session recovered = login(user, REPLACEMENT);
        protectedRead(user, recovered.access()).andExpect(status().isOk());

        // A uniform 204 on a spent link must not be mistaken for a second successful reset.
        confirm(raw, "Replay-Lifecycle-2026!", route).andExpect(status().isNoContent());
        assertThat(resets.findByTokenHash(hash(raw)).orElseThrow().getConsumedAt()).isEqualTo(consumed);
        assertThat(encoder.matches(REPLACEMENT, users.findById(user.getId()).orElseThrow().getPasswordHash())).isTrue();
        login(user, REPLACEMENT);
    }

    @Test
    void expiredAndUnknownResetLinksDoNotChangeCredentialsOrConsumeAnIssuedLink() throws Exception {
        User user = account(false);
        String raw = requestLink(user, false);
        PasswordResetToken expired = resets.findByTokenHash(hash(raw)).orElseThrow();
        expired.setExpiration(LocalDateTime.now().minusMinutes(1));
        resets.saveAndFlush(expired);
        String originalHash = users.findById(user.getId()).orElseThrow().getPasswordHash();

        confirm(raw, REPLACEMENT).andExpect(status().isNoContent());
        confirm(UUID.randomUUID().toString(), REPLACEMENT).andExpect(status().isNoContent());
        assertThat(users.findById(user.getId()).orElseThrow().getPasswordHash()).isEqualTo(originalHash);
        assertThat(resets.findByTokenHash(hash(raw)).orElseThrow().getConsumedAt()).isNull();
        login(user, PASSWORD);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void departedStaffCannotLogInRefreshOrUseTheirOldAccessToken(boolean deleted) throws Exception {
        User user = account(false);
        Session session = login(user, PASSWORD);
        protectedRead(user, session.access()).andExpect(status().isOk());
        String oidc = keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(OidcResourceServerIntegrationTest.TEST_ISSUER, OidcResourceServerIntegrationTest.TEST_AUDIENCE)
            .withRealmRoles(List.of("RECEPTIONIST")).linkedTo(user.getId(), user.getUsername()));
        protectedRead(user, oidc).andExpect(status().isOk());
        String raw = requestLink(user, false);
        // Persist the account state produced by administrator deactivation/soft deletion.
        user = users.findById(user.getId()).orElseThrow();
        user.setActive(false);
        user.setDeactivatedByAdminAt(LocalDateTime.now());
        user.setDeleted(deleted);
        users.saveAndFlush(user);

        badLogin(user, PASSWORD).andExpect(status().isUnauthorized());
        refresh(session.refresh()).andExpect(status().isUnauthorized());
        protectedRead(user, session.access()).andExpect(status().isUnauthorized());
        protectedRead(user, oidc).andExpect(status().isUnauthorized());
        // Account recovery must never reactivate a departed account.
        confirm(raw, REPLACEMENT).andExpect(status().isNoContent());
        assertThat(users.findById(user.getId()).orElseThrow().isActive()).isFalse();
        badLogin(user, REPLACEMENT).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @EnumSource(ResetRoute.class)
    void anonymousResetResponsesDoNotRevealWhetherAnAccountExists(ResetRoute route) throws Exception {
        User known = account(false);
        for (String identifier : new String[]{known.getEmail(), "unknown-" + UUID.randomUUID() + "@example.test"}) {
            var response = mvc.perform(resetRequest(identifier, route))
                .andExpect(status().isNoContent()).andReturn().getResponse();
            assertThat(response.getContentAsString()).isEmpty();
        }
    }

    @Test
    void everyResetMvcRouteIsIncludedInTheRoundTripInventory() {
        Set<String> expected = Arrays.stream(ResetRoute.values())
            .flatMap(route -> java.util.stream.Stream.of(route.request, route.confirm)).collect(Collectors.toSet());
        Set<String> actual = handlerMappings.stream().flatMap(mapping -> mapping.getHandlerMethods().entrySet().stream())
            .filter(entry -> entry.getValue().getBeanType() == com.example.hms.controller.PasswordResetController.class
                || Arrays.stream(entry.getValue().getMethod().getParameterTypes()).anyMatch(type ->
                    type == com.example.hms.payload.dto.PasswordResetRequestDTO.class
                        || type == com.example.hms.payload.dto.PasswordResetConfirmDTO.class))
            .flatMap(entry -> entry.getKey().getPatternValues().stream()).collect(Collectors.toSet());
        assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void unrelatedAccountEditsStillRequireCsrfAndWorkWithAValidCsrfToken() throws Exception {
        User user = account(false);
        Session session = login(user, PASSWORD);
        var edit = put("/api/users/" + user.getId()).contextPath("/api")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + session.access())
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                "username", user.getUsername(), "email", user.getEmail(), "phoneNumber", user.getPhoneNumber(),
                "firstName", "Updated", "lastName", user.getLastName())));

        mvc.perform(edit).andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_TOKEN_INVALID"));
        assertThat(users.findById(user.getId()).orElseThrow().getFirstName()).isEqualTo("Lifecycle");
        mvc.perform(edit.with(csrf())).andExpect(status().isOk());
        assertThat(users.findById(user.getId()).orElseThrow().getFirstName()).isEqualTo("Updated");
    }

    private User account(boolean phoneOnly) {
        String suffix = UUID.randomUUID().toString().substring(0, 12);
        User user = users.saveAndFlush(User.builder().username("x3-" + suffix)
            .passwordHash(encoder.encode(PASSWORD)).firstName("Lifecycle").lastName("Test")
            .email(phoneOnly ? null : "x3-" + suffix + "@example.test")
            .phoneNumber(String.format("+22670%06d", SEQUENCE.incrementAndGet()))
            .passwordChangedAt(LocalDateTime.now().minusMinutes(10)).isActive(true).build());
        Role role = roles.findByCode("ROLE_RECEPTIONIST").orElseGet(() -> roles.save(Role.builder()
            .code("ROLE_RECEPTIONIST").name("Receptionist").build()));
        assignments.saveAndFlush(UserRoleHospitalAssignment.builder().user(user).hospital(hospital).role(role)
            .assignmentCode("X3-" + UUID.randomUUID()).active(true).startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now()).build());
        return user;
    }

    private Session login(User user, String password) throws Exception {
        return session(badLogin(user, password).andExpect(status().isOk()).andReturn());
    }

    private org.springframework.test.web.servlet.ResultActions badLogin(User user, String password) throws Exception {
        return mvc.perform(postApi("/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("username", user.getUsername(), "password", password))));
    }

    private org.springframework.test.web.servlet.ResultActions protectedRead(User user, String token) throws Exception {
        return mvc.perform(getApi("/users/" + user.getId()).header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions refresh(String token) throws Exception {
        return mvc.perform(postApi("/auth/token/refresh").contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("refreshToken", token))));
    }

    private Session refreshed(String token) throws Exception {
        return session(refresh(token).andExpect(status().isOk()).andReturn());
    }

    private Session session(MvcResult result) throws Exception {
        var body = json.readTree(result.getResponse().getContentAsString());
        assertThat(body.path("accessToken").asText()).isNotBlank();
        assertThat(body.path("refreshToken").asText()).isNotBlank();
        return new Session(body.path("accessToken").asText(), body.path("refreshToken").asText());
    }

    private String requestLink(User user, boolean phoneOnly) throws Exception {
        return requestLink(user, phoneOnly, ResetRoute.CURRENT);
    }

    private String requestLink(User user, boolean phoneOnly, ResetRoute route) throws Exception {
        String identifier = phoneOnly ? user.getPhoneNumber() : user.getEmail();
        mvc.perform(resetRequest(identifier, route)).andExpect(status().isNoContent());
        ArgumentCaptor<String> delivered = ArgumentCaptor.forClass(String.class);
        if (phoneOnly) verify(sms, timeout(10000)).send(eq(user.getPhoneNumber()), delivered.capture());
        else verify(email, timeout(10000)).sendPasswordResetEmail(eq(user.getEmail()), delivered.capture());
        var matcher = LINK_TOKEN.matcher(delivered.getValue());
        assertThat(matcher.find()).isTrue();
        String raw = matcher.group(1);
        await().atMost(Duration.ofSeconds(10)).until(() -> resets.findByTokenHash(hash(raw)).isPresent());
        return raw;
    }

    private MockHttpServletRequestBuilder resetRequest(String identifier, ResetRoute route) throws Exception {
        var request = postApi(route.request);
        if (route.query) request.param("identifier", identifier);
        else request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("identifier", identifier)));
        return request;
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String password) throws Exception {
        return confirm(token, password, ResetRoute.CURRENT);
    }

    private org.springframework.test.web.servlet.ResultActions confirm(String token, String password, ResetRoute route) throws Exception {
        return mvc.perform(postApi(route.confirm).contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(Map.of("token", token, "newPassword", password))));
    }

    /** Preserve the real signed claims and change only issue time, avoiding clock sleeps. */
    private String backdated(String token) {
        var claims = Jwts.parser().verifyWith(SIGNING_KEY).build().parseSignedClaims(token).getPayload();
        return Jwts.builder().claims(new HashMap<>(claims)).issuedAt(Date.from(Instant.now().minusSeconds(60)))
            .signWith(SIGNING_KEY).compact();
    }

    private static String hash(String token) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
    }

    private record Session(String access, String refresh) { }

    /** Enable the real local-account gate without configuring remote issuer discovery. */
    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    static class LocalOidc {
        @org.springframework.context.annotation.Bean
        com.example.hms.security.oidc.KeycloakHospitalContextFilter keycloakHospitalContextFilter(
                com.example.hms.security.oidc.KeycloakHospitalContextResolver resolver,
                com.example.hms.security.tenant.ActingScopeResolver actingScopeResolver,
                com.example.hms.security.IdleSessionGate idleSessionGate,
                com.example.hms.security.TenantLifecycleGate tenantLifecycleGate,
                UserRepository userRepository) {
            return new com.example.hms.security.oidc.KeycloakHospitalContextFilter(resolver,
                actingScopeResolver, idleSessionGate, tenantLifecycleGate, userRepository);
        }
    }

    private MockHttpServletRequestBuilder postApi(String path) {
        return post("/api" + path).contextPath("/api").with(request -> {
            request.setRemoteAddr(clientIp);
            return request;
        });
    }

    private MockHttpServletRequestBuilder getApi(String path) {
        return get("/api" + path).contextPath("/api").with(request -> {
            request.setRemoteAddr(clientIp);
            return request;
        });
    }

    private enum ResetRoute {
        CURRENT("/auth/password/request", "/auth/password/confirm", false),
        PASSWORD_LEGACY("/auth/password/request-reset", "/auth/password/reset-password", true),
        AUTH_LEGACY("/auth/request-reset", "/auth/reset-password", false);

        private final String request;
        private final String confirm;
        private final boolean query;

        ResetRoute(String request, String confirm, boolean query) {
            this.request = request;
            this.confirm = confirm;
            this.query = query;
        }
    }
}
