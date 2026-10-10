package com.example.hms.integration;

import com.example.hms.BaseIT;
import com.example.hms.enums.FacilityType;
import com.example.hms.enums.ProviderVerificationStatus;
import com.example.hms.model.Hospital;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.provider.ProviderVerification;
import com.example.hms.repository.AuditEventLogRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.provider.ProviderVerificationRepository;
import com.example.hms.security.IdleSessionTracker;
import com.example.hms.security.tenant.LinkedTestAccounts;
import com.example.hms.service.provider.ProviderOrganisationsFlag;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The provider admin pages, the shell settings and the provider directory
 * through the REAL security chain (provider plan P1-T5, P1-T9, AC-6, AC-14):
 * signed Keycloak-shaped tokens for real local accounts, placed by their live
 * assignments.
 *
 * <p>Anyone a {@code /provider/**} handler turns away (a hospital user, a
 * provider user who is not the facility's PROVIDER_ADMIN, a member out of the
 * admin's reach) gets exactly the answer of an unmapped path, compared with a
 * hospital doctor's unmapped path.
 */
@AutoConfigureMockMvc
@Import(ProviderConfinementSecurityIT.SignedKeycloakTokens.class)
class ProviderAdminSecurityIT extends BaseIT {

    private static final String UNMAPPED = "/provider-admin-no-such-endpoint";
    private static final String PROVIDER_ADMIN = "PROVIDER_ADMIN";
    private static final String PHARMACIST = "PHARMACIST";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private AuditEventLogRepository auditEventLogRepository;
    @Autowired private ProviderVerificationRepository verificationRepository;
    @Autowired private IdleSessionTracker idleSessionTracker;
    @Autowired private ProviderOrganisationsFlag organisationsFlag;

    private LinkedTestAccounts accounts;
    private final List<UUID> verificationIds = new ArrayList<>();
    private UUID hospitalId;
    private UUID pharmacyId;
    private UUID otherPharmacyId;
    private User admin;
    private String adminToken;
    private User pharmacist;

    @BeforeEach
    void setUp() {
        accounts = new LinkedTestAccounts(hospitalRepository, userRepository, roleRepository,
            assignmentRepository, auditEventLogRepository);
        hospitalId = accounts.hospital("Admin Hospital").getId();
        pharmacyId = accounts.provider("Admin Pharmacy", FacilityType.PHARMACY).getId();
        otherPharmacyId = accounts.provider("Other Pharmacy", FacilityType.PHARMACY).getId();
        admin = accounts.userAt("padmin", pharmacyId, PROVIDER_ADMIN);
        adminToken = tokenFor(admin, PROVIDER_ADMIN);
        pharmacist = accounts.userAt("pharm", pharmacyId, PHARMACIST);
        ReflectionTestUtils.setField(organisationsFlag, "enabled", false);
    }

    @AfterEach
    void tearDown() {
        ReflectionTestUtils.setField(organisationsFlag, "enabled", false);
        verificationRepository.deleteAllByIdInBatch(verificationIds);
        verificationIds.clear();
        accounts.cleanUp();
    }

    // ── who reaches the pages ───────────────────────────────────────────────

    @Test
    @DisplayName("a hospital doctor gets the unmapped path's answer from every /provider page, whatever the body or id")
    void hospitalUserGetsTheUnmappedAnswer() throws Exception {
        String doctor = doctorToken();
        String unmapped = ProviderConfinementSecurityIT.refusalShape(as(doctor, get(UNMAPPED)));

        for (MockHttpServletRequestBuilder request : providerPages(pharmacist.getId())) {
            MvcResult result = as(doctor, request);
            assertThat(ProviderConfinementSecurityIT.refusalShape(result)).as(label(result)).isEqualTo(unmapped);
        }
        // An invalid body is not validated, and a malformed id not parsed, before the seat check.
        MvcResult invalidBody = as(doctor, put("/provider/profile").contentType(MediaType.APPLICATION_JSON).content("{}"));
        assertThat(ProviderConfinementSecurityIT.refusalShape(invalidBody)).isEqualTo(unmapped);
        MvcResult malformedId = as(doctor, post("/provider/staff/{userId}/deactivate", "not-an-id"));
        assertThat(ProviderConfinementSecurityIT.refusalShape(malformedId)).isEqualTo(unmapped);
    }

    @Test
    @DisplayName("a pharmacist reads the profile and the settings, and gets the unmapped answer from the admin pages")
    void pharmacistIsNotTheAdmin() throws Exception {
        String token = tokenFor(pharmacist, PHARMACIST);
        String unmapped = ProviderConfinementSecurityIT.refusalShape(as(doctorToken(), get(UNMAPPED)));

        assertThat(as(token, get("/provider/profile")).getResponse().getStatus()).isEqualTo(200);
        JsonNode settings = json(as(token, get("/provider/settings")));
        assertThat(settings.get("facilityId").asText()).isEqualTo(pharmacyId.toString());
        assertThat(settings.get("facilityType").asText()).isEqualTo("PHARMACY");
        assertThat(settings.get("providerAdmin").asBoolean()).isFalse();
        assertThat(settings.get("organisationsEnabled").asBoolean()).isFalse();

        for (MockHttpServletRequestBuilder request : List.of(
                get("/provider/staff"),
                put("/provider/profile").contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+22670000000\"}"),
                post("/provider/staff/{userId}/deactivate", admin.getId()),
                post("/provider/staff/{userId}/activate", admin.getId()))) {
            MvcResult result = as(token, request);
            assertThat(ProviderConfinementSecurityIT.refusalShape(result)).as(label(result)).isEqualTo(unmapped);
        }
    }

    @Test
    @DisplayName("the PROVIDER_ADMIN lists exactly its own facility's staff")
    void adminListsOwnStaffOnly() throws Exception {
        accounts.userAt("elsewhere", otherPharmacyId, PHARMACIST);

        JsonNode staff = json(as(adminToken, get("/provider/staff")));
        List<String> ids = new ArrayList<>();
        staff.forEach(member -> ids.add(member.get("userId").asText()));
        assertThat(ids).containsExactlyInAnyOrder(admin.getId().toString(), pharmacist.getId().toString());
        staff.forEach(member -> {
            if (member.get("userId").asText().equals(admin.getId().toString())) {
                assertThat(member.get("providerAdmin").asBoolean()).isTrue();
                assertThat(member.get("self").asBoolean()).isTrue();
            }
        });
    }

    // ── staff changes ───────────────────────────────────────────────────────

    @Test
    @DisplayName("deactivate retires the member's rows here: inactive, invitation revoked")
    void adminDeactivatesOwnStaff() throws Exception {
        UserRoleHospitalAssignment row = rowOf(pharmacist, pharmacyId);
        row.setConfirmationCode("123456");
        assignmentRepository.save(row);

        MvcResult result = as(adminToken, post("/provider/staff/{userId}/deactivate", pharmacist.getId()));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        assertThat(json(result).get("active").asBoolean()).isFalse();
        UserRoleHospitalAssignment after = assignmentRepository.findById(row.getId()).orElseThrow();
        assertThat(after.getActive()).isFalse();
        assertThat(after.getConfirmationCode()).isNull();
    }

    @Test
    @DisplayName("activate never switches a row on: it issues a new code for the holder to enter")
    void adminReinvitesOwnStaff() throws Exception {
        UserRoleHospitalAssignment row = rowOf(pharmacist, pharmacyId);
        row.setActive(false);
        row.setConfirmationCode(null);
        assignmentRepository.save(row);

        MvcResult result = as(adminToken, post("/provider/staff/{userId}/activate", pharmacist.getId()));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        UserRoleHospitalAssignment after = assignmentRepository.findById(row.getId()).orElseThrow();
        assertThat(after.getActive()).isFalse();
        assertThat(after.getConfirmationCode()).isNotBlank();
        assertThat(json(result).get("invitationPending").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("a member elsewhere, a peer admin, the admin itself, an unknown and a malformed id: the unmapped answer, nothing changed")
    void adminCannotReachOutsideItsStaff() throws Exception {
        User elsewhere = accounts.userAt("elsewhere", otherPharmacyId, PHARMACIST);
        User peer = accounts.userAt("peer", pharmacyId, PROVIDER_ADMIN);
        String unmapped = ProviderConfinementSecurityIT.refusalShape(as(doctorToken(), get(UNMAPPED)));

        for (String target : List.of(elsewhere.getId().toString(), peer.getId().toString(),
                admin.getId().toString(), UUID.randomUUID().toString(), "not-an-id")) {
            for (String action : List.of("deactivate", "activate")) {
                MvcResult result = as(adminToken, post("/provider/staff/{userId}/" + action, target));
                assertThat(ProviderConfinementSecurityIT.refusalShape(result)).as(label(result)).isEqualTo(unmapped);
            }
        }
        assertThat(rowOf(elsewhere, otherPharmacyId).getActive()).isTrue();
        assertThat(rowOf(peer, pharmacyId).getActive()).isTrue();
        assertThat(rowOf(admin, pharmacyId).getActive()).isTrue();
    }

    // ── profile ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("the admin changes the operational contact; the verified identity cannot be sent, so it never changes")
    void adminUpdatesOperationalContactOnly() throws Exception {
        Hospital before = hospitalRepository.findById(pharmacyId).orElseThrow();
        String body = "{\"phoneNumber\":\" +22670112233 \",\"email\":\"desk@pharmacy.test\",\"website\":\"\","
            + "\"name\":\"Renamed\",\"licenceNumber\":\"FORGED\",\"city\":\"Elsewhere\"}";

        MvcResult result = as(adminToken, put("/provider/profile").contentType(MediaType.APPLICATION_JSON).content(body));

        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(200);
        Hospital after = hospitalRepository.findById(pharmacyId).orElseThrow();
        assertThat(after.getPhoneNumber()).isEqualTo("+22670112233");
        assertThat(after.getEmail()).isEqualTo("desk@pharmacy.test");
        assertThat(after.getWebsite()).isNull();
        assertThat(after.getName()).isEqualTo(before.getName());
        assertThat(after.getLicenseNumber()).isEqualTo(before.getLicenseNumber());
        assertThat(after.getCity()).isEqualTo(before.getCity());
    }

    @Test
    @DisplayName("an invalid contact from the admin is a 400")
    void adminInvalidContactIsRefused() throws Exception {
        MvcResult result = as(adminToken, put("/provider/profile").contentType(MediaType.APPLICATION_JSON)
            .content("{\"phoneNumber\":\" \",\"email\":\"not-an-email\"}"));
        assertThat(result.getResponse().getStatus()).isEqualTo(400);
    }

    // ── directory and the flag ──────────────────────────────────────────────

    @Test
    @DisplayName("flag OFF: the directory is empty, whatever the parameters; settings say so")
    void flagOffDirectoryIsEmpty() throws Exception {
        verify(pharmacyId);
        String doctor = doctorToken();

        for (MockHttpServletRequestBuilder request : List.of(
                get("/provider-directory"),
                get("/provider-directory").param("type", "PHARMACY"),
                get("/provider-directory").param("type", "NOT-A-TYPE"))) {
            MvcResult result = as(doctor, request);
            assertThat(result.getResponse().getStatus()).as(label(result)).isEqualTo(200);
            assertThat(result.getResponse().getContentAsString()).isEqualTo("[]");
        }
        assertThat(json(as(adminToken, get("/provider/settings"))).get("organisationsEnabled").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("flag ON: the directory lists verified, active providers only, to hospital staff")
    void flagOnDirectoryListsVerifiedProviders() throws Exception {
        ReflectionTestUtils.setField(organisationsFlag, "enabled", true);
        String licence = verify(pharmacyId);
        // otherPharmacyId has no VERIFIED verification: never offered.

        JsonNode entries = json(as(doctorToken(), get("/provider-directory").param("type", "PHARMACY")));
        List<String> ids = new ArrayList<>();
        entries.forEach(entry -> ids.add(entry.get("id").asText()));
        assertThat(ids).contains(pharmacyId.toString()).doesNotContain(otherPharmacyId.toString(), hospitalId.toString());
        entries.forEach(entry -> {
            if (entry.get("id").asText().equals(pharmacyId.toString())) {
                assertThat(entry.get("licenceNumber").asText()).isEqualTo(licence);
                assertThat(entry.get("facilityType").asText()).isEqualTo("PHARMACY");
            }
        });
        assertThat(as(doctorToken(), get("/provider-directory").param("type", "HOSPITAL")).getResponse().getStatus())
            .isEqualTo(400);
        assertThat(json(as(adminToken, get("/provider/settings"))).get("organisationsEnabled").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("flag ON: a provider user never reaches the directory (confinement)")
    void providerUserNeverReachesTheDirectory() throws Exception {
        ReflectionTestUtils.setField(organisationsFlag, "enabled", true);
        String unmapped = ProviderConfinementSecurityIT.refusalShape(as(doctorToken(), get(UNMAPPED)));
        MvcResult result = as(tokenFor(pharmacist, PHARMACIST), get("/provider-directory"));
        assertThat(ProviderConfinementSecurityIT.refusalShape(result)).isEqualTo(unmapped);
    }

    // ── the super-admin's facilityType filter on the hospital list ─────────

    @Test
    @DisplayName("GET /hospitals?facilityType=PHARMACY: providers for a verified super-admin, 403 for a doctor")
    void superAdminFacilityTypeFilter() throws Exception {
        User superAdmin = accounts.userAt("sadmin", null, "SUPER_ADMIN");
        String superToken = tokenFor(superAdmin, "SUPER_ADMIN");

        JsonNode pharmacies = json(as(superToken, get("/hospitals").param("facilityType", "PHARMACY")));
        List<String> ids = new ArrayList<>();
        pharmacies.forEach(row -> ids.add(row.get("id").asText()));
        assertThat(ids).contains(pharmacyId.toString(), otherPharmacyId.toString()).doesNotContain(hospitalId.toString());

        JsonNode clinical = json(as(superToken, get("/hospitals")));
        List<String> clinicalIds = new ArrayList<>();
        clinical.forEach(row -> clinicalIds.add(row.get("id").asText()));
        assertThat(clinicalIds).contains(hospitalId.toString()).doesNotContain(pharmacyId.toString());

        assertThat(as(doctorToken(), get("/hospitals").param("facilityType", "PHARMACY")).getResponse().getStatus())
            .isEqualTo(403);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static List<MockHttpServletRequestBuilder> providerPages(UUID memberId) {
        return List.of(
            get("/provider/profile"),
            get("/provider/settings"),
            get("/provider/staff"),
            put("/provider/profile").contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+22670000000\"}"),
            post("/provider/staff/{userId}/deactivate", memberId),
            post("/provider/staff/{userId}/activate", memberId));
    }

    /** A VERIFIED verification for the facility; returns its licence number. */
    private String verify(UUID facilityId) {
        String licence = "LIC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        ProviderVerification verification = new ProviderVerification();
        verification.setHospital(hospitalRepository.findById(facilityId).orElseThrow());
        verification.setStatus(ProviderVerificationStatus.VERIFIED);
        verification.setLegalName("Pharmacie Test SARL");
        verification.setLegalStructure("SARL");
        verification.setRccmNumber("RCCM-" + UUID.randomUUID().toString().substring(0, 8));
        verification.setIfuNumber("IFU-" + UUID.randomUUID().toString().substring(0, 8));
        verification.setCnssNumber("CNSS-" + UUID.randomUUID().toString().substring(0, 8));
        verification.setAddressCity("Ouagadougou");
        verification.setAddressRegion("Centre");
        verification.setCompanyPhone("+22625000000");
        verification.setManagerName("Manager");
        verification.setManagerTitle("Gérant");
        verification.setBusinessStartedOn(LocalDate.of(2020, 1, 1));
        verification.setIfuMatchesRccm(true);
        verification.setCnssMatchesRccm(true);
        verification.setLicenceNumber(licence);
        verification.setLicenceAuthority("DGPML");
        verification.setResponsibleProfessionalName("Responsible");
        verification.setResponsibleProfessionalRegistration("ORDRE-1");
        verification.setDecidedAt(LocalDateTime.now());
        verificationIds.add(verificationRepository.save(verification).getId());
        return licence;
    }

    private UserRoleHospitalAssignment rowOf(User user, UUID facilityId) {
        return assignmentRepository.findByUserId(user.getId()).stream()
            .filter(row -> row.getHospital() != null && facilityId.equals(row.getHospital().getId()))
            .findFirst()
            .orElseThrow();
    }

    private String doctorToken() {
        return tokenFor(accounts.userAt("doc", hospitalId, "DOCTOR"), "DOCTOR");
    }

    private String tokenFor(User user, String... realmRoles) {
        idleSessionTracker.touch(user.getId());
        return ProviderConfinementSecurityIT.token(user.getUsername(), user.getId(), realmRoles);
    }

    private MvcResult as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
    }

    private JsonNode json(MvcResult result) throws Exception {
        assertThat(result.getResponse().getStatus()).as(label(result) + " " + result.getResponse().getContentAsString())
            .isEqualTo(200);
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private static String label(MvcResult result) {
        return result.getRequest().getMethod() + " " + result.getRequest().getRequestURI();
    }
}
