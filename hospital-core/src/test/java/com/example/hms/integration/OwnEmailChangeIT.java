package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.enums.AuditStatus;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.AuditEventLog;
import com.example.hms.model.EmailChangeRequest;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Role;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.model.PasswordResetToken;
import com.example.hms.repository.EmailChangeRequestRepository;
import com.example.hms.repository.EmailChangeSendRepository;
import com.example.hms.repository.PasswordResetTokenRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.JwtTokenProvider;
import com.example.hms.security.LoginAttemptService;
import com.example.hms.security.TokenUserDescriptor;
import com.example.hms.security.oidc.KeycloakJwtFixture;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * {@code POST /auth/me/change-email} and {@code /confirm} through the REAL
 * security filter chain, with HMS-minted tokens and a Keycloak token. Test mail
 * is swallowed and the code only ever exists hashed, so the confirm tests plant
 * a known code hash on the pending row, as the mailbox would hold it.
 */
@AutoConfigureMockMvc
@Import(OidcResourceServerIntegrationTest.OidcTestConfig.class)
class OwnEmailChangeIT extends BaseIT {

    private static final AtomicInteger SEQUENCE = new AtomicInteger();
    private static final String PASSWORD = "Original-Pass-1";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JwtTokenProvider jwtTokenProvider;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private KeycloakJwtFixture keycloak;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private EmailChangeRequestRepository emailChangeRequestRepository;
    @Autowired private EmailChangeSendRepository emailChangeSendRepository;
    @Autowired private PasswordResetTokenRepository resetTokenRepository;
    @Autowired private LoginAttemptService loginAttemptService;
    @Autowired private PasswordEncoder passwordEncoder;

    private final List<UUID> createdUsers = new ArrayList<>();
    private final List<UUID> createdRoles = new ArrayList<>();

    private Organization organization;
    private Hospital hospital;
    private User nurse;
    private User patient;
    private User other;

