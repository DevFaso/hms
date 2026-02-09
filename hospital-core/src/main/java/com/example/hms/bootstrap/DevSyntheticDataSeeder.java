package com.example.hms.bootstrap;

import com.example.hms.enums.*;
import com.example.hms.model.*;
import com.example.hms.model.Role;
import com.example.hms.repository.*;
import com.example.hms.security.permission.PermissionCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

@Component
@Profile({"dev", "local"})
@ConditionalOnProperty(value = "app.seed.synthetic.enabled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
@SuppressWarnings("squid:S2068") // Default credentials are dev-only seed values
public class DevSyntheticDataSeeder implements ApplicationRunner {

    private record FeaturedAccountSeed(
        String username,
        String firstName,
        String lastName,
        String roleCode,
        JobTitle jobTitle,
        String departmentCode,
        EmploymentType employmentType,
        String specialization
    ) {
    }

    private static final int ORGANIZATION_TARGET = 10;
    private static final int HOSPITALS_PER_ORG = 10;
    private static final int STAFF_PER_HOSPITAL = 12;
    private static final int PATIENTS_PER_HOSPITAL = 10;
    private static final String ORG_CODE_TEMPLATE = "DEV-ORG-%02d";
    private static final String SUPER_ADMIN_USERNAME = "superadmin";
    private static final String SEED_EMAIL_SUFFIX = "@seed.dev";
    private static final String SUPER_ADMIN_EMAIL = SUPER_ADMIN_USERNAME + SEED_EMAIL_SUFFIX;
    private static final String SUPER_ADMIN_SEED_SECRET = new String(new char[]{'T','e','m','p','P','a','s','s','1','2','3','!'});
    private static final String SAMPLE_TYPE_BLOOD = "BLOOD";
    private static final BigDecimal LAB_ANALYSIS_PRICE = new BigDecimal("15000.00");
    private static final String DEFAULT_MRN_PREFIX = "MRX";
    private static final String PHONE_PREFIX = "+226";

    private static final String DEFAULT_DEV_SEED_SECRET = new String(new char[]{'P','a','s','s','w','o','r','d','1','2','3','!'});
    private static final String FEATURED_HOSPITAL_CODE = "DEV-ORG-01-H01";
    private static final String ROLE_MIDWIFE_CODE = "ROLE_MIDWIFE";

    private static final List<FeaturedAccountSeed> FEATURED_ACCOUNTS = List.of(
        new FeaturedAccountSeed(
            "dev_midwife_2031",
            "Clarisse",
            "Sawadogo",
            ROLE_MIDWIFE_CODE,
            JobTitle.MIDWIFE,
            "PED",
            EmploymentType.FULL_TIME,
            "Maternal Support Lead"
        )
    );

    private static final List<String> FIRST_NAMES = List.of(
        "Awa", "Issa", "Salif", "Mariam", "Souleymane",
        "Clarisse", "Amadou", "Alimata", "Oumar", "Nadine",
        "Yacouba", "Leila", "Harouna", "Safiatou", "Jacques"
    );

    private static final List<String> LAST_NAMES = List.of(
        "Kaboré", "Ouédraogo", "Sanou", "Ilboudo", "Zongo",
        "Diallo", "Traoré", "Koulibaly", "Sawadogo", "Tapsoba",
        "Nikiéma", "Bationo", "Bambara", "Bagré", "Nignan"
    );

    private final OrganizationRepository organizationRepository;
    private final HospitalRepository hospitalRepository;
    private final DepartmentRepository departmentRepository;
    private final StaffRepository staffRepository;
    private final PatientRepository patientRepository;
    private final PatientHospitalRegistrationRepository registrationRepository;
    private final PatientInsuranceRepository patientInsuranceRepository;
    private final PatientConsentRepository patientConsentRepository;
    private final AppointmentRepository appointmentRepository;
    private final EncounterRepository encounterRepository;
    private final BillingInvoiceRepository billingInvoiceRepository;
    private final LabOrderRepository labOrderRepository;
    private final LabResultRepository labResultRepository;
    private final LabTestDefinitionRepository labTestDefinitionRepository;
    private final TreatmentRepository treatmentRepository;
    private final ServiceTranslationRepository serviceTranslationRepository;
    private final EncounterTreatmentRepository encounterTreatmentRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserRoleHospitalAssignmentRepository assignmentRepository;
    private final PermissionRepository permissionRepository;
    private final PasswordEncoder passwordEncoder;
    private final PlatformTransactionManager transactionManager;

    private final AtomicInteger userSequence = new AtomicInteger(1);
    private final AtomicInteger phoneSequence = new AtomicInteger(60000000);
    private final AtomicInteger licenseSequence = new AtomicInteger(1);
    private final AtomicInteger assignmentSequence = new AtomicInteger(1);
    private final AtomicInteger patientSequence = new AtomicInteger(1);
    private final AtomicInteger encounterSequence = new AtomicInteger(1);
    private final AtomicInteger invoiceSequence = new AtomicInteger(1);

    private TransactionTemplate transactionTemplate;
    private Map<String, Role> roleCache;
    private List<LabTestDefinition> labTests;
    private String defaultEncodedPassword;
    private boolean featuredAccountsSeeded;
    private final Map<UUID, List<SeededTreatment>> hospitalTreatmentsCache = new HashMap<>();

    @Override
    public void run(ApplicationArguments args) {
        transactionTemplate = new TransactionTemplate(transactionManager);
        initializeSequencesFromDatabase();
    defaultEncodedPassword = passwordEncoder.encode(DEFAULT_DEV_SEED_SECRET); // NOSONAR dev-only seed credential
        roleCache = ensureRoles();
        labTests = ensureLabTests();

        ensureSuperAdminUser(roleCache.get("ROLE_SUPER_ADMIN"));

        for (int orgIndex = 1; orgIndex <= ORGANIZATION_TARGET; orgIndex++) {
            final int index = orgIndex;
            transactionTemplate.executeWithoutResult(tx -> seedOrganizationGraph(index));
            if (!featuredAccountsSeeded) {
                transactionTemplate.executeWithoutResult(tx -> seedFeaturedAccountsIfNeeded());
            }
        }

        if (!featuredAccountsSeeded) {
            transactionTemplate.executeWithoutResult(tx -> seedFeaturedAccountsIfNeeded());
        }

        ensureDeveloperConsentScenarios();
        
        // Backfill permissions for existing assignments
        backfillPermissionsForExistingAssignments();
    }

    private void initializeSequencesFromDatabase() {
        syncUserSequenceFromExistingData();
        syncPhoneSequenceFromExistingData();
    }

    private void syncUserSequenceFromExistingData() {
        List<String> devUsernames = userRepository.findUsernamesByPrefix("dev_");
        int current = userSequence.get();

        int maxSequence = devUsernames.stream()
            .map(this::extractDevUserSequence)
            .filter(OptionalInt::isPresent)
            .mapToInt(OptionalInt::getAsInt)
            .max()
            .orElse(current - 1);

        if (maxSequence + 1 > current) {
            userSequence.set(maxSequence + 1);
        }
    }

    private void syncPhoneSequenceFromExistingData() {
        userRepository.findMaxPhoneNumberWithPrefix(PHONE_PREFIX)
            .flatMap(this::extractPhoneSequence)
            .ifPresent(max -> {
                int candidate = max + 1;
                if (candidate > phoneSequence.get()) {
                    phoneSequence.set(candidate);
                }
            });
    }

    private OptionalInt extractDevUserSequence(String username) {
        if (username == null) {
            return OptionalInt.empty();
        }

        String normalized = username.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("dev_")) {
            return OptionalInt.empty();
        }

        int lastUnderscore = normalized.lastIndexOf('_');
        if (lastUnderscore < 0 || lastUnderscore + 1 >= normalized.length()) {
            return OptionalInt.empty();
        }

        String numericPart = normalized.substring(lastUnderscore + 1);
        try {
            return OptionalInt.of(Integer.parseInt(numericPart));
        } catch (NumberFormatException ex) {
            log.debug("Ignoring dev username without numeric suffix: {}", username);
            return OptionalInt.empty();
        }
    }

    private Optional<Integer> extractPhoneSequence(String phoneNumber) {
        if (phoneNumber == null || !phoneNumber.startsWith(PHONE_PREFIX)) {
            return Optional.empty();
        }

        String numericPart = phoneNumber.substring(PHONE_PREFIX.length());
        try {
            return Optional.of(Integer.parseInt(numericPart));
        } catch (NumberFormatException ex) {
            log.debug("Ignoring non-numeric phone number: {}", phoneNumber);
            return Optional.empty();
        }
    }

    private void seedOrganizationGraph(int orgIndex) {
        String orgCode = String.format(ORG_CODE_TEMPLATE, orgIndex);
        Organization organization = organizationRepository.findByCode(orgCode)
            .orElseGet(() -> organizationRepository.save(buildOrganization(orgIndex, orgCode)));

        for (int hospitalIndex = 1; hospitalIndex <= HOSPITALS_PER_ORG; hospitalIndex++) {
            seedHospitalGraph(organization, orgIndex, hospitalIndex);
        }
    }

    private Organization buildOrganization(int index, String code) {
        OrganizationType[] types = OrganizationType.values();
        OrganizationType type = types[(index - 1) % types.length];
        String name = "Dev Seed Organization " + index;
        Organization organization = Organization.builder()
            .name(name)
            .code(code)
            .description("Synthetic organization seeded for developer experience")
            .type(type)
            .active(true)
            .primaryContactEmail(code.toLowerCase(Locale.ROOT) + SEED_EMAIL_SUFFIX)
            .primaryContactPhone(formatPhone())
            .defaultTimezone("Africa/Ouagadougou")
            .onboardingNotes("Auto-generated organization for local testing")
            .build();
        return organization;
    }

    private void seedHospitalGraph(Organization organization, int orgIndex, int hospitalIndex) {
        String hospitalCode = organization.getCode() + "-H" + String.format("%02d", hospitalIndex);
        Hospital hospital = hospitalRepository.findByCodeIgnoreCase(hospitalCode)
            .orElseGet(() -> hospitalRepository.save(buildHospital(organization, orgIndex, hospitalIndex, hospitalCode)));

        long existingStaff = staffRepository.findByHospital_Id(hospital.getId(), PageRequest.of(0, 1)).getTotalElements();
        if (existingStaff >= STAFF_PER_HOSPITAL) {
            return;
        }

        StaffBundle staffBundle = createStaffBundle(hospital, hospitalIndex);
        seedTreatmentsForHospital(hospital, staffBundle);
        seedPatientsForHospital(organization, hospital, hospitalIndex, staffBundle);
    }

    private void ensureDeveloperConsentScenarios() {
        transactionTemplate.executeWithoutResult(status ->
            seedConsentForEmail("dev_patient_0007" + SEED_EMAIL_SUFFIX)
        );
    }

    private void seedConsentForEmail(String email) {
        List<Patient> matches = patientRepository.findByEmailContainingIgnoreCase(email);
        if (matches.isEmpty()) {
            return;
        }

        Patient patient = matches.stream()
            .filter(p -> p.getEmail() != null && p.getEmail().equalsIgnoreCase(email))
            .findFirst()
            .orElse(matches.get(0));

        List<PatientHospitalRegistration> registrations = registrationRepository.findByPatientId(patient.getId());
        PatientHospitalRegistration activeRegistration = registrations.stream()
            .filter(PatientHospitalRegistration::isActive)
            .findFirst()
            .orElse(null);

        if (activeRegistration == null || activeRegistration.getHospital() == null) {
            return;
        }

        Hospital fromHospital = activeRegistration.getHospital();
        Hospital toHospital = findAlternateHospital(fromHospital);
        if (toHospital == null) {
            return;
        }

        if (patientConsentRepository
            .findByPatientIdAndFromHospitalIdAndToHospitalId(patient.getId(), fromHospital.getId(), toHospital.getId())
            .isPresent()) {
            return;
        }

        // Ensure relationship collection is populated for validation callbacks
        registrations.forEach(reg -> patient.getHospitalRegistrations().add(reg));

        PatientConsent consent = PatientConsent.builder()
            .patient(patient)
            .fromHospital(fromHospital)
            .toHospital(toHospital)
            .consentGiven(true)
            .consentExpiration(LocalDateTime.now().plusMonths(6))
            .purpose("Synthetic developer seed consent for MRN-based sharing")
            .build();

        patientConsentRepository.save(consent);
        log.info("Seeded patient consent for {} from {} to {}", email, fromHospital.getCode(), toHospital.getCode());
    }

    private Hospital findAlternateHospital(Hospital fromHospital) {
        if (fromHospital == null || fromHospital.getId() == null) {
            return null;
        }

        UUID organizationId = fromHospital.getOrganization() != null ? fromHospital.getOrganization().getId() : null;
        List<Hospital> candidates = (organizationId != null)
            ? hospitalRepository.findByOrganizationIdOrderByNameAsc(organizationId)
            : hospitalRepository.findAll();

        return candidates.stream()
            .filter(h -> h.getId() != null && !h.getId().equals(fromHospital.getId()))
            .findFirst()
            .orElse(null);
    }

    private Hospital buildHospital(Organization organization, int orgIndex, int hospitalIndex, String code) {
        String name = String.format("%s Hospital %02d", organization.getName(), hospitalIndex);
        Hospital hospital = Hospital.builder()
            .name(name)
            .code(code)
            .address(String.format("%d Rue du Faso, Quartier %02d", 100 + hospitalIndex, hospitalIndex))
            .city(selectCity(orgIndex, hospitalIndex))
            .state(selectState(orgIndex, hospitalIndex))
            .zipCode("BF-" + String.format("%03d", hospitalIndex))
            .country("Burkina Faso")
            .phoneNumber(formatPhone())
            .email(code.toLowerCase(Locale.ROOT) + "@hospital.seed.dev")
            .website("https://" + code.toLowerCase(Locale.ROOT) + ".seed.dev")
            .licenseNumber("LIC-" + code)
            .active(true)
            .organization(organization)
            .build();
        return hospital;
    }

    private StaffBundle createStaffBundle(Hospital hospital, int hospitalIndex) {
        Role adminRole = roleCache.get("ROLE_HOSPITAL_ADMIN");

        List<Staff> existingStaff = staffRepository.findByHospital_Id(hospital.getId(), PageRequest.of(0, 50)).getContent();
        Map<JobTitle, Staff> staffByJobTitle = existingStaff.stream()
            .filter(staff -> staff.getJobTitle() != null)
            .collect(Collectors.toMap(Staff::getJobTitle, staff -> staff, (left, right) -> left));

        Staff adminStaff = staffByJobTitle.get(JobTitle.HOSPITAL_ADMINISTRATOR);
        User adminUser;
        UserRoleHospitalAssignment adminAssignment;

        if (adminStaff != null) {
            adminUser = adminStaff.getUser();
            adminAssignment = adminStaff.getAssignment();
        } else {
            adminUser = createUser("admin", "Admin" + hospitalIndex, "Lead");
            addGlobalRole(adminUser, adminRole);
            adminAssignment = createAssignment(adminUser, hospital, adminRole, null);
        }

        Department general = createDepartment(hospital, adminAssignment, hospitalIndex, "General Medicine", "GEN", "general");
        Department pediatrics = createDepartment(hospital, adminAssignment, hospitalIndex, "Pediatrics", "PED", "pediatrics");
        Department laboratory = createDepartment(hospital, adminAssignment, hospitalIndex, "Clinical Laboratory", "LAB", "lab");

        if (adminStaff == null) {
            adminStaff = createStaffForExistingUser(adminUser, adminAssignment, hospital, general,
                JobTitle.HOSPITAL_ADMINISTRATOR, EmploymentType.FULL_TIME, "Hospital Governance");
            staffByJobTitle.put(JobTitle.HOSPITAL_ADMINISTRATOR, adminStaff);
        } else if (adminStaff.getDepartment() == null || !Objects.equals(adminStaff.getDepartment().getId(), general.getId())) {
            adminStaff.setDepartment(general);
            adminStaff = staffRepository.save(adminStaff);
        }

        Staff doctor = ensureStaff(staffByJobTitle, JobTitle.DOCTOR, "doctor", hospital, general,
            roleCache.get("ROLE_DOCTOR"), EmploymentType.FULL_TIME, "General Medicine", adminUser);

        Staff physician = ensureStaff(staffByJobTitle, JobTitle.PHYSICIAN, "physician", hospital, general,
            roleCache.get("ROLE_PHYSICIAN"), EmploymentType.FULL_TIME, "Internal Medicine", adminUser);

        Staff surgeon = ensureStaff(staffByJobTitle, JobTitle.SURGEON, "surgeon", hospital, general,
            roleCache.get("ROLE_SURGEON"), EmploymentType.FULL_TIME, "Surgical Services", adminUser);

        Staff anesthesiologist = ensureStaff(staffByJobTitle, JobTitle.ANESTHESIOLOGIST, "anesthesia", hospital, general,
            roleCache.get("ROLE_ANESTHESIOLOGIST"), EmploymentType.FULL_TIME, "Perioperative Care", adminUser);

        Staff nurse = ensureStaff(staffByJobTitle, JobTitle.NURSE, "nurse", hospital, pediatrics,
            roleCache.get("ROLE_NURSE"), EmploymentType.FULL_TIME, "Pediatric Care", adminUser);

        Staff midwife = ensureStaff(staffByJobTitle, JobTitle.MIDWIFE, "midwife", hospital, pediatrics,
            roleCache.get(ROLE_MIDWIFE_CODE), EmploymentType.FULL_TIME, "Maternal Support", adminUser);

        Staff receptionist = ensureStaff(staffByJobTitle, JobTitle.RECEPTIONIST, "reception", hospital, general,
            roleCache.get("ROLE_RECEPTIONIST"), EmploymentType.FULL_TIME, "Front Desk", adminUser);

        Staff billing = ensureStaff(staffByJobTitle, JobTitle.BILLING_SPECIALIST, "billing", hospital, general,
            roleCache.get("ROLE_BILLING_SPECIALIST"), EmploymentType.PART_TIME, "Billing", adminUser);

        Staff labScientist = ensureStaff(staffByJobTitle, JobTitle.LABORATORY_SCIENTIST, "lab", hospital, laboratory,
            roleCache.get("ROLE_LAB_SCIENTIST"), EmploymentType.FULL_TIME, "Diagnostics", adminUser);

        Staff radiologist = ensureStaff(staffByJobTitle, JobTitle.RADIOLOGIST, "radiology", hospital, laboratory,
            roleCache.get("ROLE_RADIOLOGIST"), EmploymentType.FULL_TIME, "Imaging", adminUser);

        Staff physiotherapist = ensureStaff(staffByJobTitle, JobTitle.PHYSIOTHERAPIST, "physio", hospital, general,
            roleCache.get("ROLE_PHYSIOTHERAPIST"), EmploymentType.FULL_TIME, "Rehabilitation", adminUser);

        general.setHeadOfDepartment(doctor != null ? doctor : adminStaff);
        pediatrics.setHeadOfDepartment(nurse != null ? nurse : midwife);
        laboratory.setHeadOfDepartment(labScientist != null ? labScientist : radiologist);
        departmentRepository.saveAll(Arrays.asList(general, pediatrics, laboratory));

        return new StaffBundle(
            adminUser,
            adminAssignment,
            general,
            pediatrics,
            laboratory,
            adminStaff,
            doctor,
            nurse,
            receptionist,
            billing,
            labScientist,
            surgeon,
            physician,
            midwife,
            radiologist,
            anesthesiologist,
            physiotherapist
        );
    }

    private Department createDepartment(Hospital hospital, UserRoleHospitalAssignment assignment, int hospitalIndex,
                                        String name, String codePrefix, String emailLocalPart) {
        String deptCode = codePrefix + String.format("%02d", hospitalIndex);
        return departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), deptCode)
            .map(existing -> {
                boolean changed = false;
                if (existing.getAssignment() == null && assignment != null) {
                    existing.setAssignment(assignment);
                    changed = true;
                }
                if (existing.getHospital() == null) {
                    existing.setHospital(hospital);
                    changed = true;
                }
                if (changed) {
                    return departmentRepository.save(existing);
                }
                return existing;
            })
            .orElseGet(() -> {
                Department department = Department.builder()
                    .assignment(assignment)
                    .hospital(hospital)
                    .phoneNumber(formatPhone())
                    .active(true)
                    .email(String.format("%s_%s@department.seed.dev", hospital.getCode().toLowerCase(Locale.ROOT), emailLocalPart))
                    .name(name)
                    .code(deptCode)
                    .description(name + " department (synthetic data)")
                    .bedCapacity(20 + hospitalIndex)
                    .build();
                return departmentRepository.save(department);
            });
    }

    private Staff createStaffForExistingUser(User user, UserRoleHospitalAssignment assignment, Hospital hospital,
                                             Department department, JobTitle jobTitle,
                                             EmploymentType employmentType, String specialization) {
        Staff staff = Staff.builder()
            .user(user)
            .hospital(hospital)
            .department(department)
            .assignment(assignment)
            .jobTitle(jobTitle)
            .employmentType(employmentType)
            .specialization(specialization)
            .licenseNumber(generateLicense(jobTitle.name()))
            .startDate(LocalDate.now().minusYears(2))
            .name(user.getFirstName() + " " + user.getLastName())
            .active(true)
            .build();
        Staff persisted = staffRepository.save(staff);
        user.setStaffProfile(persisted);
        userRepository.save(user);
        return persisted;
    }

    private Staff createStaff(String roleSuffix, Hospital hospital, Department department, Role role,
                              JobTitle jobTitle, EmploymentType employmentType, String specialization,
                              User registeredBy) {
        String firstName = pickName(FIRST_NAMES, roleSuffix);
        String lastName = pickName(LAST_NAMES, roleSuffix + "-" + hospital.getCode());
        User user = createUser(roleSuffix, firstName, lastName);
        addGlobalRole(user, role);
        UserRoleHospitalAssignment assignment = createAssignment(user, hospital, role, registeredBy);
        Staff staff = Staff.builder()
            .user(user)
            .hospital(hospital)
            .department(department)
            .assignment(assignment)
            .jobTitle(jobTitle)
            .employmentType(employmentType)
            .specialization(specialization)
            .licenseNumber(generateLicense(roleSuffix))
            .startDate(LocalDate.now().minusMonths(18))
            .name(user.getFirstName() + " " + user.getLastName())
            .active(true)
            .build();
        Staff persisted = staffRepository.save(staff);
        user.setStaffProfile(persisted);
        userRepository.save(user);
        return persisted;
    }

    private Staff ensureStaff(Map<JobTitle, Staff> staffByJobTitle, JobTitle jobTitle, String roleSuffix,
                              Hospital hospital, Department department, Role role,
                              EmploymentType employmentType, String specialization, User registeredBy) {
        Staff existing = staffByJobTitle.get(jobTitle);
        if (existing != null) {
            if (department != null && (existing.getDepartment() == null
                || !Objects.equals(existing.getDepartment().getId(), department.getId()))) {
                existing.setDepartment(department);
                existing = staffRepository.save(existing);
            }
            return existing;
        }

        if (role == null) {
            log.warn("Missing role configuration for {} — skipping staff seed", jobTitle);
            return null;
        }

        Staff created = createStaff(roleSuffix, hospital, department, role, jobTitle, employmentType, specialization, registeredBy);
        staffByJobTitle.put(jobTitle, created);
        return created;
    }

    private void seedPatientsForHospital(Organization organization, Hospital hospital, int hospitalIndex,
                                         StaffBundle staffBundle) {
        List<Patient> existingPatients = patientRepository.findByHospitalId(hospital.getId());
        if (existingPatients.size() >= PATIENTS_PER_HOSPITAL) {
            return;
        }

        LocalDate baseDate = LocalDate.now().minusDays(14);
        for (int offset = existingPatients.size(); offset < PATIENTS_PER_HOSPITAL; offset++) {
            int patientOrdinal = offset + 1;
            createPatientGraph(organization, hospital, hospitalIndex, patientOrdinal, baseDate,
                staffBundle);
        }
    }

    private void createPatientGraph(Organization organization, Hospital hospital, int hospitalIndex, int patientOrdinal,
                                    LocalDate baseDate, StaffBundle staffBundle) {
        String firstName = pickName(FIRST_NAMES, "patient-" + hospital.getCode() + "-" + patientOrdinal);
        String lastName = pickName(LAST_NAMES, "patient-" + hospitalIndex + "-" + patientOrdinal);

        User user = createUser("patient", firstName, lastName);
        addGlobalRole(user, roleCache.get("ROLE_PATIENT"));

        LocalDate dob = LocalDate.now().minusYears(20 + (patientSequence.get() % 25)).minusDays(patientOrdinal);
        String phone = user.getPhoneNumber();
        String email = user.getEmail();

        Patient patient = Patient.builder()
            .firstName(firstName)
            .lastName(lastName)
            .dateOfBirth(dob)
            .gender(patientOrdinal % 2 == 0 ? "Female" : "Male")
            .address(String.format("%d Quartier Zaka, Secteur %02d", 200 + patientOrdinal, hospitalIndex))
            .city(hospital.getCity())
            .state(hospital.getState())
            .zipCode(hospital.getZipCode())
            .country(hospital.getCountry())
            .phoneNumberPrimary(phone)
            .email(email)
            .emergencyContactName("Contact " + firstName)
            .emergencyContactPhone(formatPhone())
            .emergencyContactRelationship("Family")
            .bloodType(selectBloodType(patientOrdinal))
            .allergies(patientOrdinal % 3 == 0 ? "Peanuts" : null)
            .medicalHistorySummary("Synthetic medical summary for QA scenario")
            .organizationId(organization.getId())
            .hospitalId(hospital.getId())
            .departmentId(staffBundle.generalDepartment().getId())
            .user(user)
            .active(true)
            .build();

        PatientHospitalRegistration registration = PatientHospitalRegistration.builder()
            .mrn(generateMrn(hospital, patientOrdinal))
            .patient(patient)
            .hospital(hospital)
            .registrationDate(baseDate.plusDays(patientOrdinal))
            .patientFullName(firstName + " " + lastName)
            .currentRoom("R" + String.format("%03d", patientOrdinal))
            .currentBed("B" + String.format("%02d", patientOrdinal % 10 + 1))
            .attendingPhysicianName(staffBundle.doctor().getName())
            .build();

        patient.getHospitalRegistrations().add(registration);

        Patient persistedPatient = patientRepository.save(patient);
        registrationRepository.save(registration);
        user.setPatientProfile(persistedPatient);
        userRepository.save(user);
        createPatientInsurance(persistedPatient, staffBundle);

        Appointment appointment = createAppointment(persistedPatient, hospital, staffBundle, patientOrdinal, baseDate);
        Encounter encounter = createEncounter(persistedPatient, hospital, staffBundle, appointment);
        createBilling(encounter, persistedPatient, hospital, staffBundle);
        createLabResults(encounter, persistedPatient, hospital, staffBundle, patientOrdinal, appointment.getAppointmentDate());
        patientSequence.incrementAndGet();
    }

    private void createPatientInsurance(Patient patient, StaffBundle staffBundle) {
        if (patient == null || staffBundle == null) {
            return;
        }

        UserRoleHospitalAssignment assignment = staffBundle.billing().getAssignment();
        if (assignment == null) {
            return;
        }

        String policyNumber = String.format("POL-%s-%05d", patient.getHospitalId(), patientSequence.get());
        if (patientInsuranceRepository.existsByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
            patient.getId(), "BSHLD", policyNumber)) {
            return;
        }

        PatientInsurance insurance = PatientInsurance.builder()
            .patient(patient)
            .assignment(assignment)
            .providerName("Blue Shield Insurance")
            .policyNumber(policyNumber)
            .groupNumber("HMO-PLATINUM")
            .subscriberName(patient.getFirstName() + " " + patient.getLastName())
            .subscriberRelationship("SELF")
            .effectiveDate(LocalDate.now().minusYears(1))
            .expirationDate(LocalDate.now().plusYears(1))
            .linkedByUserId(assignment.getUser() != null ? assignment.getUser().getId() : null)
            .linkedAs("STAFF")
            .payerCode("BSHLD")
            .primary(true)
            .build();

        patientInsuranceRepository.save(insurance);
        patient.getPatientInsurances().add(insurance);
    }

    private Appointment createAppointment(Patient patient, Hospital hospital, StaffBundle staffBundle,
                                          int patientOrdinal, LocalDate baseDate) {
        LocalDate appointmentDate = baseDate.plusDays(patientOrdinal / 6);
        LocalTime startTime = LocalTime.of(8 + (patientOrdinal % 6), (patientOrdinal * 10) % 60);
        LocalTime endTime = startTime.plusMinutes(45);

        Appointment appointment = Appointment.builder()
            .patient(patient)
            .staff(staffBundle.doctor())
            .hospital(hospital)
            .department(staffBundle.generalDepartment())
            .appointmentDate(appointmentDate)
            .startTime(startTime)
            .endTime(endTime)
            .status(AppointmentStatus.COMPLETED)
            .reason("Routine consultation")
            .notes("Synthetic appointment entry")
            .createdBy(staffBundle.adminUser())
            .assignment(staffBundle.doctor().getAssignment())
            .build();
        return appointmentRepository.save(appointment);
    }

    private Encounter createEncounter(Patient patient, Hospital hospital, StaffBundle staffBundle, Appointment appointment) {
        String encounterCode = String.format("%s-ENC-%05d", hospital.getCode(), encounterSequence.getAndIncrement());
        Encounter encounter = Encounter.builder()
            .patient(patient)
            .staff(staffBundle.doctor())
            .hospital(hospital)
            .encounterType(EncounterType.CONSULTATION)
            .appointment(appointment)
            .department(staffBundle.generalDepartment())
            .encounterDate(appointment.getAppointmentDate().atTime(appointment.getStartTime().plusMinutes(15)))
            .status(EncounterStatus.COMPLETED)
            .notes("Encounter generated for synthetic dataset")
            .code(encounterCode)
            .assignment(staffBundle.doctor().getAssignment())
            .build();
        return encounterRepository.save(encounter);
    }

    private void createBilling(Encounter encounter, Patient patient, Hospital hospital, StaffBundle staffBundle) {
        String invoiceNumber = String.format("%s-INV-%05d", hospital.getCode(), invoiceSequence.getAndIncrement());
        BillingInvoice invoice = BillingInvoice.builder()
            .patient(patient)
            .hospital(hospital)
            .encounter(encounter)
            .invoiceNumber(invoiceNumber)
            .invoiceDate(LocalDate.now().minusDays(2))
            .dueDate(LocalDate.now().plusDays(28))
            .totalAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
            .amountPaid(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP))
            .status(InvoiceStatus.SENT)
            .notes("Synthetic invoice")
            .build();

        InvoiceItem consultation = InvoiceItem.builder()
            .itemDescription("Consultation fee")
            .quantity(1)
            .itemCategory(ItemCategory.SERVICE)
            .unitPrice(new BigDecimal("25000.00"))
            .totalPrice(new BigDecimal("25000.00"))
            .assignment(staffBundle.doctor().getAssignment())
            .build();

        InvoiceItem labItem = InvoiceItem.builder()
            .itemDescription("Laboratory analysis")
            .quantity(1)
            .itemCategory(ItemCategory.LAB_TEST)
            .unitPrice(LAB_ANALYSIS_PRICE)
            .totalPrice(LAB_ANALYSIS_PRICE)
            .assignment(staffBundle.labScientist().getAssignment())
            .build();

        invoice.addItem(consultation);
        invoice.addItem(labItem);
        billingInvoiceRepository.save(invoice);
    }

    private void createLabResults(Encounter encounter, Patient patient, Hospital hospital, StaffBundle staffBundle,
                                  int patientOrdinal, LocalDate appointmentDate) {
        LabTestDefinition definition = labTests.get(patientOrdinal % labTests.size());

        LabOrder order = LabOrder.builder()
            .patient(patient)
            .orderingStaff(staffBundle.doctor())
            .encounter(encounter)
            .labTestDefinition(definition)
            .orderDatetime(appointmentDate.atTime(10, 0))
            .status(LabOrderStatus.COMPLETED)
            .clinicalIndication("Follow-up diagnostics for " + definition.getName())
            .medicalNecessityNote("Synthetic seed order for QA scenarios")
            .notes("Synthetic lab order entry")
            .assignment(staffBundle.doctor().getAssignment())
            .hospital(hospital)
            .build();
        LabOrder savedOrder = labOrderRepository.save(order);

        LabResult result = LabResult.builder()
            .labOrder(savedOrder)
            .resultValue("Within reference range")
            .resultUnit("mg/dL")
            .resultDate(savedOrder.getOrderDatetime().plusHours(4))
            .notes("Synthetic lab result")
            .assignment(staffBundle.labScientist().getAssignment())
            .build();
        labResultRepository.save(result);

        createEncounterTreatments(encounter, hospital, staffBundle, patientOrdinal);
    }

    private void createEncounterTreatments(Encounter encounter, Hospital hospital, StaffBundle staffBundle, int patientOrdinal) {
        if (encounter == null || encounter.getId() == null || hospital == null || hospital.getId() == null) {
            return;
        }

        if (!encounterTreatmentRepository.findByEncounter_Id(encounter.getId()).isEmpty()) {
            return;
        }

        List<SeededTreatment> seededTreatments = hospitalTreatmentsCache.get(hospital.getId());
        if (seededTreatments == null || seededTreatments.isEmpty()) {
            seedTreatmentsForHospital(hospital, staffBundle);
            seededTreatments = hospitalTreatmentsCache.get(hospital.getId());
        }

        if (seededTreatments == null || seededTreatments.isEmpty()) {
            return;
        }

        SeededTreatment selection = seededTreatments.get(patientOrdinal % seededTreatments.size());
        Staff performer = selection.performer() != null ? selection.performer() : staffBundle.doctor();
        LocalDateTime performedAt = Optional.ofNullable(encounter.getEncounterDate()).orElse(LocalDateTime.now());

        EncounterTreatment encounterTreatment = EncounterTreatment.builder()
            .encounter(encounter)
            .treatment(selection.treatment())
            .staff(performer)
            .performedAt(performedAt)
            .outcome("COMPLETED")
            .notes("Synthetic treatment recorded for QA scenario")
            .build();
        encounterTreatmentRepository.save(encounterTreatment);
    }

    private void seedTreatmentsForHospital(Hospital hospital, StaffBundle staffBundle) {
        if (hospital == null || hospital.getId() == null || staffBundle == null) {
            return;
        }

        List<Treatment> existing = treatmentRepository.findByHospital_Id(hospital.getId());
        Map<String, Treatment> existingByName = existing.stream()
            .collect(Collectors.toMap(t -> t.getName().toLowerCase(Locale.ROOT), Function.identity(), (left, right) -> left));

        List<SeededTreatment> seeded = new ArrayList<>();
        for (TreatmentSeed seed : buildTreatmentSeeds(staffBundle)) {
            Department department = seed.department() != null ? seed.department() : staffBundle.generalDepartment();
            UserRoleHospitalAssignment assignment = seed.assignment() != null
                ? seed.assignment()
                : staffBundle.adminAssignment();

            if (department == null || assignment == null) {
                log.debug("Skipping treatment seed {} due to missing department or assignment", seed.nameEn());
                continue;
            }

            Treatment treatment = upsertTreatment(
                existingByName.get(seed.nameEn().toLowerCase(Locale.ROOT)),
                seed, department, hospital, assignment
            );

            ensureTranslation(treatment, assignment, "en", seed.nameEn(), seed.descriptionEn());
            ensureTranslation(treatment, assignment, "fr", seed.nameFr(), seed.descriptionFr());
            seeded.add(new SeededTreatment(treatment, seed.performer()));
        }

        if (!seeded.isEmpty()) {
            hospitalTreatmentsCache.put(hospital.getId(), seeded);
        }
    }


    private Treatment upsertTreatment(Treatment existing, TreatmentSeed seed,
                                       Department department, Hospital hospital,
                                       UserRoleHospitalAssignment assignment) {
        if (existing == null) {
            Treatment treatment = Treatment.builder()
                .name(seed.nameEn())
                .description(seed.descriptionEn())
                .department(department)
                .hospital(hospital)
                .price(seed.price())
                .durationMinutes(seed.durationMinutes())
                .assignment(assignment)
                .active(true)
                .build();
            treatment = treatmentRepository.save(treatment);
            log.info("Seeded treatment '{}' for hospital {}", seed.nameEn(), hospital.getCode());
            return treatment;
        }
        boolean updated = false;
        if (existing.getDepartment() != null && !Objects.equals(existing.getDepartment().getId(), department.getId())) {
            existing.setDepartment(department);
            updated = true;
        }
        if (existing.getAssignment() != null && !Objects.equals(existing.getAssignment().getId(), assignment.getId())) {
            existing.setAssignment(assignment);
            updated = true;
        }
        if (updated) {
            existing = treatmentRepository.save(existing);
        }
        return existing;
    }

    private List<TreatmentSeed> buildTreatmentSeeds(StaffBundle staffBundle) {
        Staff admin = staffBundle.adminStaff();
        Staff doctor = resolveStaff(staffBundle.doctor(), admin);
        Staff nurse = resolveStaff(staffBundle.nurse(), doctor);
        Staff midwife = resolveStaff(staffBundle.midwife(), nurse);
        Staff lab = resolveStaff(staffBundle.labScientist(), doctor);
        Staff physio = resolveStaff(staffBundle.physiotherapist(), nurse);
        Staff surgeon = resolveStaff(staffBundle.surgeon(), doctor);

        return List.of(
            new TreatmentSeed(
                "prenatal_consult",
                "Prenatal consultation",
                "Comprehensive antenatal visit for expectant mothers.",
                "Consultation prénatale",
                "Visite anténatale complète pour les futures mamans.",
                staffBundle.pediatricsDepartment(),
                resolveAssignment(midwife, staffBundle.adminAssignment()),
                midwife,
                new BigDecimal("35000.00"),
                45
            ),
            new TreatmentSeed(
                "postoperative_care",
                "Post-operative wound care",
                "Sterile dressing change and monitoring of surgical sites.",
                "Soins postopératoires",
                "Changement de pansement stérile et suivi des sites chirurgicaux.",
                staffBundle.generalDepartment(),
                resolveAssignment(nurse, staffBundle.adminAssignment()),
                nurse,
                new BigDecimal("18000.00"),
                30
            ),
            new TreatmentSeed(
                "physical_therapy_session",
                "Physical therapy session",
                "Guided mobility and strengthening exercises.",
                "Séance de kinésithérapie",
                "Exercices guidés de mobilité et de renforcement.",
                staffBundle.generalDepartment(),
                resolveAssignment(physio, staffBundle.adminAssignment()),
                physio,
                new BigDecimal("22000.00"),
                40
            ),
            new TreatmentSeed(
                "metabolic_panel",
                "Comprehensive metabolic panel",
                "Standard metabolic screening with same-day results.",
                "Bilan métabolique complet",
                "Dépistage métabolique standard avec résultats le jour même.",
                staffBundle.laboratoryDepartment(),
                resolveAssignment(lab, staffBundle.adminAssignment()),
                lab,
                LAB_ANALYSIS_PRICE,
                25
            ),
            new TreatmentSeed(
                "day_surgery_preparation",
                "Day surgery preparation",
                "Pre-operative assessment and consent review for minor procedures.",
                "Préparation chirurgie ambulatoire",
                "Évaluation préopératoire et revue de consentement pour interventions mineures.",
                staffBundle.generalDepartment(),
                resolveAssignment(surgeon, staffBundle.adminAssignment()),
                surgeon,
                new BigDecimal("125000.00"),
                60
            )
        );
    }

    private void ensureTranslation(Treatment treatment, UserRoleHospitalAssignment assignment, String languageCode,
                                   String name, String description) {
        if (treatment == null || assignment == null || name == null || name.isBlank() || languageCode == null) {
            return;
        }

        String normalized = languageCode.toLowerCase(Locale.ROOT);
        Set<ServiceTranslation> translations = treatment.getTranslations();
        boolean exists = translations != null && translations.stream()
            .anyMatch(translation -> normalized.equalsIgnoreCase(translation.getLanguageCode()));

        if (exists) {
            return;
        }

        ServiceTranslation translation = ServiceTranslation.builder()
            .treatment(treatment)
            .languageCode(normalized)
            .name(name)
            .description(description)
            .assignment(assignment)
            .build();
        serviceTranslationRepository.save(translation);
        if (translations != null) {
            translations.add(translation);
        }
    }

    private Staff resolveStaff(Staff primary, Staff fallback) {
        return primary != null ? primary : fallback;
    }

    private UserRoleHospitalAssignment resolveAssignment(Staff staff, UserRoleHospitalAssignment fallback) {
        if (staff != null && staff.getAssignment() != null) {
            return staff.getAssignment();
        }
        return fallback;
    }

    private Map<String, Role> ensureRoles() {
        String[] codes = {
            "ROLE_SUPER_ADMIN",
            "ROLE_HOSPITAL_ADMIN",
            "ROLE_DOCTOR",
            "ROLE_PHYSICIAN",
            "ROLE_SURGEON",
            "ROLE_NURSE",
            ROLE_MIDWIFE_CODE,
            "ROLE_PHARMACIST",
            "ROLE_RADIOLOGIST",
            "ROLE_ANESTHESIOLOGIST",
            "ROLE_RECEPTIONIST",
            "ROLE_BILLING_SPECIALIST",
            "ROLE_LAB_SCIENTIST",
            "ROLE_PHYSIOTHERAPIST",
            "ROLE_PATIENT"
        };

        Map<String, Role> cache = new HashMap<>();
        for (String code : codes) {
            Role role = roleRepository.findByCode(code)
                .orElseGet(() -> roleRepository.save(Role.builder()
                    .name(code)
                    .code(code)
                    .description("Synthetic role seed")
                    .build()));
            cache.put(code, role);
        }
        return cache;
    }

    private void ensureSuperAdminUser(Role superAdminRole) {
        if (superAdminRole == null) {
            log.warn("Super admin role not available; skipping super admin user seed");
            return;
        }

        User superAdmin = userRepository.findByUsernameIgnoreCase(SUPER_ADMIN_USERNAME)
            .orElseGet(() -> {
                User user = User.builder()
                    .username(SUPER_ADMIN_USERNAME)
                    .passwordHash(passwordEncoder.encode(SUPER_ADMIN_SEED_SECRET)) // NOSONAR dev-only seed credential
                    .email(SUPER_ADMIN_EMAIL)
                    .firstName("System")
                    .lastName("SuperAdmin")
                    .phoneNumber(formatPhone())
                    .isActive(true)
                    .isDeleted(false)
                    .forcePasswordChange(false)
                    .build();
                return userRepository.save(user);
            });

        boolean changed = false;
        if (!passwordEncoder.matches(SUPER_ADMIN_SEED_SECRET, superAdmin.getPasswordHash())) { // NOSONAR dev-only seed credential
            superAdmin.setPasswordHash(passwordEncoder.encode(SUPER_ADMIN_SEED_SECRET)); // NOSONAR dev-only seed credential
            changed = true;
        }
        if (!superAdmin.isActive()) {
            superAdmin.setActive(true);
            changed = true;
        }
        if (superAdmin.isDeleted()) {
            superAdmin.setDeleted(false);
            changed = true;
        }
        if (changed) {
            userRepository.save(superAdmin);
        }

        addGlobalRole(superAdmin, superAdminRole);
        ensureGlobalAssignment(superAdmin, superAdminRole);
    }

    private void ensureGlobalAssignment(User user, Role role) {
        if (user == null || role == null) {
            return;
        }
        if (assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(user.getId(), role.getId())) {
            return;
        }

        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .assignmentCode(generateGlobalAssignmentCode(role))
            .description("Global assignment for " + role.getCode())
            .startDate(LocalDate.now().minusMonths(1))
            .user(user)
            .role(role)
            .active(true)
            .build();
        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);
        
        // Create permissions for the global assignment
        createPermissionsForAssignment(saved, role);
    }

    private String generateGlobalAssignmentCode(Role role) {
        String roleSegment = role.getCode() == null ? "ROLE" : role.getCode().replace("ROLE_", "");
        String suffix = String.format("%04d", assignmentSequence.getAndIncrement());
        String raw = "GLOBAL-" + roleSegment + "-" + suffix;
        return raw.length() > 50 ? raw.substring(0, 50) : raw;
    }

    private List<LabTestDefinition> ensureLabTests() {
        List<LabTestSeed> seeds = List.of(
            new LabTestSeed("DEV-CBC", "Complete Blood Count", "Routine diagnostics", SAMPLE_TYPE_BLOOD, "cells/mm3", 120),
            new LabTestSeed("DEV-HBA1C", "Hemoglobin A1C", "Diabetes monitoring", SAMPLE_TYPE_BLOOD, "%", 240),
            new LabTestSeed("DEV-LIPID", "Lipid Panel", "Cholesterol panel", SAMPLE_TYPE_BLOOD, "mg/dL", 180)
        );

        List<LabTestDefinition> definitions = new ArrayList<>();
        for (LabTestSeed seed : seeds) {
            LabTestDefinition definition = labTestDefinitionRepository.findActiveGlobalByIdentifier(seed.code())
                .or(() -> labTestDefinitionRepository.findByNameIgnoreCase(seed.name()))
                .orElse(null);

            if (definition == null) {
                definition = LabTestDefinition.builder()
                    .testCode(seed.code())
                    .name(seed.name())
                    .category("ROUTINE")
                    .description(seed.description())
                    .sampleType(seed.sampleType())
                    .unit(seed.unit())
                    .turnaroundTimeMinutes(seed.turnaroundMinutes())
                    .active(true)
                    .build();
            } else {
                definition.setTestCode(seed.code());
                definition.setName(seed.name());
                definition.setCategory("ROUTINE");
                definition.setDescription(seed.description());
                definition.setSampleType(seed.sampleType());
                definition.setUnit(seed.unit());
                definition.setTurnaroundTimeMinutes(seed.turnaroundMinutes());
                definition.setActive(true);
            }

            definitions.add(labTestDefinitionRepository.save(definition));
        }
        return definitions;
    }

    private User createUser(String roleSuffix, String firstName, String lastName) {
        int sequence = userSequence.getAndIncrement();
        String username = String.format("dev_%s_%04d", roleSuffix.toLowerCase(Locale.ROOT), sequence);
    String email = username + SEED_EMAIL_SUFFIX;
        String phone = formatPhone();

        User user = User.builder()
            .username(username)
            .passwordHash(defaultEncodedPassword)
            .email(email)
            .firstName(firstName)
            .lastName(lastName)
            .phoneNumber(phone)
            .isActive(true)
            .isDeleted(false)
            .forcePasswordChange(false)
            .build();
        return userRepository.save(user);
    }

    private void addGlobalRole(User user, Role role) {
        if (user == null || role == null || user.getId() == null || role.getId() == null) {
            return;
        }

        UserRoleId linkId = new UserRoleId(user.getId(), role.getId());
        if (user.getUserRoles() != null) {
            user.getUserRoles().removeIf(existing -> existing.getId() != null && linkId.equals(existing.getId()));
        }

        UserRole link = userRoleRepository.findById(linkId).orElse(null);
        if (link == null) {
            log.debug("Linking global role {} to user {}", role.getCode(), user.getUsername());
            link = UserRole.builder()
                .id(linkId)
                .user(user)
                .role(role)
                .build();
            link = userRoleRepository.save(link);
        } else {
            log.trace("Global role already linked for user={} role={}", user.getUsername(), role.getCode());
        }

        if (user.getUserRoles() != null) {
            user.getUserRoles().add(link);
        }
    }

    private UserRoleHospitalAssignment createAssignment(User user, Hospital hospital, Role role, User registeredBy) {
        String assignmentCode = generateAssignmentCode(hospital.getCode(), role.getCode());
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .assignmentCode(assignmentCode)
            .description(role.getCode() + " assignment for " + hospital.getCode())
            .startDate(LocalDate.now().minusMonths(18))
            .registeredBy(registeredBy)
            .user(user)
            .hospital(hospital)
            .role(role)
            .active(true)
            .build();
        UserRoleHospitalAssignment saved = assignmentRepository.save(assignment);
        createPermissionsForAssignment(saved, role);
        return saved;
    }

    private void createPermissionsForAssignment(UserRoleHospitalAssignment assignment, Role role) {
        if (assignment == null || role == null) {
            return;
        }

        List<String> permissionNames = getPermissionsForRole(role.getCode());
        for (String permissionName : permissionNames) {
            if (assignment.getId() != null && permissionRepository.existsByNameAndAssignment_Id(permissionName, assignment.getId())) {
                continue;
            }

            // Generate code from name: "View Patient Records" -> "VIEW_PATIENT_RECORDS"
            String code = permissionName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            
            Permission permission = Permission.builder()
                .name(permissionName)
                .code(code)
                .assignment(assignment)
                .build();
            permissionRepository.save(permission);
        }
    }

    private List<String> getPermissionsForRole(String roleCode) {
        return PermissionCatalog.permissionsForRole(roleCode);
    }

    private void backfillPermissionsForExistingAssignments() {
        log.info("Backfilling permissions for existing assignments...");
        List<UserRoleHospitalAssignment> allAssignments = assignmentRepository.findAll();
        
        // Load all existing permissions once for efficiency
        List<Permission> allPermissions = permissionRepository.findAll();
        Map<UUID, Set<String>> existingPermissionsByAssignment = new HashMap<>();
        
        for (Permission perm : allPermissions) {
            if (perm.getAssignment() != null && perm.getAssignment().getId() != null) {
                existingPermissionsByAssignment
                    .computeIfAbsent(perm.getAssignment().getId(), k -> new HashSet<>())
                    .add(perm.getName().toLowerCase());
            }
        }
        
        int backfilledCount = 0;
        for (UserRoleHospitalAssignment assignment : allAssignments) {
            if (assignment.getRole() == null || assignment.getId() == null) {
                continue;
            }
            
            Set<String> existingPerms = existingPermissionsByAssignment.getOrDefault(assignment.getId(), Collections.emptySet());
            
            if (existingPerms.isEmpty()) {
                // No permissions for this assignment, create them
                createPermissionsForAssignmentOptimized(assignment, assignment.getRole(), existingPerms);
                backfilledCount++;
            }
        }
        
        log.info("Backfilled permissions for {} assignments", backfilledCount);
    }
    
    private void createPermissionsForAssignmentOptimized(UserRoleHospitalAssignment assignment, Role role, Set<String> existingPermissionNames) {
        if (assignment == null || role == null) {
            return;
        }

        List<String> permissionNames = getPermissionsForRole(role.getCode());
        for (String permissionName : permissionNames) {
            if (existingPermissionNames.contains(permissionName.toLowerCase())
                || (assignment.getId() != null && permissionRepository.existsByNameAndAssignment_Id(permissionName, assignment.getId()))) {
                continue;
            }

            // Generate code from name: "View Patient Records" -> "VIEW_PATIENT_RECORDS"
            String code = permissionName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_");
            
            Permission permission = Permission.builder()
                .name(permissionName)
                .code(code)
                .assignment(assignment)
                .build();
            permissionRepository.save(permission);
        }
    }

    private void seedFeaturedAccountsIfNeeded() {
        if (featuredAccountsSeeded) {
            return;
        }

        Hospital featuredHospital = hospitalRepository.findByCodeIgnoreCase(FEATURED_HOSPITAL_CODE)
            .orElse(null);
        if (featuredHospital == null) {
            log.debug("Featured hospital {} not ready yet; delaying curated account seed", FEATURED_HOSPITAL_CODE);
            return;
        }

        FEATURED_ACCOUNTS.forEach(seed -> seedFeaturedAccount(seed, featuredHospital));
        featuredAccountsSeeded = true;
        log.info("Seeded {} featured developer accounts anchored to {}", FEATURED_ACCOUNTS.size(), FEATURED_HOSPITAL_CODE);
    }

    private void seedFeaturedAccount(FeaturedAccountSeed seed, Hospital hospital) {
        Role role = roleCache.get(seed.roleCode());
        if (role == null) {
            log.warn("Skipping featured account {} because role {} is unavailable", seed.username(), seed.roleCode());
            return;
        }

        User user = ensureFeaturedUser(seed);
        ensureSequenceAheadOf(seed.username());
        addGlobalRole(user, role);
        ensureGlobalAssignment(user, role);

        UserRoleHospitalAssignment assignment = assignmentRepository
            .findByUserIdAndHospitalIdAndRoleId(user.getId(), hospital.getId(), role.getId())
            .orElseGet(() -> createAssignment(user, hospital, role, null));

        if (!Boolean.TRUE.equals(assignment.getActive())) {
            assignment.setActive(true);
            assignmentRepository.save(assignment);
        }

        Department department = resolveDepartmentForFeaturedAccount(hospital, seed.departmentCode())
            .orElse(null);
        if (department == null) {
            log.warn("Unable to locate department {} for hospital {}; skipping staff profile for {}",
                seed.departmentCode(), hospital.getCode(), seed.username());
            return;
        }

        Optional<Staff> existingStaff = staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId());
        if (existingStaff.isEmpty()) {
            createStaffForExistingUser(user, assignment, hospital, department, seed.jobTitle(),
                seed.employmentType(), seed.specialization());
        } else {
            Staff staff = existingStaff.get();
            boolean changed = false;
            if (!staff.isActive()) {
                staff.setActive(true);
                changed = true;
            }
            if (staff.getDepartment() == null || !Objects.equals(staff.getDepartment().getId(), department.getId())) {
                staff.setDepartment(department);
                changed = true;
            }
            if (changed) {
                staffRepository.save(staff);
            }
        }
    }

    private User ensureFeaturedUser(FeaturedAccountSeed seed) {
        User user = userRepository.findByUsernameIgnoreCase(seed.username())
            .orElseGet(() -> userRepository.save(User.builder()
                .username(seed.username())
                .passwordHash(defaultEncodedPassword)
                .email(seed.username() + SEED_EMAIL_SUFFIX)
                .firstName(seed.firstName())
                .lastName(seed.lastName())
                .phoneNumber(formatPhone())
                .isActive(true)
                .isDeleted(false)
                .forcePasswordChange(false)
                .build()));

        boolean updated = false;
        if (!Objects.equals(user.getFirstName(), seed.firstName())) {
            user.setFirstName(seed.firstName());
            updated = true;
        }
        if (!Objects.equals(user.getLastName(), seed.lastName())) {
            user.setLastName(seed.lastName());
            updated = true;
        }
    String expectedEmail = seed.username() + SEED_EMAIL_SUFFIX;
        if (!Objects.equals(user.getEmail(), expectedEmail)) {
            user.setEmail(expectedEmail);
            updated = true;
        }
        if (user.getPhoneNumber() == null || user.getPhoneNumber().isBlank()) {
            user.setPhoneNumber(formatPhone());
            updated = true;
        }
        if (user.getPasswordHash() == null || !passwordEncoder.matches(DEFAULT_DEV_SEED_SECRET, user.getPasswordHash())) { // NOSONAR dev-only seed credential
            user.setPasswordHash(defaultEncodedPassword);
            updated = true;
        }
        if (!user.isActive()) {
            user.setActive(true);
            updated = true;
        }
        if (user.isDeleted()) {
            user.setDeleted(false);
            updated = true;
        }
        if (updated) {
            user = userRepository.save(user);
        }
        return user;
    }

    private Optional<Department> resolveDepartmentForFeaturedAccount(Hospital hospital, String departmentCode) {
        if (hospital == null || hospital.getId() == null) {
            return Optional.empty();
        }

        Optional<Department> byCode = departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), departmentCode);
        if (byCode.isPresent()) {
            return byCode;
        }

        List<Department> departments = departmentRepository.findByHospitalId(hospital.getId());
        return departments.stream().findFirst();
    }

    private void ensureSequenceAheadOf(String username) {
        extractDevUserSequence(username).ifPresent(this::bumpUserSequence);
    }

    private void bumpUserSequence(int sequenceValue) {
        int target = sequenceValue + 1;
        userSequence.updateAndGet(current -> Math.max(current, target));
    }

    private String formatPhone() {
        return PHONE_PREFIX + String.format("%08d", phoneSequence.getAndIncrement());
    }

    private String generateLicense(String seed) {
        return String.format("LIC-%s-%05d", seed.replaceAll("[^A-Za-z]", "").toUpperCase(Locale.ROOT), licenseSequence.getAndIncrement());
    }

    private String generateMrn(Hospital hospital, int patientOrdinal) {
        String prefix = deriveHospitalPrefix(hospital);
        int normalizedOrdinal = Math.abs(patientOrdinal) % 10_000;
        return prefix + String.format("%04d", normalizedOrdinal);
    }

    private String deriveHospitalPrefix(Hospital hospital) {
        String prefix = sanitizePrefix(hospital != null ? hospital.getCode() : null);
        if (prefix != null) {
            return prefix;
        }

        prefix = sanitizePrefix(hospital != null ? hospital.getName() : null);
        if (prefix != null) {
            return prefix;
        }

        return DEFAULT_MRN_PREFIX;
    }

    private String sanitizePrefix(String source) {
        if (source == null) {
            return null;
        }

        String cleaned = source.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
        if (cleaned.isBlank()) {
            return null;
        }

        if (cleaned.length() >= 3) {
            return cleaned.substring(0, 3);
        }

        return (cleaned + DEFAULT_MRN_PREFIX).substring(0, 3);
    }

    private String selectBloodType(int index) {
        String[] types = {"A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-"};
        return types[index % types.length];
    }

    private String generateAssignmentCode(String hospitalCode, String roleCode) {
        String suffix = String.format("%04d", assignmentSequence.getAndIncrement());
        String roleSegment = roleCode.replace("ROLE_", "");
        String raw = hospitalCode + "-" + roleSegment + "-" + suffix;
        return raw.length() > 50 ? raw.substring(0, 50) : raw;
    }

    private String selectCity(int orgIndex, int hospitalIndex) {
        String[] cities = {"Ouagadougou", "Bobo-Dioulasso", "Koudougou", "Kaya", "Tenkodogo",
            "Fada N'Gourma", "Banfora", "Gaoua", "Dori", "Ouahigouya"};
        return cities[(orgIndex + hospitalIndex) % cities.length];
    }

    private String selectState(int orgIndex, int hospitalIndex) {
        String[] regions = {"Centre", "Hauts-Bassins", "Plateau-Central", "Centre-Est", "Sahel",
            "Cascade", "Nord", "Centre-Ouest", "Boucle du Mouhoun", "Centre-Nord"};
        return regions[(orgIndex * 3 + hospitalIndex) % regions.length];
    }

    private String pickName(List<String> pool, String salt) {
        int hash = Math.abs(Objects.hash(salt));
        return pool.get(hash % pool.size());
    }

    private record LabTestSeed(String code, String name, String description, String sampleType, String unit,
                               int turnaroundMinutes) {
    }

    private record TreatmentSeed(
        String slug,
        String nameEn,
        String descriptionEn,
        String nameFr,
        String descriptionFr,
        Department department,
        UserRoleHospitalAssignment assignment,
        Staff performer,
        BigDecimal price,
        int durationMinutes
    ) {
    }

    private record SeededTreatment(Treatment treatment, Staff performer) {
    }

    private record StaffBundle(
        User adminUser,
        UserRoleHospitalAssignment adminAssignment,
        Department generalDepartment,
        Department pediatricsDepartment,
        Department laboratoryDepartment,
        Staff adminStaff,
        Staff doctor,
        Staff nurse,
        Staff receptionist,
        Staff billing,
        Staff labScientist,
        Staff surgeon,
        Staff physician,
        Staff midwife,
        Staff radiologist,
        Staff anesthesiologist,
        Staff physiotherapist
    ) {
    }
}
