package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.*;
import com.example.hms.repository.*;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.PatientNationalUniqueId;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import com.example.hms.service.RequiredAuditEventLogPort;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real PostgreSQL, Liquibase, encrypted converter, method security, tenant queries and audit writes. */
@Testcontainers
@AutoConfigureMockMvc(addFilters = false)
class PatientNationalUniqueIdPostgresIT extends BaseIT {
    private static final String VALUE = "000123456789";
    private static final String ROOT = "/api/patients/national-unique-id";
    @Container @SuppressWarnings("resource")
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
        .withDatabaseName("hms_iu_test").withUsername("hms_test_user").withPassword("hms_test_pass");
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        properties.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.PostgreSQLDialect");
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.liquibase.enabled", () -> "true");
        properties.add("spring.sql.init.mode", () -> "never");
    }
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrganizationRepository organizations;
    @Autowired HospitalRepository hospitals;
    @Autowired PatientRepository patients;
    @Autowired PatientHospitalRegistrationRepository registrations;
    @Autowired UserRepository users;
    @Autowired RoleRepository roles;
    @Autowired UserRoleHospitalAssignmentRepository assignments;
    @Autowired com.example.hms.repository.empi.EmpiIdentityAliasRepository aliases;
    @Autowired com.example.hms.service.PatientService patientService;
    @MockitoSpyBean RequiredAuditEventLogPort audit;
    private Organization organization;
    private Hospital hospitalA;
    private Hospital hospitalB;
    private User actor;
    private Patient patientA;
    private Patient patientB;
    private final java.util.List<UUID> extraPatients = new java.util.ArrayList<>();

    @BeforeEach
    void fixtures() {
        extraPatients.clear();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        organization = organizations.save(Organization.builder().name("IU test " + tag).code("IU" + tag)
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospitalA = hospital("A" + tag);
        hospitalB = hospital("B" + tag);
        actor = user("staff" + tag);
        Role role = roles.findByCode("ROLE_RECEPTIONIST").orElseGet(() -> roles.save(Role.builder()
            .name("Receptionist").code("ROLE_RECEPTIONIST").description("IU test").build()));
        assignments.save(UserRoleHospitalAssignment.builder().user(actor).role(role).hospital(hospitalA)
            .assignmentCode("IU" + tag).description("IU test").startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now()).active(true).build());
        patientA = patient(hospitalA, "A" + tag);
        patientB = patient(hospitalB, "B" + tag);
    }

    @AfterEach
    void clear() {
        RequiredAuditEventLogPort auditTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        reset(auditTarget);
        SecurityContextHolder.clearContext(); HospitalContextHolder.clear();
        var ids = new java.util.ArrayList<>(extraPatients); ids.add(patientA.getId()); ids.add(patientB.getId());
        for (UUID id : ids) {
            jdbc.update("DELETE FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?", id);
            jdbc.update("UPDATE clinical.patients SET national_unique_id = NULL, national_unique_id_hash = NULL WHERE id = ?", id);
        }
        // Unique fixtures remain inside this disposable container; no shared or environment data.
    }

    @Test
    void patchPersistsEncryptedStringAndPrivateAliasAndAuditsOnlyPatientId() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        Map<String, Object> stored = jdbc.queryForMap("SELECT national_unique_id, national_unique_id_hash FROM clinical.patients WHERE id = ?", patientA.getId());
        assertThat(stored.get("national_unique_id").toString()).startsWith("gcm1:").doesNotContain(VALUE);
        assertThat(stored.get("national_unique_id_hash")).isEqualTo(PatientNationalUniqueId.fingerprint(VALUE));
        assertThat(jdbc.queryForObject("SELECT alias_value FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?", String.class, patientA.getId()))
            .isEqualTo(PatientNationalUniqueId.fingerprint(VALUE));
        List<Map<String, Object>> events = jdbc.queryForList("SELECT event_description, details FROM support.audit_event_logs WHERE target_resource_id = ?", patientA.getId().toString());
        assertThat(events).isNotEmpty();
        assertThat(events.toString()).doesNotContain(VALUE, PatientNationalUniqueId.fingerprint(VALUE));
    }

    @Test
    void duplicateOutsideScopeIsOnlyAGenericBooleanAndOverrideRequiresReason() throws Exception {
        save(patientB, VALUE, false, null, hospitalB).andExpect(status().isOk());
        String body = json.writeValueAsString(Map.of("nationalUniqueId", VALUE));
        String response = mvc.perform(post(ROOT + "/check").contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andExpect(jsonPath("$.accessiblePatientIds").isEmpty())
            .andExpect(jsonPath("$.registeredElsewhere").value(true)).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain(patientB.getId().toString(), hospitalB.getId().toString(), hospitalB.getName(), VALUE);
        save(patientA, VALUE, false, null).andExpect(status().isBadRequest());
        save(patientA, VALUE, true, "").andExpect(status().isBadRequest());
        save(patientA, VALUE, true, "Checked with patient " + VALUE).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clinical.patients WHERE national_unique_id_hash = ?", Integer.class,
            PatientNationalUniqueId.fingerprint(VALUE))).isEqualTo(2);
        String auditRows = jdbc.queryForList("SELECT details FROM support.audit_event_logs WHERE target_resource_id = ?", patientA.getId().toString()).toString();
        assertThat(auditRows).contains("Checked with patient").doesNotContain(VALUE, PatientNationalUniqueId.fingerprint(VALUE));
    }

    @Test
    void inScopeDuplicateOffersOnlyAccessibleRecordAndExactPostSearchIsScoped() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        mvc.perform(post(ROOT + "/check").contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nationalUniqueId", "0001-2345 6789"))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accessiblePatientIds[0]").value(patientA.getId().toString()))
            .andExpect(jsonPath("$.registeredElsewhere").value(false));
        for (Hospital hospital : List.of(hospitalA, hospitalB)) {
            mvc.perform(post(ROOT + "/search").contextPath("/api").with(as(hospital, "ROLE_RECEPTIONIST"))
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nationalUniqueId", VALUE))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(hospital == hospitalA ? 1 : 0))
                .andExpect(jsonPath("$[0].nationalUniqueId").doesNotExist());
        }
    }

    @Test
    void ordinaryStaffAndThePatientsOwnPortalReceiveOnlyTheMaskedValue() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        mvc.perform(get("/api/patients/{id}", patientA.getId()).contextPath("/api").with(as(hospitalA, "ROLE_NURSE")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nationalUniqueIdMasked").value("•••• •••• 6789"))
            .andExpect(jsonPath("$.nationalUniqueId").doesNotExist()).andExpect(jsonPath("$.nationalUniqueIdHash").doesNotExist());
        mvc.perform(get("/api/me/patient/profile").contextPath("/api").with(as(patientA.getUser(), hospitalA, "ROLE_PATIENT")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.nationalUniqueIdMasked").value("•••• •••• 6789"))
            .andExpect(jsonPath("$.data.nationalUniqueId").doesNotExist()).andExpect(jsonPath("$.data.nationalUniqueIdHash").doesNotExist());
    }

    @Test
    void anExactIdentityLookupCannotDiscloseClinicalContentToPickerRoles() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        Patient stored = patients.findByIdUnscoped(patientA.getId()).orElseThrow();
        stored.setMedicalHistorySummary("staff-only history"); stored.setCareTeamNotes("staff-only notes");
        stored.setAllergies("another-site allergy summary"); patients.save(stored);
        stored.setChartRestricted(true); stored.setChartRestrictionReason("private restriction detail"); patients.save(stored);
        String body = mvc.perform(post(ROOT + "/search").contextPath("/api").with(as(hospitalA, "ROLE_BILLING_SPECIALIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nationalUniqueId", VALUE))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(patientA.getId().toString()))
            .andExpect(jsonPath("$[0].nationalUniqueIdMasked").value("•••• •••• 6789"))
            .andExpect(jsonPath("$[0].chartRestricted").value(true))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("staff-only history", "staff-only notes", "another-site allergy summary", "private restriction detail", VALUE);
    }

    @Test
    void privateHashesAreAbsentFromPublicAliasLookupAndLegacyPublicUniquenessRemains() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        String hash = PatientNationalUniqueId.fingerprint(VALUE);
        assertThat(aliases.findByAliasTypeAndAliasValueIgnoreCase(com.example.hms.enums.empi.EmpiAliasType.NATIONAL_ID, hash)).isEmpty();
        assertThat(aliases.existsByAliasTypeAndAliasValueIgnoreCase(com.example.hms.enums.empi.EmpiAliasType.NATIONAL_ID, hash)).isFalse();
        UUID master = jdbc.queryForObject("SELECT master_identity_id FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?",
            UUID.class, patientA.getId());
        String legacy = "LEGACY-PUBLIC-" + UUID.randomUUID();
        String insert = "INSERT INTO empi.identity_aliases (id, master_identity_id, alias_type, alias_value, active, created_at, updated_at) VALUES (?, ?, 'NATIONAL_ID', ?, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)";
        jdbc.update(insert, UUID.randomUUID(), master, legacy);
        assertThat(aliases.findByAliasTypeAndAliasValueIgnoreCase(com.example.hms.enums.empi.EmpiAliasType.NATIONAL_ID,
            legacy.toLowerCase(java.util.Locale.ROOT))).isPresent();
        assertThat(aliases.existsByAliasTypeAndAliasValueIgnoreCase(com.example.hms.enums.empi.EmpiAliasType.NATIONAL_ID,
            legacy.toLowerCase(java.util.Locale.ROOT))).isTrue();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), master, legacy))
            .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void publicAliasNullLookupsRemainEmptyOnPostgres() {
        org.assertj.core.api.Assertions.assertThatCode(() ->
            assertThat(aliases.findByAliasTypeAndAliasValueIgnoreCase(com.example.hms.enums.empi.EmpiAliasType.NATIONAL_ID,
                null)).isEmpty()).doesNotThrowAnyException();
        org.assertj.core.api.Assertions.assertThatCode(() ->
            assertThat(aliases.existsByAliasTypeAndAliasValueIgnoreCase(com.example.hms.enums.empi.EmpiAliasType.NATIONAL_ID,
                null)).isFalse()).doesNotThrowAnyException();
    }

    @Test
    void staffCreateFullReplaceAndAccountRegistrationCreateUseTheSameProtectedBoundary() throws Exception {
        User account = user("create" + UUID.randomUUID().toString().substring(0, 8));
        var body = registrationBody(account, VALUE);
        String response = mvc.perform(post("/api/patients").contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.nationalUniqueIdMasked").value("•••• •••• 6789"))
            .andReturn().getResponse().getContentAsString();
        UUID id = UUID.fromString(json.readTree(response).get("id").asText()); extraPatients.add(id);
        body.put("nationalUniqueId", "999123456789");
        mvc.perform(put("/api/patients/{id}", id).contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nationalUniqueIdMasked").value("•••• •••• 6789"));
        assertThat(patients.findByIdUnscoped(id).orElseThrow().getNationalUniqueId()).isEqualTo("999123456789");
        User second = user("account" + UUID.randomUUID().toString().substring(0, 8));
        var request = json.convertValue(registrationBody(second, "888123456789"), com.example.hms.payload.dto.PatientRequestDTO.class);
        var created = patientService.createPatient(request, java.util.Locale.FRENCH);
        extraPatients.add(created.getId());
        assertThat(created.getNationalUniqueIdMasked()).isEqualTo("•••• •••• 6789");
        assertThat(jdbc.queryForObject("SELECT alias_value FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?",
            String.class, created.getId())).isEqualTo(PatientNationalUniqueId.fingerprint("888123456789"));
    }

    private java.util.LinkedHashMap<String, Object> registrationBody(User user, String value) {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("userId", user.getId()); body.put("hospitalId", hospitalA.getId()); body.put("nationalUniqueId", value);
        body.put("firstName", "New"); body.put("lastName", "Identity"); body.put("dateOfBirth", "1990-01-01");
        body.put("gender", "FEMALE"); body.put("phoneNumberPrimary", "+22670000123"); body.put("country", "Burkina Faso");
        body.put("city", "Ouagadougou"); body.put("address", "Test address");
        return body;
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> emptyIdentityQueries() {
        return java.util.stream.Stream.of("check", "search").flatMap(operation ->
            java.util.stream.Stream.of("{}", "{\"nationalUniqueId\":\"   \"}")
                .map(body -> org.junit.jupiter.params.provider.Arguments.of(operation, body)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("emptyIdentityQueries")
    void anEmptyOptionalIdentityQueryDoesNotModifyThePatientAliasOrAudit(String operation, String body) throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        String cipher = jdbc.queryForObject("SELECT national_unique_id FROM clinical.patients WHERE id = ?", String.class, patientA.getId());
        String hash = jdbc.queryForObject("SELECT national_unique_id_hash FROM clinical.patients WHERE id = ?", String.class, patientA.getId());
        var updated = jdbc.queryForObject("SELECT updated_at FROM clinical.patients WHERE id = ?", java.sql.Timestamp.class, patientA.getId());
        Long aliasCount = jdbc.queryForObject("SELECT COUNT(*) FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?", Long.class, patientA.getId());
        Long auditCount = jdbc.queryForObject("SELECT COUNT(*) FROM support.audit_event_logs WHERE target_resource_id = ?", Long.class, patientA.getId().toString());
        var answer = mvc.perform(post(ROOT + "/" + operation).contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        if (operation.equals("search")) answer.andExpect(content().json("[]"));
        else answer.andExpect(jsonPath("$.accessiblePatientIds").isEmpty()).andExpect(jsonPath("$.registeredElsewhere").value(false));
        assertThat(jdbc.queryForObject("SELECT national_unique_id FROM clinical.patients WHERE id = ?", String.class, patientA.getId())).isEqualTo(cipher);
        assertThat(jdbc.queryForObject("SELECT national_unique_id_hash FROM clinical.patients WHERE id = ?", String.class, patientA.getId())).isEqualTo(hash);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM clinical.patients WHERE id = ?", java.sql.Timestamp.class, patientA.getId())).isEqualTo(updated);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?", Long.class, patientA.getId())).isEqualTo(aliasCount);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support.audit_event_logs WHERE target_resource_id = ?", Long.class, patientA.getId().toString())).isEqualTo(auditCount);
    }

    static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidIdentityQueries() {
        return java.util.stream.Stream.of("check", "search").flatMap(operation ->
            java.util.stream.Stream.of("00012345678", "0001234567890", "not-a-national-number")
                .map(value -> org.junit.jupiter.params.provider.Arguments.of(operation, value)));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("invalidIdentityQueries")
    void invalidExactIdentityQueriesAreFieldRefusalsWithoutEchoingTheValue(String operation, String input) throws Exception {
        String answer = mvc.perform(post(ROOT + "/" + operation).contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nationalUniqueId", input))))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.fieldErrors.nationalUniqueId").exists())
            .andReturn().getResponse().getContentAsString();
        assertThat(answer).doesNotContain(input);
    }

    @Test
    void identityReadsStayAvailableAfterADeathAndOnlyTheFullDisclosureAddsAnAudit() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        Patient deceased = patients.findByIdUnscoped(patientA.getId()).orElseThrow();
        LocalDateTime diedAt = LocalDateTime.of(2026, 11, 15, 10, 0);
        deceased.setDeceasedAt(diedAt); patients.save(deceased);
        String encrypted = jdbc.queryForObject("SELECT national_unique_id FROM clinical.patients WHERE id = ?", String.class, patientA.getId());
        Integer audits = jdbc.queryForObject("SELECT COUNT(*) FROM support.audit_event_logs WHERE target_resource_id = ?", Integer.class, patientA.getId().toString());
        mvc.perform(post(ROOT + "/check").contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nationalUniqueId", VALUE, "excludePatientId", patientA.getId()))))
            .andExpect(status().isOk()).andExpect(jsonPath("$.accessiblePatientIds").isEmpty());
        mvc.perform(post(ROOT + "/search").contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("nationalUniqueId", VALUE))))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(patientA.getId().toString()))
            .andExpect(jsonPath("$[0].deceased").value(true))
            .andExpect(jsonPath("$[0].deceasedAt").exists())
            .andExpect(jsonPath("$[0].careBlockedByDeath").value(true));
        mvc.perform(post(ROOT + "/{id}/reveal", patientA.getId()).contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST")))
            .andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT national_unique_id FROM clinical.patients WHERE id = ?", String.class, patientA.getId())).isEqualTo(encrypted);
        assertThat(patients.findByIdUnscoped(patientA.getId()).orElseThrow().getDeceasedAt()).isEqualTo(diedAt);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM support.audit_event_logs WHERE target_resource_id = ?", Integer.class, patientA.getId().toString())).isEqualTo(audits + 1);
    }

    @Test
    void fullDisclosureNeedsIdentityRoleScopeAndSuccessfulAudit() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        mvc.perform(post(ROOT + "/{id}/reveal", patientA.getId()).contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.nationalUniqueId").value("0001 2345 6789"));
        mvc.perform(post(ROOT + "/{id}/reveal", patientA.getId()).contextPath("/api").with(as(hospitalA, "ROLE_PHARMACIST")))
            .andExpect(status().isForbidden());
        mvc.perform(post(ROOT + "/{id}/reveal", patientA.getId()).contextPath("/api").with(as(hospitalB, "ROLE_RECEPTIONIST")))
            .andExpect(status().isNotFound());
        RequiredAuditEventLogPort auditTarget = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(audit);
        doThrow(new IllegalStateException("Synthetic audit failure")).when(auditTarget).logRequiredEvent(any());
        String refusal = mvc.perform(post(ROOT + "/{id}/reveal", patientA.getId()).contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST")))
            .andExpect(status().is5xxServerError()).andReturn().getResponse().getContentAsString();
        assertThat(refusal).doesNotContain(VALUE, "0001 2345 6789");
        save(patientA, "999123456789", false, null).andExpect(status().is5xxServerError());
        assertThat(patients.findByIdUnscoped(patientA.getId()).orElseThrow().getNationalUniqueId()).isEqualTo(VALUE);
        assertThat(jdbc.queryForObject("SELECT alias_value FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?",
            String.class, patientA.getId())).isEqualTo(PatientNationalUniqueId.fingerprint(VALUE));
    }

    @Test
    void omittedKeepsAndBlankClearsWithoutRequiringTheIdentifier() throws Exception {
        save(patientA, VALUE, false, null).andExpect(status().isOk());
        mvc.perform(patch("/api/patients/{id}", patientA.getId()).contextPath("/api").with(as(hospitalA, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk())
            .andExpect(jsonPath("$.nationalUniqueIdMasked").value("•••• •••• 6789"));
        save(patientA, "", false, null).andExpect(status().isOk()).andExpect(jsonPath("$.nationalUniqueIdMasked").doesNotExist());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM empi.identity_aliases WHERE national_unique_id_patient_id = ?", Integer.class, patientA.getId())).isZero();
        assertThat(patients.findByIdUnscoped(patientA.getId()).orElseThrow().getNationalUniqueIdHash()).isNull();
    }

    @Test
    void twoConcurrentSavesCannotSilentlySkipTheDuplicateWarning() throws Exception {
        registrations.save(PatientHospitalRegistration.builder().patient(patientB).hospital(hospitalA)
            .mrn("IU-concurrent-" + UUID.randomUUID()).registrationDate(LocalDate.now()).active(true).build());
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (Patient patient : List.of(patientA, patientB)) results.add(executor.submit(() -> {
                start.await();
                try { return save(patient, VALUE, false, null).andReturn().getResponse().getStatus(); }
                finally { SecurityContextHolder.clearContext(); HospitalContextHolder.clear(); }
            }));
            start.countDown();
            assertThat(List.of(results.get(0).get(60, java.util.concurrent.TimeUnit.SECONDS),
                results.get(1).get(60, java.util.concurrent.TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 400);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM clinical.patients WHERE national_unique_id_hash = ?",
            Integer.class, PatientNationalUniqueId.fingerprint(VALUE))).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions save(Patient patient, String value, boolean override, String reason) throws Exception {
        return save(patient, value, override, reason, hospitalA);
    }
    private org.springframework.test.web.servlet.ResultActions save(Patient patient, String value, boolean override, String reason, Hospital scope) throws Exception {
        var body = new java.util.LinkedHashMap<String, Object>();
        body.put("nationalUniqueId", value); body.put("allowNationalUniqueIdDuplicate", override);
        if (reason != null) body.put("nationalUniqueIdDuplicateReason", reason);
        return mvc.perform(patch("/api/patients/{id}", patient.getId()).contextPath("/api").with(as(scope, "ROLE_RECEPTIONIST"))
            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }
    private Hospital hospital(String tag) {
        return hospitals.save(Hospital.builder().name("IU Hospital " + tag).code("IU" + tag).city("Ouagadougou")
            .country("Burkina Faso").address("Test address").phoneNumber("+22670000000")
            .email(tag + "@hospital.test").organization(organization).build());
    }
    private User user(String tag) {
        return users.save(User.builder().username("iu" + tag).passwordHash("synthetic")
            .email(tag + "@example.test").firstName("Test").lastName(tag).isActive(true).build());
    }
    private Patient patient(Hospital hospital, String tag) {
        Patient patient = patients.save(Patient.builder().firstName("Test").lastName(tag).dateOfBirth(LocalDate.of(1990, 1, 1))
            .gender("F").phoneNumberPrimary("+22670000000").hospitalId(hospital.getId()).organizationId(organization.getId())
            .user(user("patient" + tag)).build());
        registrations.save(PatientHospitalRegistration.builder().patient(patient).hospital(hospital).mrn("IU" + tag)
            .registrationDate(LocalDate.now()).active(true).build());
        return patient;
    }
    private RequestPostProcessor as(Hospital hospital, String role) {
        return as(actor, hospital, role);
    }
    private RequestPostProcessor as(User caller, Hospital hospital, String role) {
        var authorities = List.of(new SimpleGrantedAuthority(role));
        var principal = new CustomUserDetails(caller.getId(), caller.getUsername(), caller.getPasswordHash(), true, authorities);
        var auth = new UsernamePasswordAuthenticationToken(principal, null, authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder().principalUserId(caller.getId())
                .principalUsername(caller.getUsername()).activeHospitalId(hospital.getId())
                .activeOrganizationId(organization.getId()).permittedHospitalIds(Set.of(hospital.getId()))
                .permittedOrganizationIds(Set.of(organization.getId())).build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }
}
