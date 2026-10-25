package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.DeliveryMode;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Hospital;
import com.example.hms.model.MaternalHistory;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.payload.dto.clinical.labor.DeliveryRecordRequestDTO;
import com.example.hms.payload.dto.clinical.labor.LaborEpisodeRequestDTO;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MaternalHistoryRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D796 and D797, the exact round-12 calls through the real filter chain,
 * controllers and services (H2).
 *
 * <p>D796: MIDWIFE_A posted a labour episode for A3, who had no pregnancy on
 * record (201, gestational age null), and the Prénatal tab then planned a
 * 14-week calendar next to it (200). The episode now needs the estimated
 * gestational age, which opens the pregnancy it is linked to, and no
 * antenatal calendar is planned while she is in labour.
 *
 * <p>D797: a delivery of 3,200 g at 24 weeks was accepted with only the
 * preterm alert. It is still recorded (201), with a CAUTION to check it.
 */
@AutoConfigureMockMvc
class MaternityPregnancyLinkIT extends BaseIT {

    private static final String API = "/api";

    @Autowired private ApplicationContext applicationContext;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private OrganizationRepository organizationRepository;
    @Autowired private HospitalRepository hospitalRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Autowired private StaffRepository staffRepository;
    @Autowired private PatientRepository patientRepository;
    @Autowired private PatientHospitalRegistrationRepository registrationRepository;
    @Autowired private MaternalHistoryRepository maternalHistoryRepository;

    private final AtomicInteger sequence = new AtomicInteger();

    private Organization organization;
    private Hospital hospital;
    private Patient patient;
    private User midwife;

    @BeforeEach
    void seed() {
        wipe();
        organization = organizationRepository.save(Organization.builder()
            .name("Keneya Maternity Network")
            .code("ORG-" + nextId())
            .type(OrganizationType.HOSPITAL_CHAIN)
            .active(true)
            .build());
        hospital = saveHospital();
        midwife = midwifeAt(hospital);
        patient = createPatient();
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
        HospitalContextHolder.clear();
        wipe();
    }

    @Test
    @DisplayName("D796: no pregnancy on record and no estimated age -> 400, nothing written")
    void anUnbookedLabourWithNoEstimateIsRefused() throws Exception {
        LaborEpisodeRequestDTO request = new LaborEpisodeRequestDTO();
        request.setLaborOnsetAt(LocalDateTime.now().minusHours(1).withNano(0));
        request.setAdmittedAt(LocalDateTime.now().minusMinutes(30).withNano(0));

        postEpisode(request).andExpect(status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("No current pregnancy is recorded")));

