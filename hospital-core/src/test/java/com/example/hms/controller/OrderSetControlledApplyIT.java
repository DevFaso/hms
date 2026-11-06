package com.example.hms.controller;

import com.example.hms.BaseIT;
import com.example.hms.enums.AcuityLevel;
import com.example.hms.enums.AdmissionStatus;
import com.example.hms.enums.AdmissionType;
import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.EncounterType;
import com.example.hms.enums.JobTitle;
import com.example.hms.enums.OrganizationType;
import com.example.hms.model.Admission;
import com.example.hms.model.AdmissionOrderSet;
import com.example.hms.model.Encounter;
import com.example.hms.model.Hospital;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.Prescription;
import com.example.hms.model.Role;
import com.example.hms.model.Staff;
import com.example.hms.model.User;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.model.medication.MedicationCatalogItem;
import com.example.hms.repository.AdmissionOrderSetRepository;
import com.example.hms.repository.AdmissionRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.MedicationCatalogItemRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PrescriptionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.CustomUserDetails;
import com.example.hms.security.context.HospitalContext;
import com.example.hms.security.context.HospitalContextHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * QA D1396 (round 33) end to end, through the real apply endpoint, the real
 * dispatcher and the real prescription service: a set holding a drug the
 * catalogue marks controlled creates its draft, with the item's quantity; a set
 * stored without a quantity is refused by name, before any order is created;
 * a name two catalogue items carry is refused until the item names its pick.
 */
@AutoConfigureMockMvc(addFilters = false)
@Transactional
class OrderSetControlledApplyIT extends BaseIT {

    private static final String API_CONTEXT = "/api";
    private static final String ROLE_DOCTOR = "ROLE_DOCTOR";
    private static final String MEDICATION = "MEDICATION";
    private static final AtomicInteger SEQUENCE = new AtomicInteger((int) (System.nanoTime() % 1_000_000));

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganizationRepository organizations;
    @Autowired private HospitalRepository hospitals;
    @Autowired private RoleRepository roles;
    @Autowired private UserRepository users;
    @Autowired private UserRoleHospitalAssignmentRepository assignments;
    @Autowired private StaffRepository staffRows;
    @Autowired private PatientRepository patients;
    @Autowired private PatientHospitalRegistrationRepository registrations;
    @Autowired private EncounterRepository encounters;
    @Autowired private AdmissionRepository admissions;
    @Autowired private AdmissionOrderSetRepository orderSets;
    @Autowired private MedicationCatalogItemRepository catalog;
    @Autowired private PrescriptionRepository prescriptions;

    private Organization organization;
    private Hospital hospital;
    private User doctor;
    private Staff doctorStaff;
    private Encounter encounter;
    private Admission admission;

    private String unique() {
        return String.format("%08d", SEQUENCE.incrementAndGet());
    }

    private void world() {
        organization = organizations.save(Organization.builder().name("Set Network").code("ORG-" + unique())
            .type(OrganizationType.HOSPITAL_CHAIN).active(true).build());
        hospital = hospitals.save(Hospital.builder().name("Set Hospital").code("H" + unique())
            .city("Ouagadougou").country("Burkina Faso").address("1 Main St")
            .phoneNumber("+226" + unique()).email(unique() + "@set.test").organization(organization).build());
        Role role = roles.findByCode(ROLE_DOCTOR).orElseGet(() -> roles.save(Role.builder()
            .name("Doctor").code(ROLE_DOCTOR).description("Doctor role").build()));
        doctor = users.save(User.builder().username("doc-" + unique()).passwordHash("hashed")
            .email(unique() + "@set.test").firstName("Doc").lastName("Set").phoneNumber("+226" + unique())
            .isActive(true).build());
        UserRoleHospitalAssignment assignment = assignments.save(UserRoleHospitalAssignment.builder()
            .assignmentCode("A-" + unique()).description("Doctor").user(doctor).hospital(hospital).role(role)
            .startDate(LocalDate.now()).assignedAt(LocalDateTime.now()).active(true).build());
        doctorStaff = staffRows.save(Staff.builder().user(doctor).hospital(hospital).assignment(assignment)
            .jobTitle(JobTitle.PHYSICIAN).employmentType(EmploymentType.FULL_TIME)
            .licenseNumber("LIC-" + unique()).name("Dr. Set").active(true).build());
        User patientUser = users.save(User.builder().username("pat-" + unique()).passwordHash("hashed")
            .email(unique() + "@set.test").firstName("Pat").lastName("Set").phoneNumber("+226" + unique())
            .isActive(true).build());
        Patient patient = patients.save(Patient.builder().firstName("Awa").lastName("Set")
            .dateOfBirth(LocalDate.now().minusYears(35)).gender("F").address("Address").city("Ouagadougou")
            .country("Burkina Faso").phoneNumberPrimary("+226" + unique()).email(unique() + "@set.test")
            .organizationId(organization.getId()).hospitalId(hospital.getId()).user(patientUser).build());
        registrations.save(PatientHospitalRegistration.builder().patient(patient).hospital(hospital)
            .mrn("S" + unique()).registrationDate(LocalDate.now()).active(true).build());
        encounter = encounters.save(Encounter.builder().patient(patient).staff(doctorStaff).hospital(hospital)
            .assignment(assignment).encounterType(EncounterType.CONSULTATION).encounterDate(LocalDateTime.now())
            .code("ENC-" + unique()).build());
        Admission row = new Admission();
        row.setPatient(patient);
        row.setHospital(hospital);
        row.setAdmittingProvider(doctorStaff);
        row.setAdmissionType(AdmissionType.EMERGENCY);
        row.setStatus(AdmissionStatus.ACTIVE);
        row.setAcuityLevel(AcuityLevel.values()[0]);
        row.setAdmissionDateTime(LocalDateTime.now());
        row.setChiefComplaint("Douleur");
        admission = admissions.save(row);
    }