    @BeforeEach
    void setUp() {
        organization = organizationRepository.save(Organization.builder()
            .name("Email Change Network " + next())
            .code("ECN" + next())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        String id = next();
        hospital = hospitalRepository.save(Hospital.builder()
            .name("Email Change Hospital " + id)
            .code("EC" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226557" + id)
            .email("email-change" + id + "@hospital.test")
            .organization(organization)
            .build());
        nurse = account("ecnur", "ROLE_NURSE");
        patient = account("ecpat", "ROLE_PATIENT");
        other = account("ecoth", "ROLE_NURSE");
    }

    @AfterEach
    void tearDown() {
        auditEventLogRepository.deleteAllInBatch();
        emailChangeSendRepository.deleteAllInBatch();
        for (UUID userId : createdUsers) {
            resetTokenRepository.deleteByUser_IdAndConsumedAtIsNull(userId);
        }
        emailChangeRequestRepository.deleteAll(emailChangeRequestRepository.findAll().stream()
            .filter(r -> createdUsers.contains(r.getUserId())).toList());
        for (UUID userId : createdUsers) {
            assignmentRepository.deleteAll(assignmentRepository.findByUserId(userId));
        }
        userRepository.deleteAllById(createdUsers);
        roleRepository.deleteAllById(createdRoles);
        hospitalRepository.delete(hospital);
        organizationRepository.delete(organization);
    }

    @Test
    @DisplayName("the change needs the current password AND the code sent to the new address; no address is echoed")
    void theChangeWaitsForTheCode() throws Exception {
        String token = hms(patient, "ROLE_PATIENT");
        String original = patient.getEmail();
        String typed = "  Awa" + next() + "@Self.Test ";
        String wanted = typed.trim().toLowerCase(Locale.ROOT);

        MvcResult wrong = perform(post("/auth/me/change-email"), token,
            Map.of("currentPassword", "Not-The-Password-1", "newEmail", typed));
        assertThat(wrong.getResponse().getStatus()).isEqualTo(400);
        assertNoAddressIn(wrong, original, wanted);
        List<AuditEventLog> refusals = failureRowsFor(patient);
        assertThat(refusals).hasSize(1);
        assertThat(String.valueOf(refusals.get(0).getEventDescription()) + refusals.get(0).getDetails())
            .doesNotContain(original).doesNotContain(wanted).doesNotContain("Not-The-Password");

        MvcResult requested = perform(post("/auth/me/change-email"), token,
            Map.of("currentPassword", PASSWORD, "newEmail", typed));
        assertThat(requested.getResponse().getStatus()).isEqualTo(200);
        assertNoAddressIn(requested, original, wanted);
        assertThat(body(requested).get("delivery").get(0).get("purpose").asText()).isEqualTo("EMAIL_CHANGE_CODE");
        assertThat(emailOf(patient)).isEqualTo(original);
        EmailChangeRequest pending = rowOf(patient);
        assertThat(pending.getPendingEmail()).isEqualTo(wanted);
        pending.setCodeHash(passwordEncoder.encode("424242"));
        emailChangeRequestRepository.save(pending);

        MvcResult wrongCode = perform(post("/auth/me/change-email/confirm"), token, Map.of("code", "000000"));
        assertThat(wrongCode.getResponse().getStatus()).isEqualTo(400);
        assertThat(emailOf(patient)).isEqualTo(original);

        MvcResult confirmed = perform(post("/auth/me/change-email/confirm"), token, Map.of("code", "424242"));
        assertThat(confirmed.getResponse().getStatus()).isEqualTo(200);
        assertNoAddressIn(confirmed, original, wanted);
        assertThat(emailOf(patient)).isEqualTo(wanted);
        // Spent: nothing is waiting any more, which is 410, so the client drops its form.
        assertThat(status(post("/auth/me/change-email/confirm"), token, Map.of("code", "424242"))).isEqualTo(410);
    }

    @Test
    @DisplayName("an address that already has an account gets the same answer as a free one, and can never be confirmed")
    void aTakenAddressIsNotRevealed() throws Exception {
        String token = hms(nurse, "ROLE_NURSE");
        String free = "free" + next() + "@self.test";

        MvcResult toFree = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"), token,
            Map.of("currentPassword", PASSWORD, "newEmail", free));
        MvcResult toTaken = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"), token,
            Map.of("currentPassword", PASSWORD, "newEmail", other.getEmail().toUpperCase(Locale.ROOT)));

        assertThat(toTaken.getResponse().getStatus()).isEqualTo(toFree.getResponse().getStatus()).isEqualTo(200);
        JsonNode a = body(toFree);
        JsonNode b = body(toTaken);
        assertThat(b.get("message")).isEqualTo(a.get("message"));
        assertThat(b.get("delivery").get(0).get("purpose")).isEqualTo(a.get("delivery").get(0).get("purpose"));
        assertThat(b.get("delivery").get(0).get("outcome")).isEqualTo(a.get("delivery").get(0).get("outcome"));
        assertNoAddressIn(toTaken, other.getEmail(), other.getUsername());

        // Whatever code is tried, the address is never applied.
        for (String code : new String[] {"000000", "123456", "424242", "999999"}) {
            assertThat(status(post("/auth/me/change-email/confirm"), token, Map.of("code", code))).isEqualTo(400);
        }
        assertThat(emailOf(nurse)).isEqualTo(nurse.getEmail());
        assertThat(emailOf(other)).isEqualTo(other.getEmail());
    }

    @Test
    @DisplayName("five wrong passwords lock the email change, not the owner's sign-in")
    void wrongPasswordsHereDoNotLockSignIn() throws Exception {
        String token = hms(nurse, "ROLE_NURSE");
        for (int i = 0; i < 5; i++) {
            assertThat(status(post("/auth/me/change-email"), token,
                Map.of("currentPassword", "Wrong-" + i, "newEmail", "n" + next() + "@self.test"))).isEqualTo(400);
        }
        MvcResult locked = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"), token,
            Map.of("currentPassword", PASSWORD, "newEmail", "n" + next() + "@self.test"));
        assertThat(locked.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(locked)).isEqualTo(MessageUtil.resolve("user.email.change.locked"));