        assertThat(maternalHistoryRepository.findCurrentByPatientId(patient.getId())).isEmpty();
    }

    @Test
    @DisplayName("D796: the estimate opens the linked pregnancy, and the 14-week calendar is then refused")
    void theEstimateOpensThePregnancyAndTheCalendarIsRefusedDuringLabour() throws Exception {
        LaborEpisodeRequestDTO request = new LaborEpisodeRequestDTO();
        request.setLaborOnsetAt(LocalDateTime.now().minusHours(1).withNano(0));
        request.setGestationalAgeWeeks(38);

        MvcResult created = postEpisode(request).andExpect(status().isCreated()).andReturn();
        JsonNode episode = objectMapper.readTree(created.getResponse().getContentAsString());

        MaternalHistory pregnancy = maternalHistoryRepository.findCurrentByPatientId(patient.getId()).orElseThrow();
        assertThat(episode.get("maternalHistoryId").asString()).isEqualTo(pregnancy.getId().toString());
        assertThat(episode.get("gestationalAgeWeeks").asInt()).isEqualTo(38);
        assertThat(pregnancy.getEstimatedDueDate()).isEqualTo(LocalDate.now().plusWeeks(2));

        Map<String, Object> calendar = Map.of(
            "patientId", patient.getId(),
            "hospitalId", hospital.getId(),
            "lastMenstrualPeriodDate", LocalDate.now().minusWeeks(14).toString());
        // The JWT filter builds the midwife's hospital context in production;
        // a test principal has none, so it is set as the filter would.
        HospitalContextHolder.setContext(com.example.hms.security.context.HospitalContext.builder()
            .principalUserId(midwife.getId())
            .principalUsername(midwife.getUsername())
            .activeOrganizationId(organization.getId())
            .activeHospitalId(hospital.getId())
            .permittedOrganizationIds(java.util.Set.of(organization.getId()))
            .permittedHospitalIds(java.util.Set.of(hospital.getId()))
            .staffHospitalIds(java.util.Set.of(hospital.getId()))
            .build());
        mockMvc.perform(post(API + "/prenatal/schedule")
                .contextPath(API)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(calendar))
                .header("Accept-Language", "en")
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isBadRequest())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                .jsonPath("$.message").value(org.hamcrest.Matchers.containsString("is in labour")));
    }

    @Test
    @DisplayName("D797: 3,200 g at 24 weeks is recorded (201) with a CAUTION to check the weight and the age")
    void anImplausibleWeightIsRecordedWithACaution() throws Exception {
        LaborEpisodeRequestDTO request = new LaborEpisodeRequestDTO();
        request.setLaborOnsetAt(LocalDateTime.now().minusHours(8).withNano(0));
        request.setGestationalAgeWeeks(24);
        JsonNode episode = objectMapper.readTree(postEpisode(request).andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString());

        LocalDateTime birth = LocalDateTime.now().minusHours(3).withNano(0);
        DeliveryRecordRequestDTO delivery = new DeliveryRecordRequestDTO();
        delivery.setBirthDateTime(birth);
        delivery.setDeliveryMode(DeliveryMode.SPONTANEOUS_VAGINAL);
        delivery.setLiveBirth(true);
        delivery.setNumberOfInfants(1);
        delivery.setBirthWeightGrams(3200);
        delivery.setApgarOneMinute(8);
        delivery.setApgarFiveMinute(9);
        delivery.setPlacentaDeliveredAt(birth.plusMinutes(10));
        delivery.setPlacentaComplete(true);
        String body = mockMvc.perform(post(API + "/patients/{p}/labor/episodes/{e}/delivery",
                    patient.getId(), episode.get("id").asString())
                .contextPath(API).param("hospitalId", hospital.getId().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(delivery))
                .with(signedInAs(midwife)).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn().getResponse().getContentAsString();

        List<String> codes = new java.util.ArrayList<>();
        for (JsonNode alert : objectMapper.readTree(body).get("alerts")) {
            codes.add(alert.get("code").asString());
        }
        assertThat(codes).contains("labor-weight-implausible", "labor-preterm");
    }

    private org.springframework.test.web.servlet.ResultActions postEpisode(LaborEpisodeRequestDTO request)
        throws Exception {
        return mockMvc.perform(post(API + "/patients/{p}/labor/episodes", patient.getId())
            .contextPath(API).param("hospitalId", hospital.getId().toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsBytes(request))
            .header("Accept-Language", "en")
            .with(signedInAs(midwife)).with(csrf()));
    }

    private static RequestPostProcessor signedInAs(User user) {
        CustomUserDetails details = new CustomUserDetails(user.getId(), user.getUsername(), "n/a", true,
            List.of(new SimpleGrantedAuthority("ROLE_MIDWIFE")));
        return authentication(new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities()));
    }

    private void wipe() {
        applicationContext.getBean(com.example.hms.repository.InvoiceItemRepository.class).deleteAll();
        applicationContext.getBean(com.example.hms.repository.BillingInvoiceRepository.class).deleteAll();
        applicationContext.getBean(com.example.hms.repository.PostpartumCarePlanRepository.class).deleteAll();
        SharedClinicalRowsCleanup.deleteAll(applicationContext);
    }

    private Hospital saveHospital() {
        String id = nextId();
        return hospitalRepository.save(Hospital.builder()
            .name("Maternity Link Hospital")
            .code("H" + id)
            .city("Ouagadougou")
            .country("Burkina Faso")
            .address("1 Main St")
            .phoneNumber("+226556" + id)
            .email("contact" + id + "@hospital.test")
            .organization(organization)
            .build());
    }

    private User midwifeAt(Hospital at) {
        Role role = roleRepository.findByCode("ROLE_MIDWIFE")
            .orElseGet(() -> roleRepository.save(Role.builder()
                .name("MIDWIFE").code("ROLE_MIDWIFE").description("ROLE_MIDWIFE").build()));
        User user = userRepository.save(buildUser("midwife"));
        UserRoleHospitalAssignment assignment = assignmentRepository.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("ASSIGN-" + nextId())
            .description("midwife at " + at.getName())
            .user(user)
            .hospital(at)
            .role(role)
            .startDate(LocalDate.now())
            .assignedAt(LocalDateTime.now())
            .active(true)
            .build());
        staffRepository.save(Staff.builder()
            .user(user)
            .hospital(at)
            .assignment(assignment)
            .jobTitle(JobTitle.MIDWIFE)
            .employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + nextId())
            .name("midwife " + user.getLastName())
            .active(true)
            .build());
        return user;
    }

    private User buildUser(String prefix) {
        String suffix = nextId();
        return User.builder()
            .username(prefix + "ml" + suffix)
            .passwordHash("hashed-password")
            .email(prefix + "ml" + suffix + "@example.test")
            .firstName(prefix + "FN")
            .lastName("User" + suffix)
            .phoneNumber("+22676" + suffix)
            .isActive(true)
            .build();
    }

    private Patient createPatient() {
        User patientUser = userRepository.save(buildUser("patient"));
        String suffix = nextId();
        Patient saved = patientRepository.save(Patient.builder()
            .firstName("Aissata")
            .lastName("Femme")
            .dateOfBirth(LocalDate.of(1997, 5, 12))
            .gender("F")
            .address("Patient address")
            .city("Ouagadougou")
            .country("Burkina Faso")
            .phoneNumberPrimary("+22675" + suffix)
            .email("aissata" + suffix + "@patient.test")
            .emergencyContactName("Issa")
            .emergencyContactPhone("+22674" + suffix)
            .organizationId(organization.getId())
            .hospitalId(hospital.getId())
            .user(patientUser)
            .build());
        registrationRepository.save(PatientHospitalRegistration.builder()
            .patient(saved)
            .hospital(hospital)
            .mrn("MRN-ML" + suffix)
            .registrationDate(LocalDate.now())
            .active(true)
            .build());
        return saved;
    }

    private String nextId() {
        return String.format("%05d", sequence.incrementAndGet());
    }
}