    private MedicationCatalogItem drug(String strength) {
        return catalog.save(MedicationCatalogItem.builder().nameFr("Morphine").genericName("Morphine")
            .strength(strength).strengthUnit("mg").hospital(hospital).active(true).controlled(true).build());
    }

    private AdmissionOrderSet stored(Map<String, Object> item) {
        AdmissionOrderSet set = new AdmissionOrderSet();
        set.setName("Douleur " + unique());
        set.setAdmissionType(AdmissionType.EMERGENCY);
        set.setHospital(hospital);
        set.setOrderItems(new ArrayList<>(List.of(item)));
        set.setActive(true);
        set.setVersion(1);
        set.setCreatedBy(doctorStaff);
        set.setLastModifiedBy(doctorStaff);
        return orderSets.save(set);
    }

    private org.springframework.test.web.servlet.ResultActions apply(AdmissionOrderSet set) throws Exception {
        String body = "{\"encounterId\":\"" + encounter.getId() + "\",\"orderingStaffId\":\"" + doctorStaff.getId() + "\"}";
        return mockMvc.perform(post(API_CONTEXT + "/order-sets/{set}/apply/{admission}", set.getId(), admission.getId())
            .contextPath(API_CONTEXT).with(asDoctor())
            .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private RequestPostProcessor asDoctor() {
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(ROLE_DOCTOR));
        CustomUserDetails principal = new CustomUserDetails(
            doctor.getId(), doctor.getUsername(), doctor.getPasswordHash(), true, authorities);
        Authentication auth = new UsernamePasswordAuthenticationToken(principal, doctor.getPasswordHash(), authorities);
        return request -> {
            SecurityContextHolder.getContext().setAuthentication(auth);
            HospitalContextHolder.setContext(HospitalContext.builder()
                .principalUserId(doctor.getId()).principalUsername(doctor.getUsername())
                .activeOrganizationId(organization.getId()).activeHospitalId(hospital.getId())
                .permittedOrganizationIds(Set.of(organization.getId())).permittedHospitalIds(Set.of(hospital.getId()))
                .build());
            request.setUserPrincipal(auth);
            return authentication(auth).postProcessRequest(request);
        };
    }

    @Test
    @DisplayName("QA D1396: a set with a catalogue-controlled drug applies, and the draft carries its quantity")
    void aControlledSetApplies() throws Exception {
        world();
        drug("10");
        long before = prescriptions.count();

        String answer = apply(stored(Map.of("orderType", MEDICATION, "medicationName", "Morphine", "doseRangeMin", 2,
            "doseUnit", "mg", "route", "IV", "frequency", "Q4H", "quantity", 6, "quantityUnit", "ampoules")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.prescriptionIds.length()").value(1))
            .andExpect(jsonPath("$.skippedItemCount").value(0))
            .andReturn().getResponse().getContentAsString();

        assertThat(prescriptions.count()).isEqualTo(before + 1);
        UUID created = UUID.fromString(com.jayway.jsonpath.JsonPath.read(answer, "$.prescriptionIds[0]"));
        Prescription draft = prescriptions.findById(created).orElseThrow();
        assertThat(draft.getQuantity()).isEqualByComparingTo(BigDecimal.valueOf(6));
        assertThat(draft.getQuantityUnit()).isEqualTo("ampoules");
        assertThat(draft.getDosage()).isEqualTo("2 mg IV");
        assertThat(draft.getMedicationCatalogItemId()).isNotNull();
    }

    @Test
    @DisplayName("QA D1396: a stored set without the quantity is refused by name, and no order is created")
    void aStoredSetWithoutTheQuantityIsRefused() throws Exception {
        world();
        drug("10");
        long before = prescriptions.count();

        apply(stored(Map.of("orderType", MEDICATION, "medicationName", "Morphine", "frequency", "Q4H")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors['orderItems[0].quantity']").exists());

        assertThat(prescriptions.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("QA D1396: a name two catalogue items carry is refused until the item names its pick")
    void aNameTwoItemsCarryNamesItsPick() throws Exception {
        world();
        MedicationCatalogItem ten = drug("10");
        drug("30");

        apply(stored(Map.of("orderType", MEDICATION, "medicationName", "Morphine", "frequency", "Q4H", "quantity", 6)))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.fieldErrors['orderItems[0].medicationCatalogItemId']").exists());

        apply(stored(Map.of("orderType", MEDICATION, "medicationName", "Morphine", "frequency", "Q4H", "quantity", 6,
            "medicationCatalogItemId", ten.getId().toString())))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.prescriptionIds.length()").value(1));
    }
}