        assertThat(loginAttemptService.isLocked(nurse.getUsername())).isFalse();
        MvcResult login = mockMvc.perform(post("/auth/login").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("username", nurse.getUsername(), "password", PASSWORD))))
            .andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("the right password cannot mail codes in a loop: the sixth request in the hour is refused")
    void requestsAreRateLimited() throws Exception {
        String token = hms(nurse, "ROLE_NURSE");
        for (int i = 0; i < 5; i++) {
            assertThat(status(post("/auth/me/change-email"), token,
                Map.of("currentPassword", PASSWORD, "newEmail", "loop" + next() + "@victim.test"))).isEqualTo(200);
        }
        MvcResult sixth = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"), token,
            Map.of("currentPassword", PASSWORD, "newEmail", "loop" + next() + "@victim.test"));
        assertThat(sixth.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(sixth)).isEqualTo(MessageUtil.resolve("user.email.change.ratelimited"));
    }

    @Test
    @DisplayName("an address the mail sender would refuse is refused at the request, not accepted and then never sent")
    void undeliverableAddressIsRefused() throws Exception {
        MvcResult refused = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"),
            hms(nurse, "ROLE_NURSE"), Map.of("currentPassword", PASSWORD, "newEmail", "someone@example.c"));
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(refused)).isEqualTo(MessageUtil.resolve("user.update.email.invalid"));
    }

    @Test
    @DisplayName("a Keycloak session cannot request or confirm a change: Keycloak owns the email on that path")
    void keycloakSessionIsRefused() throws Exception {
        String kc = keycloakPatient();
        MvcResult requested = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"), kc,
            Map.of("currentPassword", PASSWORD, "newEmail", "kc" + next() + "@self.test"));
        assertThat(requested.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(requested)).isEqualTo(MessageUtil.resolve("user.email.change.external"));
        assertThat(status(post("/auth/me/change-email/confirm"), kc, Map.of("code", "424242"))).isEqualTo(400);
    }

    @Test
    @DisplayName("a password-reset link mailed to the old address before the change is refused after it")
    void resetLinkIssuedBeforeTheChangeIsRefusedAfter() throws Exception {
        String rawToken = "reset-link-" + UUID.randomUUID();
        resetTokenRepository.save(PasswordResetToken.builder()
            .user(userRepository.findById(patient.getId()).orElseThrow())
            .tokenHash(sha256Hex(rawToken))
            .expiration(LocalDateTime.now().plusHours(2))
            .build());
        String token = hms(patient, "ROLE_PATIENT");
        String wanted = "moved" + next() + "@self.test";

        assertThat(status(post("/auth/me/change-email"), token,
            Map.of("currentPassword", PASSWORD, "newEmail", wanted))).isEqualTo(200);
        EmailChangeRequest pending = rowOf(patient);
        pending.setCodeHash(passwordEncoder.encode("424242"));
        emailChangeRequestRepository.save(pending);
        assertThat(status(post("/auth/me/change-email/confirm"), token, Map.of("code", "424242"))).isEqualTo(200);
        assertThat(emailOf(patient)).isEqualTo(wanted);
        assertThat(resetTokenRepository.findByTokenHash(sha256Hex(rawToken)))
            .as("the unconsumed reset token is deleted with the change").isEmpty();

        MvcResult reset = mockMvc.perform(post("/auth/password/confirm").with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                    Map.of("token", rawToken, "newPassword", "Old-Mailbox-Pass-1"))))
            .andReturn();
        // The confirm endpoint answers 204 whatever the token (it never reveals
        // validity), so the proof is that the password did NOT change.
        assertThat(reset.getResponse().getStatus()).isEqualTo(204);
        String hashAfter = userRepository.findById(patient.getId()).orElseThrow().getPasswordHash();
        assertThat(passwordEncoder.matches("Old-Mailbox-Pass-1", hashAfter))
            .as("the old mailbox's reset link must not work after the move").isFalse();
        assertThat(passwordEncoder.matches(PASSWORD, hashAfter)).isTrue();
    }

    @Test
    @DisplayName("the per-address limit counts mails sent, so an account re-targeting does not free a slot")
    void retargetingDoesNotResetTheAddressCount() throws Exception {
        String victim = "inbox" + next() + "@victim.test";
        User third = account("ecthr", "ROLE_NURSE");
        User fourth = account("ecfou", "ROLE_NURSE");

        // Three mails to the victim, from three accounts...
        for (User sender : List.of(nurse, other, third)) {
            assertThat(status(post("/auth/me/change-email"), hms(sender, "ROLE_NURSE"),
                Map.of("currentPassword", PASSWORD, "newEmail", victim))).isEqualTo(200);
        }
        // ...then the first two move their pending change elsewhere.
        for (User sender : List.of(nurse, other)) {
            assertThat(status(post("/auth/me/change-email"), hms(sender, "ROLE_NURSE"),
                Map.of("currentPassword", PASSWORD, "newEmail", "elsewhere" + next() + "@self.test")))
                .isEqualTo(200);
        }
        // Only one pending change still targets the victim, yet three mails went there this hour.
        MvcResult fourthMail = perform(post("/auth/me/change-email").header(HttpHeaders.ACCEPT_LANGUAGE, "en"),
            hms(fourth, "ROLE_NURSE"), Map.of("currentPassword", PASSWORD, "newEmail", victim));
        assertThat(fourthMail.getResponse().getStatus()).isEqualTo(400);
        assertThat(message(fourthMail)).isEqualTo(MessageUtil.resolve("user.email.change.ratelimited"));
    }

    // -------------------------------------------------------------- helpers

    private static String sha256Hex(String raw) throws Exception {
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    private String next() {
        return String.format("%05d", SEQUENCE.incrementAndGet());
    }

    private Role ensureRole(String code) {
        return roleRepository.findByCode(code).orElseGet(() -> {
            Role saved = roleRepository.save(Role.builder()
                .name(code.substring("ROLE_".length()))
                .code(code)
                .description(code)
                .build());
            createdRoles.add(saved.getId());
            return saved;
        });
    }

    private User account(String prefix, String roleCode) {
        String suffix = next();
        User user = userRepository.save(User.builder()
            .username(prefix + suffix)
            .passwordHash(passwordEncoder.encode(PASSWORD))
            .email(prefix + suffix + "@email-change.test")
            .firstName(prefix)
            .lastName("Change" + suffix)
            .phoneNumber("+22678" + suffix)
            .isActive(true)
            .build());
        createdUsers.add(user.getId());
        assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("EC-" + next())
            .description(roleCode)
            .user(user)
            .hospital(hospital)
            .role(ensureRole(roleCode))
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        return user;
    }

    private String hms(User user, String role) {
        idleSessionTracker.touch(user.getId());
        return jwtTokenProvider.generateAccessToken(
            new TokenUserDescriptor(user.getId(), user.getUsername(), List.of(role)));
    }

    private String keycloakPatient() {
        return keycloak.mintToken(KeycloakJwtFixture.TokenSpec
            .defaults(OidcResourceServerIntegrationTest.TEST_ISSUER, OidcResourceServerIntegrationTest.TEST_AUDIENCE)
            .withRealmRoles(List.of("PATIENT", "offline_access", "uma_authorization", "default-roles-hms")));
    }

    private MvcResult perform(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer).with(csrf());
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
        }
        return mockMvc.perform(request).andReturn();
    }

    private int status(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        return perform(request, bearer, body).getResponse().getStatus();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String message(MvcResult result) throws Exception {
        JsonNode node = body(result);
        return node.has("message") ? node.get("message").asText() : node.toString();
    }

    private String emailOf(User user) {
        return userRepository.findById(user.getId()).orElseThrow().getEmail();
    }

    private EmailChangeRequest rowOf(User user) {
        return emailChangeRequestRepository.findAll().stream()
            .filter(r -> user.getId().equals(r.getUserId())).findFirst().orElseThrow();
    }

    private void assertNoAddressIn(MvcResult result, String... addresses) throws Exception {
        String content = result.getResponse().getContentAsString();
        for (String address : addresses) {
            assertThat(content).doesNotContainIgnoringCase(address);
        }
    }

    private List<AuditEventLog> failureRowsFor(User user) {
        return auditEventLogRepository.findAll().stream()
            .filter(row -> user.getId().toString().equals(row.getResourceId()))
            .filter(row -> row.getStatus() == AuditStatus.FAILURE)
            .toList();
    }
}
