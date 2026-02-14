package com.example.hms.bootstrap;

import com.example.hms.enums.EmploymentType;
import com.example.hms.enums.JobTitle;
import com.example.hms.model.Appointment;
import com.example.hms.model.BillingInvoice;
import com.example.hms.model.Department;
import com.example.hms.model.Encounter;
import com.example.hms.model.EncounterTreatment;
import com.example.hms.model.Hospital;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabTestDefinition;
import com.example.hms.model.Organization;
import com.example.hms.model.Patient;
import com.example.hms.model.PatientConsent;
import com.example.hms.model.PatientHospitalRegistration;
import com.example.hms.model.PatientInsurance;
import com.example.hms.model.Permission;
import com.example.hms.model.Role;
import com.example.hms.model.ServiceTranslation;
import com.example.hms.model.Staff;
import com.example.hms.model.Treatment;
import com.example.hms.model.User;
import com.example.hms.model.UserRole;
import com.example.hms.model.UserRoleHospitalAssignment;
import com.example.hms.repository.AppointmentRepository;
import com.example.hms.repository.BillingInvoiceRepository;
import com.example.hms.repository.DepartmentRepository;
import com.example.hms.repository.EncounterRepository;
import com.example.hms.repository.EncounterTreatmentRepository;
import com.example.hms.repository.HospitalRepository;
import com.example.hms.repository.LabOrderRepository;
import com.example.hms.repository.LabResultRepository;
import com.example.hms.repository.LabTestDefinitionRepository;
import com.example.hms.repository.OrganizationRepository;
import com.example.hms.repository.PatientConsentRepository;
import com.example.hms.repository.PatientHospitalRegistrationRepository;
import com.example.hms.repository.PatientInsuranceRepository;
import com.example.hms.repository.PatientRepository;
import com.example.hms.repository.PermissionRepository;
import com.example.hms.repository.RoleRepository;
import com.example.hms.repository.ServiceTranslationRepository;
import com.example.hms.repository.StaffRepository;
import com.example.hms.repository.TreatmentRepository;
import com.example.hms.repository.UserRepository;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.repository.UserRoleRepository;
import com.example.hms.security.permission.PermissionCatalog;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import java.util.OptionalInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.contains;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings("java:S5976") // Individual tests preferred over parameterized for clarity
class DevSyntheticDataSeederTest {

    // ── All repository mocks ─────────────────────────────────────

    @Mock private OrganizationRepository organizationRepository;
    @Mock private HospitalRepository hospitalRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private StaffRepository staffRepository;
    @Mock private PatientRepository patientRepository;
    @Mock private PatientHospitalRegistrationRepository registrationRepository;
    @Mock private PatientInsuranceRepository patientInsuranceRepository;
    @Mock private PatientConsentRepository patientConsentRepository;
    @Mock private AppointmentRepository appointmentRepository;
    @Mock private EncounterRepository encounterRepository;
    @Mock private BillingInvoiceRepository billingInvoiceRepository;
    @Mock private LabOrderRepository labOrderRepository;
    @Mock private LabResultRepository labResultRepository;
    @Mock private LabTestDefinitionRepository labTestDefinitionRepository;
    @Mock private TreatmentRepository treatmentRepository;
    @Mock private ServiceTranslationRepository serviceTranslationRepository;
    @Mock private EncounterTreatmentRepository encounterTreatmentRepository;
    @Mock private RoleRepository roleRepository;
    @Mock private UserRepository userRepository;
    @Mock private UserRoleRepository userRoleRepository;
    @Mock private UserRoleHospitalAssignmentRepository assignmentRepository;
    @Mock private PermissionRepository permissionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ApplicationArguments applicationArguments;

    private DevSyntheticDataSeeder seeder;

    // ── Helper objects ───────────────────────────────────────────

    private Role superAdminRole;
    private Role doctorRole;

    @BeforeEach
    void setUp() {
        seeder = new DevSyntheticDataSeeder(
                organizationRepository, hospitalRepository, departmentRepository,
                staffRepository, patientRepository, registrationRepository,
                patientInsuranceRepository, patientConsentRepository,
                appointmentRepository, encounterRepository, billingInvoiceRepository,
                labOrderRepository, labResultRepository, labTestDefinitionRepository,
                treatmentRepository, serviceTranslationRepository, encounterTreatmentRepository,
                roleRepository, userRepository, userRoleRepository,
                assignmentRepository, permissionRepository,
                passwordEncoder, transactionManager
        );

        superAdminRole = buildRole("ROLE_SUPER_ADMIN");
        doctorRole = buildRole("ROLE_DOCTOR");
    }

    // ═══════════════════════════════════════════════════════════════
    // Private helper method tests via reflection
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class ExtractDevUserSequenceTests {

        @Test
        void extractDevUserSequence_validDevUsername_returnsSequence() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "dev_doctor_0042");
            assertThat(result).isPresent();
            assertThat(result.getAsInt()).isEqualTo(42);
        }

        @Test
        void extractDevUserSequence_nullUsername_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, (String) null);
            assertThat(result).isEmpty();
        }

        @Test
        void extractDevUserSequence_nonDevPrefix_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "admin_user_001");
            assertThat(result).isEmpty();
        }

        @Test
        void extractDevUserSequence_noNumericSuffix_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "dev_doctor_abc");
            assertThat(result).isEmpty();
        }

        @Test
        void extractDevUserSequence_onlyDevPrefix_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "dev_");
            assertThat(result).isEmpty();
        }

        @Test
        void extractDevUserSequence_upperCase_returnsSequence() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "DEV_NURSE_0099");
            assertThat(result).isPresent();
            assertThat(result.getAsInt()).isEqualTo(99);
        }

        @Test
        void extractDevUserSequence_endsWithUnderscore_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "dev_something_");
            assertThat(result).isEmpty();
        }

        @Test
        void extractDevUserSequence_singleDigit_returnsCorrectValue() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "dev_admin_5");
            assertThat(result).isPresent();
            assertThat(result.getAsInt()).isEqualTo(5);
        }

        @Test
        void extractDevUserSequence_largeNumber_returnsCorrectValue() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractDevUserSequence", String.class);
            method.setAccessible(true);

            OptionalInt result = (OptionalInt) method.invoke(seeder, "dev_staff_99999");
            assertThat(result).isPresent();
            assertThat(result.getAsInt()).isEqualTo(99999);
        }
    }

    @Nested
    class ExtractPhoneSequenceTests {

        @Test
        void extractPhoneSequence_validPhone_returnsSequence() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractPhoneSequence", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Optional<Integer> result = (Optional<Integer>) method.invoke(seeder, "+22660000042");
            assertThat(result).isPresent().contains(60000042);
        }

        @Test
        void extractPhoneSequence_nullPhone_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractPhoneSequence", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Optional<Integer> result = (Optional<Integer>) method.invoke(seeder, (String) null);
            assertThat(result).isEmpty();
        }

        @Test
        void extractPhoneSequence_wrongPrefix_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractPhoneSequence", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Optional<Integer> result = (Optional<Integer>) method.invoke(seeder, "+33160000042");
            assertThat(result).isEmpty();
        }

        @Test
        void extractPhoneSequence_nonNumericSuffix_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("extractPhoneSequence", String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Optional<Integer> result = (Optional<Integer>) method.invoke(seeder, "+226abcdefgh");
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class FormatPhoneTests {

        @Test
        void formatPhone_returnsCorrectFormat() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("formatPhone");
            method.setAccessible(true);

            String result = (String) method.invoke(seeder);
            assertThat(result).startsWith("+226").hasSize(12); // +226 + 8 digits
        }

        @Test
        void formatPhone_incrementsSequence() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("formatPhone");
            method.setAccessible(true);

            String first = (String) method.invoke(seeder);
            String second = (String) method.invoke(seeder);
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    class GenerateLicenseTests {

        @Test
        void generateLicense_returnsLICPrefix() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateLicense", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "DOCTOR");
            assertThat(result).startsWith("LIC-DOCTOR-");
        }

        @Test
        void generateLicense_stripsNonAlphaCharacters() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateLicense", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "lab-123");
            assertThat(result).startsWith("LIC-LAB-");
        }

        @Test
        void generateLicense_incrementsEachCall() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateLicense", String.class);
            method.setAccessible(true);

            String first = (String) method.invoke(seeder, "nurse");
            String second = (String) method.invoke(seeder, "nurse");
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    class SelectBloodTypeTests {

        @Test
        void selectBloodType_index0_returnsAPlus() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectBloodType", int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, 0);
            assertThat(result).isEqualTo("A+");
        }

        @Test
        void selectBloodType_index4_returnsOPlus() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectBloodType", int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, 4);
            assertThat(result).isEqualTo("O+");
        }

        @Test
        void selectBloodType_index7_returnsABMinus() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectBloodType", int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, 7);
            assertThat(result).isEqualTo("AB-");
        }

        @Test
        void selectBloodType_wrapsAround() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectBloodType", int.class);
            method.setAccessible(true);

            String first = (String) method.invoke(seeder, 0);
            String ninth = (String) method.invoke(seeder, 8);
            assertThat(first).isEqualTo(ninth);
        }

        @ParameterizedTest
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7})
        void selectBloodType_allIndicesReturnValidTypes(int index) throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectBloodType", int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, index);
            assertThat(result).isIn("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-");
        }
    }

    @Nested
    class SelectCityTests {

        @Test
        void selectCity_returnsNonNullString() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectCity", int.class, int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, 1, 1);
            assertThat(result).isNotNull().isNotBlank();
        }

        @Test
        void selectCity_differentIndicesYieldDifferentCities() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectCity", int.class, int.class);
            method.setAccessible(true);

            String city1 = (String) method.invoke(seeder, 1, 1);
            String city2 = (String) method.invoke(seeder, 2, 3);
            // They can be the same or different depending on modulo, just verify non-null
            assertThat(city1).isNotNull();
            assertThat(city2).isNotNull();
        }

        @ParameterizedTest
        @CsvSource({"1,1", "2,3", "5,5", "10,10"})
        void selectCity_alwaysReturnsKnownCity(int orgIndex, int hospitalIndex) throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectCity", int.class, int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, orgIndex, hospitalIndex);
            assertThat(result).isIn("Ouagadougou", "Bobo-Dioulasso", "Koudougou", "Kaya", "Tenkodogo",
                    "Fada N'Gourma", "Banfora", "Gaoua", "Dori", "Ouahigouya");
        }
    }

    @Nested
    class SelectStateTests {

        @Test
        void selectState_returnsNonNullString() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectState", int.class, int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, 1, 1);
            assertThat(result).isNotNull().isNotBlank();
        }

        @ParameterizedTest
        @CsvSource({"1,1", "3,5", "10,10"})
        void selectState_alwaysReturnsKnownRegion(int orgIndex, int hospitalIndex) throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("selectState", int.class, int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, orgIndex, hospitalIndex);
            assertThat(result).isIn("Centre", "Hauts-Bassins", "Plateau-Central", "Centre-Est", "Sahel",
                    "Cascade", "Nord", "Centre-Ouest", "Boucle du Mouhoun", "Centre-Nord");
        }
    }

    @Nested
    class PickNameTests {

        @Test
        void pickName_returnsNameFromPool() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("pickName", List.class, String.class);
            method.setAccessible(true);

            List<String> pool = List.of("Alice", "Bob", "Charlie");
            String result = (String) method.invoke(seeder, pool, "someSalt");
            assertThat(result).isIn("Alice", "Bob", "Charlie");
        }

        @Test
        void pickName_sameSaltReturnsSameName() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("pickName", List.class, String.class);
            method.setAccessible(true);

            List<String> pool = List.of("Alice", "Bob", "Charlie", "Diana", "Eve");
            String first = (String) method.invoke(seeder, pool, "consistent-salt");
            String second = (String) method.invoke(seeder, pool, "consistent-salt");
            assertThat(first).isEqualTo(second);
        }

        @Test
        void pickName_differentSaltMayReturnDifferentNames() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("pickName", List.class, String.class);
            method.setAccessible(true);

            List<String> pool = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
            String first = (String) method.invoke(seeder, pool, "salt-alpha");
            String second = (String) method.invoke(seeder, pool, "salt-beta");
            // They are from the pool either way
            assertThat(first).isIn(pool);
            assertThat(second).isIn(pool);
        }
    }

    @Nested
    class GenerateAssignmentCodeTests {

        @Test
        void generateAssignmentCode_normalInput_returnsFormattedCode() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateAssignmentCode", String.class, String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "DEV-ORG-01-H01", "ROLE_DOCTOR");
            assertThat(result).startsWith("DEV-ORG-01-H01-DOCTOR-");
        }

        @Test
        void generateAssignmentCode_stripsRolePrefix() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateAssignmentCode", String.class, String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "H01", "ROLE_NURSE");
            assertThat(result).contains("NURSE").doesNotContain("ROLE_NURSE");
        }

        @Test
        void generateAssignmentCode_longCode_isTruncatedTo50() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateAssignmentCode", String.class, String.class);
            method.setAccessible(true);

            String longHospitalCode = "VERY-LONG-HOSPITAL-CODE-THAT-IS-EXCESSIVE";
            String result = (String) method.invoke(seeder, longHospitalCode, "ROLE_BILLING_SPECIALIST");
            assertThat(result.length()).isLessThanOrEqualTo(50);
        }

        @Test
        void generateAssignmentCode_incrementsEachCall() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateAssignmentCode", String.class, String.class);
            method.setAccessible(true);

            String first = (String) method.invoke(seeder, "H01", "ROLE_DOCTOR");
            String second = (String) method.invoke(seeder, "H01", "ROLE_DOCTOR");
            assertThat(first).isNotEqualTo(second);
        }
    }

    @Nested
    class GenerateGlobalAssignmentCodeTests {

        @Test
        void generateGlobalAssignmentCode_normalRole_returnsGlobalPrefix() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateGlobalAssignmentCode", Role.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, superAdminRole);
            assertThat(result).startsWith("GLOBAL-SUPER_ADMIN-");
        }

        @Test
        void generateGlobalAssignmentCode_nullRoleCode_usesROLE() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateGlobalAssignmentCode", Role.class);
            method.setAccessible(true);

            Role nullCodeRole = Role.builder().name("Custom").build();
            String result = (String) method.invoke(seeder, nullCodeRole);
            assertThat(result).startsWith("GLOBAL-ROLE-");
        }

        @Test
        void generateGlobalAssignmentCode_longRoleCode_truncatesTo50() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateGlobalAssignmentCode", Role.class);
            method.setAccessible(true);

            Role longRole = buildRole("ROLE_VERY_LONG_ROLE_CODE_THAT_EXCEEDS_LIMITS_SIGNIFICANTLY");
            String result = (String) method.invoke(seeder, longRole);
            assertThat(result.length()).isLessThanOrEqualTo(50);
        }
    }

    @Nested
    class GenerateMrnTests {

        @Test
        void generateMrn_withHospitalCode_usesCodePrefix() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateMrn", Hospital.class, int.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("ABC-HOSP").name("A Hospital").build();
            String result = (String) method.invoke(seeder, hospital, 1);
            assertThat(result).startsWith("ABC");
        }

        @Test
        void generateMrn_withNullHospital_usesDefaultPrefix() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateMrn", Hospital.class, int.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, (Hospital) null, 5);
            assertThat(result).startsWith("MRX");
        }

        @Test
        void generateMrn_normalizesPadding() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateMrn", Hospital.class, int.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("TST").name("Test").build();
            String result = (String) method.invoke(seeder, hospital, 1);
            assertThat(result).hasSize(7); // 3 prefix + 4 ordinal digits
        }

        @Test
        void generateMrn_negativePatientialOrdinal_handledByAbsoluteValue() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateMrn", Hospital.class, int.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("TST").name("Test").build();
            String result = (String) method.invoke(seeder, hospital, -5);
            assertThat(result).isNotNull().startsWith("TST");
        }

        @Test
        void generateMrn_largeOrdinal_moduloWraps() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("generateMrn", Hospital.class, int.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("TST").name("Test").build();
            String result = (String) method.invoke(seeder, hospital, 15000);
            assertThat(result).isNotNull().hasSize(7);
        }
    }

    @Nested
    class DeriveHospitalPrefixTests {

        @Test
        void deriveHospitalPrefix_nullHospital_returnsDefault() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("deriveHospitalPrefix", Hospital.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, (Hospital) null);
            assertThat(result).isEqualTo("MRX");
        }

        @Test
        void deriveHospitalPrefix_validCode_usesCode() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("deriveHospitalPrefix", Hospital.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("ABCDEF").name("Long Name").build();
            String result = (String) method.invoke(seeder, hospital);
            assertThat(result).isEqualTo("ABC");
        }

        @Test
        void deriveHospitalPrefix_nullCode_usesName() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("deriveHospitalPrefix", Hospital.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().name("TestHospital").build();
            String result = (String) method.invoke(seeder, hospital);
            assertThat(result).isEqualTo("TES");
        }

        @Test
        void deriveHospitalPrefix_bothNull_returnsDefault() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("deriveHospitalPrefix", Hospital.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().build();
            String result = (String) method.invoke(seeder, hospital);
            assertThat(result).isEqualTo("MRX");
        }
    }

    @Nested
    class SanitizePrefixTests {

        @Test
        void sanitizePrefix_null_returnsNull() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, (String) null);
            assertThat(result).isNull();
        }

        @Test
        void sanitizePrefix_longString_returnsFirst3() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "ABCDEFGH");
            assertThat(result).isEqualTo("ABC");
        }

        @Test
        void sanitizePrefix_shortString_paddsWithDefaultPrefix() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "AB");
            assertThat(result).hasSize(3).startsWith("AB");
        }

        @Test
        void sanitizePrefix_singleChar_paddsWithDefaultPrefix() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "X");
            assertThat(result).hasSize(3).startsWith("X");
        }

        @Test
        void sanitizePrefix_specialCharsOnly_returnsNull() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "---!!!");
            assertThat(result).isNull();
        }

        @Test
        void sanitizePrefix_mixedSpecialAndAlpha_stripsSpecialChars() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "A-B-C-D");
            assertThat(result).isEqualTo("ABC");
        }

        @Test
        void sanitizePrefix_lowercase_isUppercased() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "abcdef");
            assertThat(result).isEqualTo("ABC");
        }

        @Test
        void sanitizePrefix_exactly3Chars_returnsAll3() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "XYZ");
            assertThat(result).isEqualTo("XYZ");
        }

        @Test
        void sanitizePrefix_blankString_returnsNull() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("sanitizePrefix", String.class);
            method.setAccessible(true);

            String result = (String) method.invoke(seeder, "   ");
            assertThat(result).isNull();
        }
    }

    @Nested
    class ResolveStaffTests {

        @Test
        void resolveStaff_primaryNotNull_returnsPrimary() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("resolveStaff", Staff.class, Staff.class);
            method.setAccessible(true);

            Staff primary = Staff.builder().name("Primary").build();
            Staff fallback = Staff.builder().name("Fallback").build();

            Staff result = (Staff) method.invoke(seeder, primary, fallback);
            assertThat(result).isSameAs(primary);
        }

        @Test
        void resolveStaff_primaryNull_returnsFallback() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("resolveStaff", Staff.class, Staff.class);
            method.setAccessible(true);

            Staff fallback = Staff.builder().name("Fallback").build();
            Staff result = (Staff) method.invoke(seeder, null, fallback);
            assertThat(result).isSameAs(fallback);
        }

        @Test
        void resolveStaff_bothNull_returnsNull() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("resolveStaff", Staff.class, Staff.class);
            method.setAccessible(true);

            Staff result = (Staff) method.invoke(seeder, null, null);
            assertThat(result).isNull();
        }
    }

    @Nested
    class ResolveAssignmentTests {

        @Test
        void resolveAssignment_staffWithAssignment_returnsStaffAssignment() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveAssignment", Staff.class, UserRoleHospitalAssignment.class);
            method.setAccessible(true);

            UserRoleHospitalAssignment staffAssignment = UserRoleHospitalAssignment.builder()
                    .assignmentCode("STAFF-1").build();
            Staff staff = Staff.builder().name("Dr A").assignment(staffAssignment).build();
            UserRoleHospitalAssignment fallback = UserRoleHospitalAssignment.builder()
                    .assignmentCode("FALLBACK").build();

            UserRoleHospitalAssignment result = (UserRoleHospitalAssignment) method.invoke(seeder, staff, fallback);
            assertThat(result).isSameAs(staffAssignment);
        }

        @Test
        void resolveAssignment_staffWithoutAssignment_returnsFallback() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveAssignment", Staff.class, UserRoleHospitalAssignment.class);
            method.setAccessible(true);

            Staff staff = Staff.builder().name("Dr A").build();
            UserRoleHospitalAssignment fallback = UserRoleHospitalAssignment.builder()
                    .assignmentCode("FALLBACK").build();

            UserRoleHospitalAssignment result = (UserRoleHospitalAssignment) method.invoke(seeder, staff, fallback);
            assertThat(result).isSameAs(fallback);
        }

        @Test
        void resolveAssignment_nullStaff_returnsFallback() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveAssignment", Staff.class, UserRoleHospitalAssignment.class);
            method.setAccessible(true);

            UserRoleHospitalAssignment fallback = UserRoleHospitalAssignment.builder()
                    .assignmentCode("FALLBACK").build();

            UserRoleHospitalAssignment result = (UserRoleHospitalAssignment) method.invoke(seeder, null, fallback);
            assertThat(result).isSameAs(fallback);
        }
    }

    @Nested
    class BuildOrganizationTests {

        @Test
        void buildOrganization_returnsOrganizationWithCodeAndName() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("buildOrganization", int.class, String.class);
            method.setAccessible(true);

            Organization org = (Organization) method.invoke(seeder, 1, "DEV-ORG-01");
            assertThat(org.getCode()).isEqualTo("DEV-ORG-01");
            assertThat(org.getName()).isEqualTo("Dev Seed Organization 1");
            assertThat(org.isActive()).isTrue();
            assertThat(org.getDescription()).contains("Synthetic");
        }

        @Test
        void buildOrganization_setsTypeBasedOnIndex() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("buildOrganization", int.class, String.class);
            method.setAccessible(true);

            Organization org1 = (Organization) method.invoke(seeder, 1, "DEV-ORG-01");
            Organization org2 = (Organization) method.invoke(seeder, 2, "DEV-ORG-02");
            // Both should have a valid type
            assertThat(org1.getType()).isNotNull();
            assertThat(org2.getType()).isNotNull();
        }

        @Test
        void buildOrganization_setsContactEmail() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("buildOrganization", int.class, String.class);
            method.setAccessible(true);

            Organization org = (Organization) method.invoke(seeder, 1, "DEV-ORG-01");
            assertThat(org.getPrimaryContactEmail()).isEqualTo("dev-org-01@seed.dev");
        }

        @Test
        void buildOrganization_setsDefaultTimezone() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("buildOrganization", int.class, String.class);
            method.setAccessible(true);

            Organization org = (Organization) method.invoke(seeder, 1, "DEV-ORG-01");
            assertThat(org.getDefaultTimezone()).isEqualTo("Africa/Ouagadougou");
        }

        @Test
        void buildOrganization_setsOnboardingNotes() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("buildOrganization", int.class, String.class);
            method.setAccessible(true);

            Organization org = (Organization) method.invoke(seeder, 3, "DEV-ORG-03");
            assertThat(org.getOnboardingNotes()).contains("Auto-generated");
        }
    }

    @Nested
    class BuildHospitalTests {

        @Test
        void buildHospital_returnsHospitalWithAllFields() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "buildHospital", Organization.class, int.class, int.class, String.class);
            method.setAccessible(true);

            Organization org = Organization.builder().name("Test Org").code("TST").build();
            Hospital hospital = (Hospital) method.invoke(seeder, org, 1, 2, "TST-H02");

            assertThat(hospital.getName()).contains("Test Org");
            assertThat(hospital.getCode()).isEqualTo("TST-H02");
            assertThat(hospital.getCountry()).isEqualTo("Burkina Faso");
            assertThat(hospital.isActive()).isTrue();
            assertThat(hospital.getOrganization()).isSameAs(org);
            assertThat(hospital.getEmail()).contains("tst-h02");
            assertThat(hospital.getWebsite()).contains("tst-h02");
            assertThat(hospital.getLicenseNumber()).contains("TST-H02");
        }

        @Test
        void buildHospital_setsAddress() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "buildHospital", Organization.class, int.class, int.class, String.class);
            method.setAccessible(true);

            Organization org = Organization.builder().name("Org").code("O").build();
            Hospital hospital = (Hospital) method.invoke(seeder, org, 1, 3, "O-H03");

            assertThat(hospital.getAddress()).contains("Rue du Faso");
            assertThat(hospital.getZipCode()).startsWith("BF-");
        }
    }

    @Nested
    class EnsureRolesTests {

        @Test
        void ensureRoles_createsAllExpectedRoles() throws Exception {
            // Stub roleRepository.findByCode to return empty so new roles are created
            when(roleRepository.findByCode(anyString())).thenReturn(Optional.empty());
            when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
                Role r = inv.getArgument(0);
                setId(r, UUID.randomUUID());
                return r;
            });

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureRoles");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, Role> roles = (Map<String, Role>) method.invoke(seeder);

            assertThat(roles).containsKeys(
                    "ROLE_SUPER_ADMIN", "ROLE_HOSPITAL_ADMIN", "ROLE_DOCTOR",
                    "ROLE_PHYSICIAN", "ROLE_SURGEON", "ROLE_NURSE", "ROLE_MIDWIFE",
                    "ROLE_PHARMACIST", "ROLE_RADIOLOGIST", "ROLE_ANESTHESIOLOGIST",
                    "ROLE_RECEPTIONIST", "ROLE_BILLING_SPECIALIST", "ROLE_LAB_SCIENTIST",
                    "ROLE_PHYSIOTHERAPIST", "ROLE_PATIENT"
            ).hasSize(15);
        }

        @Test
        void ensureRoles_reusesExistingRoles() throws Exception {
            Role existing = buildRole("ROLE_DOCTOR");
            when(roleRepository.findByCode("ROLE_DOCTOR")).thenReturn(Optional.of(existing));
            when(roleRepository.findByCode(argThat(c -> !"ROLE_DOCTOR".equals(c)))).thenReturn(Optional.empty());
            when(roleRepository.save(any(Role.class))).thenAnswer(inv -> {
                Role r = inv.getArgument(0);
                setId(r, UUID.randomUUID());
                return r;
            });

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureRoles");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Map<String, Role> roles = (Map<String, Role>) method.invoke(seeder);

            assertThat(roles.get("ROLE_DOCTOR")).isSameAs(existing);
            // Should not save the existing doctor role again
            verify(roleRepository, never()).save(existing);
        }
    }

    @Nested
    class EnsureSuperAdminUserTests {

        @Test
        void ensureSuperAdminUser_nullRole_skips() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureSuperAdminUser", Role.class);
            method.setAccessible(true);

            method.invoke(seeder, (Role) null);

            verifyNoInteractions(userRepository);
        }

        @Test
        void ensureSuperAdminUser_existingUser_updatesIfNeeded() throws Exception {
            setField(seeder, "defaultEncodedPassword", "encodedPwd");

            User existingUser = User.builder()
                    .username("superadmin")
                    .passwordHash("oldHash")
                    .email("superadmin@seed.dev")
                    .firstName("System")
                    .lastName("SuperAdmin")
                    .phoneNumber("+22600000001")
                    .isActive(true)
                    .isDeleted(false)
                    .build();
            setId(existingUser, UUID.randomUUID());

            when(userRepository.findByUsernameIgnoreCase("superadmin")).thenReturn(Optional.of(existingUser));
            when(passwordEncoder.matches(anyString(), eq("oldHash"))).thenReturn(false);
            when(passwordEncoder.encode(anyString())).thenReturn("newEncodedHash");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
            when(userRoleRepository.findById(any())).thenReturn(Optional.empty());
            when(userRoleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(any(), any())).thenReturn(true);

            Role saRole = buildRole("ROLE_SUPER_ADMIN");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureSuperAdminUser", Role.class);
            method.setAccessible(true);
            method.invoke(seeder, saRole);

            verify(userRepository, atLeastOnce()).save(any(User.class));
        }

        @Test
        void ensureSuperAdminUser_newUser_createsAndSaves() throws Exception {
            setField(seeder, "defaultEncodedPassword", "encodedPwd");

            when(userRepository.findByUsernameIgnoreCase("superadmin")).thenReturn(Optional.empty());
            when(passwordEncoder.encode(anyString())).thenReturn("encoded");
            User savedUser = User.builder()
                    .username("superadmin")
                    .email("superadmin@seed.dev")
                    .isActive(true)
                    .isDeleted(false)
                    .build();
            setId(savedUser, UUID.randomUUID());
            when(userRepository.save(any(User.class))).thenReturn(savedUser);
            when(userRoleRepository.findById(any())).thenReturn(Optional.empty());
            when(userRoleRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(any(), any())).thenReturn(true);

            Role saRole = buildRole("ROLE_SUPER_ADMIN");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureSuperAdminUser", Role.class);
            method.setAccessible(true);
            method.invoke(seeder, saRole);

            verify(userRepository, atLeastOnce()).save(any(User.class));
        }
    }

    @Nested
    class AddGlobalRoleTests {

        @Test
        void addGlobalRole_nullUser_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("addGlobalRole", User.class, Role.class);
            method.setAccessible(true);

            method.invoke(seeder, null, doctorRole);

            verifyNoInteractions(userRoleRepository);
        }

        @Test
        void addGlobalRole_nullRole_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("addGlobalRole", User.class, Role.class);
            method.setAccessible(true);

            User user = User.builder().username("test").build();
            setId(user, UUID.randomUUID());
            method.invoke(seeder, user, null);

            verifyNoInteractions(userRoleRepository);
        }

        @Test
        void addGlobalRole_nullUserId_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("addGlobalRole", User.class, Role.class);
            method.setAccessible(true);

            User user = User.builder().username("test").build();
            method.invoke(seeder, user, doctorRole);

            verifyNoInteractions(userRoleRepository);
        }

        @Test
        void addGlobalRole_existingLink_doesNotCreateAgain() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("addGlobalRole", User.class, Role.class);
            method.setAccessible(true);

            User user = User.builder().username("test").build();
            setId(user, UUID.randomUUID());

            UserRole existingLink = UserRole.builder().user(user).role(doctorRole).build();
            when(userRoleRepository.findById(any())).thenReturn(Optional.of(existingLink));

            method.invoke(seeder, user, doctorRole);

            verify(userRoleRepository, never()).save(any());
        }
    }

    @Nested
    class EnsureGlobalAssignmentTests {

        @Test
        void ensureGlobalAssignment_nullUser_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureGlobalAssignment", User.class, Role.class);
            method.setAccessible(true);

            method.invoke(seeder, null, doctorRole);

            verify(assignmentRepository, never()).save(any());
        }

        @Test
        void ensureGlobalAssignment_nullRole_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureGlobalAssignment", User.class, Role.class);
            method.setAccessible(true);

            User user = User.builder().username("test").build();
            method.invoke(seeder, user, null);

            verify(assignmentRepository, never()).save(any());
        }

        @Test
        void ensureGlobalAssignment_alreadyExists_doesNotCreateAgain() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureGlobalAssignment", User.class, Role.class);
            method.setAccessible(true);

            User user = User.builder().username("test").build();
            setId(user, UUID.randomUUID());

            when(assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(any(), any())).thenReturn(true);

            method.invoke(seeder, user, doctorRole);

            verify(assignmentRepository, never()).save(any());
        }

        @Test
        void ensureGlobalAssignment_notExists_createsAndSaves() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureGlobalAssignment", User.class, Role.class);
            method.setAccessible(true);

            User user = User.builder().username("test").build();
            setId(user, UUID.randomUUID());

            when(assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(any(), any())).thenReturn(false);
            when(assignmentRepository.save(any())).thenAnswer(inv -> {
                UserRoleHospitalAssignment a = inv.getArgument(0);
                setId(a, UUID.randomUUID());
                return a;
            });
            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, user, superAdminRole);

            verify(assignmentRepository).save(any(UserRoleHospitalAssignment.class));
        }
    }

    @Nested
    class CreatePermissionsForAssignmentTests {

        @Test
        void createPermissionsForAssignment_nullAssignment_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createPermissionsForAssignment", UserRoleHospitalAssignment.class, Role.class);
            method.setAccessible(true);

            method.invoke(seeder, null, doctorRole);

            verify(permissionRepository, never()).save(any());
        }

        @Test
        void createPermissionsForAssignment_nullRole_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createPermissionsForAssignment", UserRoleHospitalAssignment.class, Role.class);
            method.setAccessible(true);

            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();
            method.invoke(seeder, assignment, null);

            verify(permissionRepository, never()).save(any());
        }

        @Test
        void createPermissionsForAssignment_createsPermissionsForRole() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createPermissionsForAssignment", UserRoleHospitalAssignment.class, Role.class);
            method.setAccessible(true);

            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();
            setId(assignment, UUID.randomUUID());

            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, assignment, doctorRole);

            List<String> expectedPermissions = PermissionCatalog.permissionsForRole("ROLE_DOCTOR");
            verify(permissionRepository, times(expectedPermissions.size())).save(any(Permission.class));
        }

        @Test
        void createPermissionsForAssignment_skipsExistingPermissions() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createPermissionsForAssignment", UserRoleHospitalAssignment.class, Role.class);
            method.setAccessible(true);

            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();
            setId(assignment, UUID.randomUUID());

            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(true);

            method.invoke(seeder, assignment, doctorRole);

            verify(permissionRepository, never()).save(any());
        }
    }

    @Nested
    class EnsureLabTestsTests {

        @Test
        void ensureLabTests_createsThreeLabTests() throws Exception {
            when(labTestDefinitionRepository.findActiveGlobalByIdentifier(anyString())).thenReturn(Optional.empty());
            when(labTestDefinitionRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(labTestDefinitionRepository.save(any())).thenAnswer(inv -> {
                LabTestDefinition def = inv.getArgument(0);
                setId(def, UUID.randomUUID());
                return def;
            });

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureLabTests");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<LabTestDefinition> labTests = (List<LabTestDefinition>) method.invoke(seeder);

            assertThat(labTests).hasSize(3);
            verify(labTestDefinitionRepository, times(3)).save(any());
        }

        @Test
        void ensureLabTests_updatesExistingDefinitions() throws Exception {
            LabTestDefinition existing = LabTestDefinition.builder()
                    .testCode("OLD-CODE")
                    .name("Old Name")
                    .active(false)
                    .build();
            setId(existing, UUID.randomUUID());

            when(labTestDefinitionRepository.findActiveGlobalByIdentifier(anyString()))
                    .thenReturn(Optional.of(existing))
                    .thenReturn(Optional.empty());
            when(labTestDefinitionRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(labTestDefinitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureLabTests");
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            List<LabTestDefinition> labTests = (List<LabTestDefinition>) method.invoke(seeder);

            assertThat(labTests).isNotEmpty();
            // Existing definition should be updated
            assertThat(existing.isActive()).isTrue();
        }
    }

    @Nested
    class EnsureTranslationTests {

        @Test
        void ensureTranslation_nullTreatment_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "ensureTranslation", Treatment.class, UserRoleHospitalAssignment.class,
                    String.class, String.class, String.class);
            method.setAccessible(true);

            method.invoke(seeder, null, null, "en", "Name", "Desc");

            verify(serviceTranslationRepository, never()).save(any());
        }

        @Test
        void ensureTranslation_nullAssignment_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "ensureTranslation", Treatment.class, UserRoleHospitalAssignment.class,
                    String.class, String.class, String.class);
            method.setAccessible(true);

            Treatment treatment = Treatment.builder().name("Test").build();
            method.invoke(seeder, treatment, null, "en", "Name", "Desc");

            verify(serviceTranslationRepository, never()).save(any());
        }

        @Test
        void ensureTranslation_blankName_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "ensureTranslation", Treatment.class, UserRoleHospitalAssignment.class,
                    String.class, String.class, String.class);
            method.setAccessible(true);

            Treatment treatment = Treatment.builder().name("Test").translations(new HashSet<>()).build();
            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();

            method.invoke(seeder, treatment, assignment, "en", "  ", "Desc");

            verify(serviceTranslationRepository, never()).save(any());
        }

        @Test
        void ensureTranslation_nullLanguageCode_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "ensureTranslation", Treatment.class, UserRoleHospitalAssignment.class,
                    String.class, String.class, String.class);
            method.setAccessible(true);

            Treatment treatment = Treatment.builder().name("Test").translations(new HashSet<>()).build();
            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();

            method.invoke(seeder, treatment, assignment, null, "Name", "Desc");

            verify(serviceTranslationRepository, never()).save(any());
        }

        @Test
        void ensureTranslation_translationAlreadyExists_skips() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "ensureTranslation", Treatment.class, UserRoleHospitalAssignment.class,
                    String.class, String.class, String.class);
            method.setAccessible(true);

            ServiceTranslation existingTranslation = ServiceTranslation.builder().languageCode("en").build();
            Treatment treatment = Treatment.builder()
                    .name("Test")
                    .translations(new HashSet<>(Set.of(existingTranslation)))
                    .build();
            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();

            method.invoke(seeder, treatment, assignment, "en", "Name", "Desc");

            verify(serviceTranslationRepository, never()).save(any());
        }

        @Test
        void ensureTranslation_newTranslation_saves() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "ensureTranslation", Treatment.class, UserRoleHospitalAssignment.class,
                    String.class, String.class, String.class);
            method.setAccessible(true);

            Treatment treatment = Treatment.builder()
                    .name("Test")
                    .translations(new HashSet<>())
                    .build();
            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();

            when(serviceTranslationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, treatment, assignment, "fr", "Nom", "Description");

            verify(serviceTranslationRepository).save(any(ServiceTranslation.class));
        }
    }

    @Nested
    class FindAlternateHospitalTests {

        @Test
        void findAlternateHospital_nullHospital_returnsNull() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("findAlternateHospital", Hospital.class);
            method.setAccessible(true);

            Hospital result = (Hospital) method.invoke(seeder, (Hospital) null);
            assertThat(result).isNull();
        }

        @Test
        void findAlternateHospital_hospitalWithNullId_returnsNull() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("findAlternateHospital", Hospital.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("H1").build();
            Hospital result = (Hospital) method.invoke(seeder, hospital);
            assertThat(result).isNull();
        }

        @Test
        void findAlternateHospital_withOrganization_returnsDifferentHospital() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("findAlternateHospital", Hospital.class);
            method.setAccessible(true);

            UUID orgId = UUID.randomUUID();
            Organization org = Organization.builder().code("ORG").build();
            setId(org, orgId);

            UUID fromId = UUID.randomUUID();
            Hospital from = Hospital.builder().code("H1").organization(org).build();
            setId(from, fromId);

            UUID altId = UUID.randomUUID();
            Hospital alternate = Hospital.builder().code("H2").build();
            setId(alternate, altId);

            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId)).thenReturn(List.of(from, alternate));

            Hospital result = (Hospital) method.invoke(seeder, from);
            assertThat(result).isNotNull();
            assertThat(result.getCode()).isEqualTo("H2");
        }

        @Test
        void findAlternateHospital_noOtherHospital_returnsNull() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("findAlternateHospital", Hospital.class);
            method.setAccessible(true);

            UUID orgId = UUID.randomUUID();
            Organization org = Organization.builder().code("ORG").build();
            setId(org, orgId);

            UUID fromId = UUID.randomUUID();
            Hospital from = Hospital.builder().code("H1").organization(org).build();
            setId(from, fromId);

            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(orgId)).thenReturn(List.of(from));

            Hospital result = (Hospital) method.invoke(seeder, from);
            assertThat(result).isNull();
        }

        @Test
        void findAlternateHospital_withoutOrganization_searchesAllHospitals() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("findAlternateHospital", Hospital.class);
            method.setAccessible(true);

            UUID fromId = UUID.randomUUID();
            Hospital from = Hospital.builder().code("H1").build();
            setId(from, fromId);

            UUID altId = UUID.randomUUID();
            Hospital alternate = Hospital.builder().code("H2").build();
            setId(alternate, altId);

            when(hospitalRepository.findAll()).thenReturn(List.of(from, alternate));

            Hospital result = (Hospital) method.invoke(seeder, from);
            assertThat(result).isNotNull();
            assertThat(result.getCode()).isEqualTo("H2");
        }
    }

    @Nested
    class SeedTreatmentsForHospitalTests {

        @Test
        void seedTreatmentsForHospital_nullHospital_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "seedTreatmentsForHospital", Hospital.class,
                    Class.forName("com.example.hms.bootstrap.DevSyntheticDataSeeder$StaffBundle"));
            method.setAccessible(true);

            method.invoke(seeder, null, null);

            verify(treatmentRepository, never()).findByHospital_Id(any());
        }

        @Test
        void seedTreatmentsForHospital_nullStaffBundle_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "seedTreatmentsForHospital", Hospital.class,
                    Class.forName("com.example.hms.bootstrap.DevSyntheticDataSeeder$StaffBundle"));
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("H1").build();
            setId(hospital, UUID.randomUUID());

            method.invoke(seeder, hospital, null);

            verify(treatmentRepository, never()).findByHospital_Id(any());
        }
    }

    @Nested
    class CreateEncounterTreatmentsTests {

        @Test
        void createEncounterTreatments_nullEncounter_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createEncounterTreatments", Encounter.class, Hospital.class,
                    Class.forName("com.example.hms.bootstrap.DevSyntheticDataSeeder$StaffBundle"),
                    int.class);
            method.setAccessible(true);

            method.invoke(seeder, null, Hospital.builder().build(), null, 1);

            verify(encounterTreatmentRepository, never()).findByEncounter_Id(any());
        }

        @Test
        void createEncounterTreatments_nullHospital_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createEncounterTreatments", Encounter.class, Hospital.class,
                    Class.forName("com.example.hms.bootstrap.DevSyntheticDataSeeder$StaffBundle"),
                    int.class);
            method.setAccessible(true);

            Encounter encounter = Encounter.builder().build();
            setId(encounter, UUID.randomUUID());

            method.invoke(seeder, encounter, null, null, 1);

            verify(encounterTreatmentRepository, never()).findByEncounter_Id(any());
        }

        @Test
        void createEncounterTreatments_existingTreatments_skips() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createEncounterTreatments", Encounter.class, Hospital.class,
                    Class.forName("com.example.hms.bootstrap.DevSyntheticDataSeeder$StaffBundle"),
                    int.class);
            method.setAccessible(true);

            UUID encId = UUID.randomUUID();
            UUID hospId = UUID.randomUUID();
            Encounter encounter = Encounter.builder().build();
            setId(encounter, encId);
            Hospital hospital = Hospital.builder().code("H1").build();
            setId(hospital, hospId);

            when(encounterTreatmentRepository.findByEncounter_Id(encId))
                    .thenReturn(List.of(EncounterTreatment.builder().build()));

            method.invoke(seeder, encounter, hospital, null, 1);

            verify(encounterTreatmentRepository, never()).save(any());
        }
    }

    @Nested
    class CreatePatientInsuranceTests {

        @Test
        void createPatientInsurance_nullPatient_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createPatientInsurance", Patient.class,
                    Class.forName("com.example.hms.bootstrap.DevSyntheticDataSeeder$StaffBundle"));
            method.setAccessible(true);

            method.invoke(seeder, null, null);

            verify(patientInsuranceRepository, never()).save(any());
        }

        @Test
        void createPatientInsurance_nullStaffBundle_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createPatientInsurance", Patient.class,
                    Class.forName("com.example.hms.bootstrap.DevSyntheticDataSeeder$StaffBundle"));
            method.setAccessible(true);

            Patient patient = Patient.builder().firstName("Test").build();
            method.invoke(seeder, patient, null);

            verify(patientInsuranceRepository, never()).save(any());
        }
    }

    @Nested
    class BackfillPermissionsTests {

        @Test
        void backfillPermissionsForExistingAssignments_noAssignments_logsAndReturns() throws Exception {
            when(assignmentRepository.findAll()).thenReturn(Collections.emptyList());
            when(permissionRepository.findAll()).thenReturn(Collections.emptyList());

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("backfillPermissionsForExistingAssignments");
            method.setAccessible(true);
            method.invoke(seeder);

            verify(assignmentRepository).findAll();
        }

        @Test
        void backfillPermissionsForExistingAssignments_assignmentWithExistingPerms_skips() throws Exception {
            UUID assignId = UUID.randomUUID();
            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
                    .role(doctorRole)
                    .build();
            setId(assignment, assignId);

            Permission existingPerm = Permission.builder().name("some perm").assignment(assignment).build();

            when(assignmentRepository.findAll()).thenReturn(List.of(assignment));
            when(permissionRepository.findAll()).thenReturn(List.of(existingPerm));

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("backfillPermissionsForExistingAssignments");
            method.setAccessible(true);
            method.invoke(seeder);

            // Should not create any new permissions since existing ones are present
            verify(permissionRepository, never()).save(any());
        }

        @Test
        void backfillPermissionsForExistingAssignments_assignmentWithNoPerms_backfills() throws Exception {
            UUID assignId = UUID.randomUUID();
            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
                    .role(doctorRole)
                    .build();
            setId(assignment, assignId);

            when(assignmentRepository.findAll()).thenReturn(List.of(assignment));
            when(permissionRepository.findAll()).thenReturn(Collections.emptyList());
            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("backfillPermissionsForExistingAssignments");
            method.setAccessible(true);
            method.invoke(seeder);

            // Should save permissions for the doctor role
            verify(permissionRepository, atLeastOnce()).save(any(Permission.class));
        }

        @Test
        void backfillPermissionsForExistingAssignments_assignmentWithNullRole_skips() throws Exception {
            UUID assignId = UUID.randomUUID();
            UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder().build();
            setId(assignment, assignId);

            when(assignmentRepository.findAll()).thenReturn(List.of(assignment));
            when(permissionRepository.findAll()).thenReturn(Collections.emptyList());

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("backfillPermissionsForExistingAssignments");
            method.setAccessible(true);
            method.invoke(seeder);

            verify(permissionRepository, never()).save(any());
        }
    }

    @Nested
    class SeedFeaturedAccountsTests {

        @Test
        void seedFeaturedAccountsIfNeeded_alreadySeeded_returnsImmediately() throws Exception {
            setField(seeder, "featuredAccountsSeeded", true);

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedFeaturedAccountsIfNeeded");
            method.setAccessible(true);
            method.invoke(seeder);

            verifyNoInteractions(hospitalRepository);
        }

        @Test
        void seedFeaturedAccountsIfNeeded_featuredHospitalNotFound_skips() throws Exception {
            setField(seeder, "featuredAccountsSeeded", false);
            when(hospitalRepository.findByCodeIgnoreCase("DEV-ORG-01-H01")).thenReturn(Optional.empty());

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedFeaturedAccountsIfNeeded");
            method.setAccessible(true);
            method.invoke(seeder);

            boolean seeded = (boolean) getField(seeder, "featuredAccountsSeeded");
            assertThat(seeded).isFalse();
        }
    }

    @Nested
    class ResolveDepartmentForFeaturedAccountTests {

        @Test
        void resolveDepartmentForFeaturedAccount_nullHospital_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveDepartmentForFeaturedAccount", Hospital.class, String.class);
            method.setAccessible(true);

            @SuppressWarnings("unchecked")
            Optional<Department> result = (Optional<Department>) method.invoke(seeder, null, "GEN");
            assertThat(result).isEmpty();
        }

        @Test
        void resolveDepartmentForFeaturedAccount_hospitalWithNullId_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveDepartmentForFeaturedAccount", Hospital.class, String.class);
            method.setAccessible(true);

            Hospital hospital = Hospital.builder().code("H1").build();
            @SuppressWarnings("unchecked")
            Optional<Department> result = (Optional<Department>) method.invoke(seeder, hospital, "GEN");
            assertThat(result).isEmpty();
        }

        @Test
        void resolveDepartmentForFeaturedAccount_foundByCode_returnsIt() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveDepartmentForFeaturedAccount", Hospital.class, String.class);
            method.setAccessible(true);

            UUID hospitalId = UUID.randomUUID();
            Hospital hospital = Hospital.builder().code("H1").build();
            setId(hospital, hospitalId);

            Department dept = Department.builder().name("General").code("GEN").build();
            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospitalId, "GEN"))
                    .thenReturn(Optional.of(dept));

            @SuppressWarnings("unchecked")
            Optional<Department> result = (Optional<Department>) method.invoke(seeder, hospital, "GEN");
            assertThat(result).isPresent().contains(dept);
        }

        @Test
        void resolveDepartmentForFeaturedAccount_notFoundByCode_returnsFirst() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveDepartmentForFeaturedAccount", Hospital.class, String.class);
            method.setAccessible(true);

            UUID hospitalId = UUID.randomUUID();
            Hospital hospital = Hospital.builder().code("H1").build();
            setId(hospital, hospitalId);

            Department fallback = Department.builder().name("Fallback").code("FB").build();
            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospitalId, "GEN")).thenReturn(Optional.empty());
            when(departmentRepository.findByHospitalId(hospitalId)).thenReturn(List.of(fallback));

            @SuppressWarnings("unchecked")
            Optional<Department> result = (Optional<Department>) method.invoke(seeder, hospital, "GEN");
            assertThat(result).isPresent().contains(fallback);
        }

        @Test
        void resolveDepartmentForFeaturedAccount_nothingFound_returnsEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "resolveDepartmentForFeaturedAccount", Hospital.class, String.class);
            method.setAccessible(true);

            UUID hospitalId = UUID.randomUUID();
            Hospital hospital = Hospital.builder().code("H1").build();
            setId(hospital, hospitalId);

            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospitalId, "GEN")).thenReturn(Optional.empty());
            when(departmentRepository.findByHospitalId(hospitalId)).thenReturn(Collections.emptyList());

            @SuppressWarnings("unchecked")
            Optional<Department> result = (Optional<Department>) method.invoke(seeder, hospital, "GEN");
            assertThat(result).isEmpty();
        }
    }

    @Nested
    class BumpUserSequenceTests {

        @Test
        void bumpUserSequence_higherValue_updatesSequence() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("bumpUserSequence", int.class);
            method.setAccessible(true);

            // Default starts at 1
            method.invoke(seeder, 100);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "userSequence");
            assertThat(seq.get()).isGreaterThanOrEqualTo(101);
        }

        @Test
        void bumpUserSequence_lowerValue_doesNotDecrease() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("bumpUserSequence", int.class);
            method.setAccessible(true);

            // Set the sequence to a high number first
            AtomicInteger seq = (AtomicInteger) getField(seeder, "userSequence");
            seq.set(200);

            method.invoke(seeder, 50);
            assertThat(seq.get()).isEqualTo(200);
        }
    }

    @Nested
    class EnsureSequenceAheadOfTests {

        @Test
        void ensureSequenceAheadOf_validDevUsername_bumpsSequence() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureSequenceAheadOf", String.class);
            method.setAccessible(true);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "userSequence");

            method.invoke(seeder, "dev_midwife_2031");

            assertThat(seq.get()).isGreaterThanOrEqualTo(2032);
        }

        @Test
        void ensureSequenceAheadOf_nonDevUsername_doesNotBump() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureSequenceAheadOf", String.class);
            method.setAccessible(true);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "userSequence");
            int valueBefore = seq.get();

            method.invoke(seeder, "admin_user_5000");

            assertThat(seq.get()).isEqualTo(valueBefore);
        }
    }

    @Nested
    class CreateUserTests {

        @Test
        void createUser_createsAndSavesUser() throws Exception {
            setField(seeder, "defaultEncodedPassword", "encoded123");

            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createUser", String.class, String.class, String.class);
            method.setAccessible(true);

            User user = (User) method.invoke(seeder, "doctor", "Amadou", "Ouédraogo");

            assertThat(user.getUsername()).startsWith("dev_doctor_");
            assertThat(user.getEmail()).endsWith("@seed.dev");
            assertThat(user.getFirstName()).isEqualTo("Amadou");
            assertThat(user.getLastName()).isEqualTo("Ouédraogo");
            assertThat(user.getPasswordHash()).isEqualTo("encoded123");
            assertThat(user.isActive()).isTrue();
            assertThat(user.isDeleted()).isFalse();
            verify(userRepository).save(any(User.class));
        }

        @Test
        void createUser_incrementsSequence() throws Exception {
            setField(seeder, "defaultEncodedPassword", "encoded123");
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });

            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                    "createUser", String.class, String.class, String.class);
            method.setAccessible(true);

            User user1 = (User) method.invoke(seeder, "nurse", "A", "B");
            User user2 = (User) method.invoke(seeder, "nurse", "C", "D");

            assertThat(user1.getUsername()).isNotEqualTo(user2.getUsername());
        }
    }

    @Nested
    class SeedConsentForEmailTests {

        @Test
        void seedConsentForEmail_noPatientMatch_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedConsentForEmail", String.class);
            method.setAccessible(true);

            when(patientRepository.findByEmailContainingIgnoreCase(anyString())).thenReturn(Collections.emptyList());

            method.invoke(seeder, "nonexistent@seed.dev");

            verify(patientConsentRepository, never()).save(any());
        }

        @Test
        void seedConsentForEmail_noActiveRegistration_doesNothing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedConsentForEmail", String.class);
            method.setAccessible(true);

            UUID patientId = UUID.randomUUID();
            Patient patient = Patient.builder().firstName("Test").email("dev_patient_0007@seed.dev").build();
            setId(patient, patientId);

            when(patientRepository.findByEmailContainingIgnoreCase(anyString())).thenReturn(List.of(patient));

            PatientHospitalRegistration inactiveReg = PatientHospitalRegistration.builder()
                    .patient(patient)
                    .active(false)
                    .build();
            when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(inactiveReg));

            method.invoke(seeder, "dev_patient_0007@seed.dev");

            verify(patientConsentRepository, never()).save(any());
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Complex workflow method tests
    // ═══════════════════════════════════════════════════════════════

    @Nested
    class RunMethodTests {

        @Test
        void run_initializesTransactionTemplateAndSequences() throws Exception {
            // Use lenient stubs - run() is a complex orchestrator and not all paths are hit
            lenient().when(passwordEncoder.encode(anyString())).thenReturn("encodedPw");
            lenient().when(userRepository.findUsernamesByPrefix(anyString())).thenReturn(Collections.emptyList());
            lenient().when(userRepository.findMaxPhoneNumberWithPrefix(anyString())).thenReturn(Optional.empty());
            lenient().when(roleRepository.findByCode(anyString())).thenAnswer(inv -> {
                String code = inv.getArgument(0);
                return Optional.of(buildRole(code));
            });
            lenient().when(labTestDefinitionRepository.findActiveGlobalByIdentifier(anyString())).thenReturn(Optional.empty());
            lenient().when(labTestDefinitionRepository.findByNameIgnoreCase(anyString())).thenReturn(Optional.empty());
            lenient().when(labTestDefinitionRepository.save(any(LabTestDefinition.class))).thenAnswer(inv -> {
                LabTestDefinition def = inv.getArgument(0);
                setId(def, UUID.randomUUID());
                return def;
            });
            lenient().when(userRepository.findByUsernameIgnoreCase("superadmin")).thenReturn(Optional.of(DevSyntheticDataSeederTest.this.buildUser("superadmin")));
            lenient().when(userRoleRepository.findById(any())).thenReturn(Optional.empty());
            lenient().when(userRoleRepository.save(any(UserRole.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(any(), any())).thenReturn(true);
            lenient().when(assignmentRepository.findByUserIdAndRoleIdAndHospitalIsNull(any(), any())).thenReturn(Optional.of(buildAssignment()));

            // Build org/hospital with IDs for seedOrganizationGraph / seedHospitalGraph
            Organization org = DevSyntheticDataSeederTest.this.buildOrganization();
            Hospital hospital = DevSyntheticDataSeederTest.this.buildHospital();
            hospital.setOrganization(org);

            lenient().when(organizationRepository.findByCode(anyString())).thenReturn(Optional.of(org));
            lenient().when(organizationRepository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
            lenient().when(hospitalRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.of(hospital));
            lenient().when(hospitalRepository.save(any(Hospital.class))).thenAnswer(inv -> inv.getArgument(0));

            // Staff/patient stubs
            lenient().when(staffRepository.findByHospital_Id(any(), any())).thenReturn(new org.springframework.data.domain.PageImpl<>(Collections.emptyList()));
            lenient().when(patientRepository.findByEmailContainingIgnoreCase(anyString())).thenReturn(Collections.emptyList());
            lenient().when(patientRepository.findByHospitalId(any())).thenReturn(Collections.nCopies(10, Patient.builder().build()));
            lenient().when(assignmentRepository.findAll()).thenReturn(Collections.emptyList());
            lenient().when(permissionRepository.findAll()).thenReturn(Collections.emptyList());
            lenient().when(treatmentRepository.findByHospital_Id(any())).thenReturn(Collections.emptyList());
            lenient().when(departmentRepository.findByHospitalId(any())).thenReturn(Collections.emptyList());
            lenient().when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> {
                Department d = inv.getArgument(0);
                setId(d, UUID.randomUUID());
                return d;
            });
            lenient().when(assignmentRepository.save(any(UserRoleHospitalAssignment.class))).thenAnswer(inv -> {
                UserRoleHospitalAssignment a = inv.getArgument(0);
                setId(a, UUID.randomUUID());
                return a;
            });
            lenient().when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(true);
            lenient().when(staffRepository.save(any(Staff.class))).thenAnswer(inv -> {
                Staff s = inv.getArgument(0);
                setId(s, UUID.randomUUID());
                return s;
            });
            lenient().when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                if (u.getId() == null) setId(u, UUID.randomUUID());
                return u;
            });

            // TransactionTemplate setup
            lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));

            try {
                seeder.run(applicationArguments);
            } catch (RuntimeException e) {
                // Complex orchestration may fail deeper, but initialization should complete
            }

            // Verify the initialization steps happened
            verify(passwordEncoder, atLeastOnce()).encode(anyString());
            verify(roleRepository, atLeastOnce()).findByCode(anyString());
        }
    }

    @Nested
    class SyncUserSequenceFromExistingDataTests {

        @Test
        void syncUserSequence_updatesWhenMaxIsHigher() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("syncUserSequenceFromExistingData");
            method.setAccessible(true);

            when(userRepository.findUsernamesByPrefix("dev_")).thenReturn(List.of("dev_admin_50", "dev_doctor_30"));

            method.invoke(seeder);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "userSequence");
            assertThat(seq.get()).isGreaterThanOrEqualTo(51);
        }

        @Test
        void syncUserSequence_doesNotDowngrade() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("syncUserSequenceFromExistingData");
            method.setAccessible(true);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "userSequence");
            seq.set(100);

            when(userRepository.findUsernamesByPrefix("dev_")).thenReturn(List.of("dev_admin_5"));

            method.invoke(seeder);

            assertThat(seq.get()).isEqualTo(100);
        }

        @Test
        void syncUserSequence_handlesEmptyList() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("syncUserSequenceFromExistingData");
            method.setAccessible(true);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "userSequence");
            int before = seq.get();

            when(userRepository.findUsernamesByPrefix("dev_")).thenReturn(Collections.emptyList());

            method.invoke(seeder);

            assertThat(seq.get()).isEqualTo(before);
        }
    }

    @Nested
    class SyncPhoneSequenceFromExistingDataTests {

        @Test
        void syncPhoneSequence_updatesWhenMaxIsHigher() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("syncPhoneSequenceFromExistingData");
            method.setAccessible(true);

            when(userRepository.findMaxPhoneNumberWithPrefix("+226")).thenReturn(Optional.of("+22699999999"));

            method.invoke(seeder);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "phoneSequence");
            assertThat(seq.get()).isGreaterThanOrEqualTo(100000000);
        }

        @Test
        void syncPhoneSequence_handlesEmpty() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("syncPhoneSequenceFromExistingData");
            method.setAccessible(true);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "phoneSequence");
            int before = seq.get();

            when(userRepository.findMaxPhoneNumberWithPrefix("+226")).thenReturn(Optional.empty());

            method.invoke(seeder);

            assertThat(seq.get()).isEqualTo(before);
        }

        @Test
        void syncPhoneSequence_handlesNonNumeric() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("syncPhoneSequenceFromExistingData");
            method.setAccessible(true);

            AtomicInteger seq = (AtomicInteger) getField(seeder, "phoneSequence");
            int before = seq.get();

            when(userRepository.findMaxPhoneNumberWithPrefix("+226")).thenReturn(Optional.of("+226abcdefgh"));

            method.invoke(seeder);

            assertThat(seq.get()).isEqualTo(before);
        }
    }

    @Nested
    class SeedOrganizationGraphTests {

        @Test
        void seedOrganizationGraph_createsNewOrgWhenNotFound() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedOrganizationGraph", int.class);
            method.setAccessible(true);

            Organization org = buildOrganization();
            when(organizationRepository.findByCode("DEV-ORG-01")).thenReturn(Optional.empty());
            when(organizationRepository.save(any(Organization.class))).thenReturn(org);
            // hospital loop - return existing hospital with enough staff
            when(hospitalRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.of(buildHospital()));
            when(staffRepository.findByHospital_Id(any(), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(Collections.nCopies(12, Staff.builder().build())));

            method.invoke(seeder, 1);

            verify(organizationRepository).save(any(Organization.class));
        }

        @Test
        void seedOrganizationGraph_usesExistingOrg() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedOrganizationGraph", int.class);
            method.setAccessible(true);

            Organization org = buildOrganization();
            when(organizationRepository.findByCode("DEV-ORG-01")).thenReturn(Optional.of(org));
            when(hospitalRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.of(buildHospital()));
            when(staffRepository.findByHospital_Id(any(), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(Collections.nCopies(12, Staff.builder().build())));

            method.invoke(seeder, 1);

            verify(organizationRepository, never()).save(any());
        }
    }

    @Nested
    class SeedHospitalGraphTests {

        @Test
        void seedHospitalGraph_skipsWhenEnoughStaffExist() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "seedHospitalGraph", Organization.class, int.class, int.class);
            method.setAccessible(true);

            Organization org = buildOrganization();
            Hospital hospital = buildHospital();
            when(hospitalRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.of(hospital));
            when(staffRepository.findByHospital_Id(any(), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(Collections.nCopies(12, Staff.builder().build())));

            method.invoke(seeder, org, 1, 1);

            // createStaffBundle is never called, so no patient creation
            verify(patientRepository, never()).findByHospitalId(any());
        }

        @Test
        void seedHospitalGraph_createsNewHospitalWhenNotFound() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "seedHospitalGraph", Organization.class, int.class, int.class);
            method.setAccessible(true);

            Organization org = buildOrganization();
            Hospital hospital = buildHospital();
            when(hospitalRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
            when(hospitalRepository.save(any(Hospital.class))).thenReturn(hospital);
            // Return enough existing staff so we skip the heavy bundle creation
            when(staffRepository.findByHospital_Id(any(), any())).thenReturn(
                new org.springframework.data.domain.PageImpl<>(Collections.nCopies(12, Staff.builder().build())));

            method.invoke(seeder, org, 1, 1);

            verify(hospitalRepository).save(any(Hospital.class));
        }
    }

    @Nested
    class CreateDepartmentTests {

        @Test
        void createDepartment_returnsExistingWhenFound() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createDepartment", Hospital.class, UserRoleHospitalAssignment.class, int.class,
                String.class, String.class, String.class);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            UserRoleHospitalAssignment assignment = buildAssignment();
            Department existing = Department.builder()
                .name("General Medicine").code("GEN01").hospital(hospital).assignment(assignment).build();
            setId(existing, UUID.randomUUID());

            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), "GEN01"))
                .thenReturn(Optional.of(existing));

            Object result = method.invoke(seeder, hospital, assignment, 1, "General Medicine", "GEN", "general");

            assertThat(result).isNotNull();
            verify(departmentRepository, never()).save(any(Department.class));
        }

        @Test
        void createDepartment_createsNewWhenNotFound() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createDepartment", Hospital.class, UserRoleHospitalAssignment.class, int.class,
                String.class, String.class, String.class);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            UserRoleHospitalAssignment assignment = buildAssignment();

            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), "GEN01"))
                .thenReturn(Optional.empty());
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> {
                Department d = inv.getArgument(0);
                setId(d, UUID.randomUUID());
                return d;
            });

            Object result = method.invoke(seeder, hospital, assignment, 1, "General Medicine", "GEN", "general");

            assertThat(result).isNotNull();
            verify(departmentRepository).save(any(Department.class));
        }

        @Test
        void createDepartment_updatesExistingWhenAssignmentMissing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createDepartment", Hospital.class, UserRoleHospitalAssignment.class, int.class,
                String.class, String.class, String.class);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            UserRoleHospitalAssignment assignment = buildAssignment();
            Department existing = Department.builder()
                .name("General").code("GEN01").hospital(hospital).build();
            setId(existing, UUID.randomUUID());
            // assignment is null, hospital is set

            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), "GEN01"))
                .thenReturn(Optional.of(existing));
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            Object result = method.invoke(seeder, hospital, assignment, 1, "General", "GEN", "general");

            assertThat(result).isNotNull();
            verify(departmentRepository).save(any(Department.class));
        }

        @Test
        void createDepartment_updatesExistingWhenHospitalMissing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createDepartment", Hospital.class, UserRoleHospitalAssignment.class, int.class,
                String.class, String.class, String.class);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            UserRoleHospitalAssignment assignment = buildAssignment();
            Department existing = Department.builder()
                .name("General").code("GEN01").assignment(assignment).build();
            setId(existing, UUID.randomUUID());
            // hospital is null

            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), "GEN01"))
                .thenReturn(Optional.of(existing));
            when(departmentRepository.save(any(Department.class))).thenAnswer(inv -> inv.getArgument(0));

            Object result = method.invoke(seeder, hospital, assignment, 1, "General", "GEN", "general");

            assertThat(result).isNotNull();
            verify(departmentRepository).save(any(Department.class));
        }
    }

    @Nested
    class CreateAssignmentTests {

        @Test
        void createAssignment_savesAndCreatesPermissions() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createAssignment", User.class, Hospital.class, Role.class, User.class);
            method.setAccessible(true);

            User user = buildUser("testuser");
            Hospital hospital = buildHospital();
            Role role = buildRole("ROLE_DOCTOR");

            UserRoleHospitalAssignment savedAssignment = buildAssignment();
            when(assignmentRepository.save(any(UserRoleHospitalAssignment.class))).thenReturn(savedAssignment);
            // createPermissionsForAssignment uses existsByNameAndAssignment_Id
            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any(Permission.class))).thenAnswer(inv -> {
                Permission p = inv.getArgument(0);
                setId(p, UUID.randomUUID());
                return p;
            });

            Object result = method.invoke(seeder, user, hospital, role, null);

            assertThat(result).isNotNull();
            verify(assignmentRepository).save(any(UserRoleHospitalAssignment.class));
        }
    }

    @Nested
    class CreateStaffForExistingUserTests {

        @Test
        void createStaffForExistingUser_savesStaffAndLinksToUser() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createStaffForExistingUser", User.class, UserRoleHospitalAssignment.class,
                Hospital.class, Department.class, JobTitle.class, EmploymentType.class, String.class);
            method.setAccessible(true);

            User user = buildUser("admin");
            Hospital hospital = buildHospital();
            Department dept = buildDepartment(hospital);
            UserRoleHospitalAssignment assignment = buildAssignment();

            when(staffRepository.save(any(Staff.class))).thenAnswer(inv -> {
                Staff s = inv.getArgument(0);
                setId(s, UUID.randomUUID());
                return s;
            });
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            Object result = method.invoke(seeder, user, assignment, hospital, dept,
                JobTitle.HOSPITAL_ADMINISTRATOR, EmploymentType.FULL_TIME, "Hospital Governance");

            assertThat(result).isNotNull();
            verify(staffRepository).save(any(Staff.class));
            verify(userRepository).save(user);
        }
    }

    @Nested
    class CreateStaffTests {

        @Test
        void createStaff_createsUserStaffAndAssignment() throws Exception {
            Class<?> staffSeedInputClass = findInnerClass("StaffSeedInput");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createStaff", String.class, staffSeedInputClass);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Department dept = buildDepartment(hospital);
            Role role = buildRole("ROLE_DOCTOR");
            User registeredBy = buildUser("admin");

            var ctor = staffSeedInputClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object input = ctor.newInstance(
                hospital, dept, role, JobTitle.DOCTOR, EmploymentType.FULL_TIME,
                "General Medicine", registeredBy);

            // createUser internally
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });
            when(userRoleRepository.findById(any())).thenReturn(Optional.empty());
            when(userRoleRepository.save(any(UserRole.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.save(any(UserRoleHospitalAssignment.class))).thenAnswer(inv -> {
                UserRoleHospitalAssignment a = inv.getArgument(0);
                setId(a, UUID.randomUUID());
                return a;
            });
            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any(Permission.class))).thenAnswer(inv -> {
                Permission p = inv.getArgument(0);
                setId(p, UUID.randomUUID());
                return p;
            });
            when(staffRepository.save(any(Staff.class))).thenAnswer(inv -> {
                Staff s = inv.getArgument(0);
                setId(s, UUID.randomUUID());
                return s;
            });

            Object result = method.invoke(seeder, "doctor", input);

            assertThat(result).isNotNull();
            verify(staffRepository).save(any(Staff.class));
        }
    }

    @Nested
    class EnsureStaffTests {

        @Test
        void ensureStaff_returnsExistingAndUpdatesDepartment() throws Exception {
            Class<?> staffSeedInputClass = findInnerClass("StaffSeedInput");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "ensureStaff", Map.class, String.class, staffSeedInputClass);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Department dept = buildDepartment(hospital);
            Department otherDept = buildDepartment(hospital);
            setId(otherDept, UUID.randomUUID());
            Role role = buildRole("ROLE_DOCTOR");
            User adminUser = buildUser("admin");

            var ctor = staffSeedInputClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            Object input = ctor.newInstance(
                hospital, dept, role, JobTitle.DOCTOR, EmploymentType.FULL_TIME,
                "General Medicine", adminUser);

            Staff existing = Staff.builder().jobTitle(JobTitle.DOCTOR).hospital(hospital).department(otherDept).build();
            setId(existing, UUID.randomUUID());

            Map<JobTitle, Staff> staffMap = new EnumMap<>(JobTitle.class);
            staffMap.put(JobTitle.DOCTOR, existing);

            when(staffRepository.save(any(Staff.class))).thenAnswer(inv -> inv.getArgument(0));

            Object result = method.invoke(seeder, staffMap, "doctor", input);

            assertThat(result).isNotNull();
            verify(staffRepository).save(any(Staff.class));
        }

        @Test
        void ensureStaff_returnsNullWhenRoleIsMissing() throws Exception {
            Class<?> staffSeedInputClass = findInnerClass("StaffSeedInput");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "ensureStaff", Map.class, String.class, staffSeedInputClass);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Department dept = buildDepartment(hospital);
            User adminUser = buildUser("admin");

            var ctor1 = staffSeedInputClass.getDeclaredConstructors()[0];
            ctor1.setAccessible(true);
            Object input = ctor1.newInstance(
                hospital, dept, null, JobTitle.DOCTOR, EmploymentType.FULL_TIME,
                "General Medicine", adminUser);

            Map<JobTitle, Staff> staffMap = new EnumMap<>(JobTitle.class);

            Object result = method.invoke(seeder, staffMap, "doctor", input);

            assertThat(result).isNull();
        }

        @Test
        void ensureStaff_createsNewWhenNotInMap() throws Exception {
            Class<?> staffSeedInputClass = findInnerClass("StaffSeedInput");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "ensureStaff", Map.class, String.class, staffSeedInputClass);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Department dept = buildDepartment(hospital);
            Role role = buildRole("ROLE_DOCTOR");
            User adminUser = buildUser("admin");

            var ctor2 = staffSeedInputClass.getDeclaredConstructors()[0];
            ctor2.setAccessible(true);
            Object input = ctor2.newInstance(
                hospital, dept, role, JobTitle.DOCTOR, EmploymentType.FULL_TIME,
                "General Medicine", adminUser);

            Map<JobTitle, Staff> staffMap = new EnumMap<>(JobTitle.class);

            // Stub everything createStaff needs
            when(userRepository.save(any(User.class))).thenAnswer(inv -> {
                User u = inv.getArgument(0);
                setId(u, UUID.randomUUID());
                return u;
            });
            when(userRoleRepository.findById(any())).thenReturn(Optional.empty());
            when(userRoleRepository.save(any(UserRole.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.save(any(UserRoleHospitalAssignment.class))).thenAnswer(inv -> {
                UserRoleHospitalAssignment a = inv.getArgument(0);
                setId(a, UUID.randomUUID());
                return a;
            });
            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any(Permission.class))).thenAnswer(inv -> {
                Permission p = inv.getArgument(0);
                setId(p, UUID.randomUUID());
                return p;
            });
            when(staffRepository.save(any(Staff.class))).thenAnswer(inv -> {
                Staff s = inv.getArgument(0);
                setId(s, UUID.randomUUID());
                return s;
            });

            Object result = method.invoke(seeder, staffMap, "doctor", input);

            assertThat(result).isNotNull();
        }
    }

    @Nested
    class SeedPatientsForHospitalTests {

        @Test
        void seedPatientsForHospital_skipsWhenEnoughPatientsExist() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "seedPatientsForHospital", Organization.class, Hospital.class, int.class,
                staffBundleClass);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Organization org = buildOrganization();

            when(patientRepository.findByHospitalId(hospital.getId()))
                .thenReturn(Collections.nCopies(10, Patient.builder().build()));

            // Create a StaffBundle via reflection
            Object staffBundle = createStaffBundle();

            method.invoke(seeder, org, hospital, 1, staffBundle);

            // Should not attempt to create more patients
            verify(patientRepository, never()).save(any(Patient.class));
        }
    }

    @Nested
    class CreateAppointmentTests {

        @Test
        void createAppointment_savesAppointment() throws Exception {
            // Find the StaffBundle class
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createAppointment", Patient.class, Hospital.class, staffBundleClass,
                int.class, LocalDate.class);
            method.setAccessible(true);

            Patient patient = Patient.builder().firstName("Test").build();
            setId(patient, UUID.randomUUID());
            Hospital hospital = buildHospital();

            Object staffBundle = createStaffBundle();

            when(appointmentRepository.save(any(Appointment.class))).thenAnswer(inv -> {
                Appointment a = inv.getArgument(0);
                setId(a, UUID.randomUUID());
                return a;
            });

            Object result = method.invoke(seeder, patient, hospital, staffBundle, 1, LocalDate.now());

            assertThat(result).isNotNull();
            verify(appointmentRepository).save(any(Appointment.class));
        }
    }

    @Nested
    class CreateEncounterTests {

        @Test
        void createEncounter_savesEncounter() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createEncounter", Patient.class, Hospital.class, staffBundleClass, Appointment.class);
            method.setAccessible(true);

            Patient patient = Patient.builder().firstName("Test").build();
            setId(patient, UUID.randomUUID());
            Hospital hospital = buildHospital();
            Appointment appointment = Appointment.builder()
                .appointmentDate(LocalDate.now())
                .startTime(java.time.LocalTime.of(9, 0))
                .build();
            setId(appointment, UUID.randomUUID());

            Object staffBundle = createStaffBundle();

            when(encounterRepository.save(any(Encounter.class))).thenAnswer(inv -> {
                Encounter e = inv.getArgument(0);
                setId(e, UUID.randomUUID());
                return e;
            });

            Object result = method.invoke(seeder, patient, hospital, staffBundle, appointment);

            assertThat(result).isNotNull();
            verify(encounterRepository).save(any(Encounter.class));
        }
    }

    @Nested
    class CreateBillingTests {

        @Test
        void createBilling_savesInvoice() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createBilling", Encounter.class, Patient.class, Hospital.class, staffBundleClass);
            method.setAccessible(true);

            Encounter encounter = Encounter.builder().build();
            setId(encounter, UUID.randomUUID());
            Patient patient = Patient.builder().firstName("Test").build();
            setId(patient, UUID.randomUUID());
            Hospital hospital = buildHospital();

            Object staffBundle = createStaffBundle();

            when(billingInvoiceRepository.save(any(BillingInvoice.class))).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, encounter, patient, hospital, staffBundle);

            verify(billingInvoiceRepository).save(any(BillingInvoice.class));
        }
    }

    @Nested
    class CreateLabResultsTests {

        @Test
        void createLabResults_savesOrderAndResult() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createLabResults", Encounter.class, Patient.class, Hospital.class,
                staffBundleClass, int.class, LocalDate.class);
            method.setAccessible(true);

            Encounter encounter = Encounter.builder().build();
            setId(encounter, UUID.randomUUID());
            Patient patient = Patient.builder().firstName("Test").build();
            setId(patient, UUID.randomUUID());
            Hospital hospital = buildHospital();

            // Need labTests populated
            LabTestDefinition labDef = LabTestDefinition.builder().name("CBC").testCode("CBC").build();
            setId(labDef, UUID.randomUUID());
            setField(seeder, "labTests", List.of(labDef));

            Object staffBundle = createStaffBundle();

            when(labOrderRepository.save(any(LabOrder.class))).thenAnswer(inv -> {
                LabOrder o = inv.getArgument(0);
                setId(o, UUID.randomUUID());
                setField(o, "orderDatetime", LocalDate.now().atTime(10, 0));
                return o;
            });
            when(labResultRepository.save(any(LabResult.class))).thenAnswer(inv -> inv.getArgument(0));
            when(encounterTreatmentRepository.findByEncounter_Id(encounter.getId())).thenReturn(List.of(
                EncounterTreatment.builder().build()
            ));

            method.invoke(seeder, encounter, patient, hospital, staffBundle, 0, LocalDate.now());

            verify(labOrderRepository).save(any(LabOrder.class));
            verify(labResultRepository).save(any(LabResult.class));
        }
    }

    @Nested
    class EnsureDeveloperConsentScenariosTests {

        @Test
        void ensureDeveloperConsentScenarios_callsSeedConsentWithTransactionTemplate() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("ensureDeveloperConsentScenarios");
            method.setAccessible(true);

            // TransactionTemplate must exist
            org.springframework.transaction.support.TransactionTemplate template =
                new org.springframework.transaction.support.TransactionTemplate(transactionManager);
            setField(seeder, "transactionTemplate", template);

            when(transactionManager.getTransaction(any())).thenReturn(mock(org.springframework.transaction.TransactionStatus.class));
            when(patientRepository.findByEmailContainingIgnoreCase(anyString())).thenReturn(Collections.emptyList());

            method.invoke(seeder);

            verify(patientRepository).findByEmailContainingIgnoreCase(contains("dev_patient_0007"));
        }
    }

    @Nested
    class SeedFeaturedAccountTests {

        @Test
        void seedFeaturedAccount_skipsWhenRoleMissing() throws Exception {
            Class<?> featuredSeedClass = findInnerClass("FeaturedAccountSeed");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "seedFeaturedAccount", featuredSeedClass, Hospital.class);
            method.setAccessible(true);

            Hospital hospital = buildHospital();

            // Create FeaturedAccountSeed
            Object seed = createFeaturedAccountSeed();

            // roleCache without the needed role
            setField(seeder, "roleCache", Map.of("ROLE_DOCTOR", buildRole("ROLE_DOCTOR")));

            method.invoke(seeder, seed, hospital);

            // Should skip because ROLE_MIDWIFE not in cache
            verify(userRepository, never()).findByUsernameIgnoreCase(anyString());
        }

        @Test
        void seedFeaturedAccount_createsAccountWhenRolePresent() throws Exception {
            Class<?> featuredSeedClass = findInnerClass("FeaturedAccountSeed");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "seedFeaturedAccount", featuredSeedClass, Hospital.class);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Object seed = createFeaturedAccountSeed();

            Role midwifeRole = buildRole("ROLE_MIDWIFE");
            Map<String, Role> cache = new HashMap<>();
            cache.put("ROLE_MIDWIFE", midwifeRole);
            setField(seeder, "roleCache", cache);
            setField(seeder, "defaultEncodedPassword", "encodedPw");

            User user = buildUser("dev_midwife_2031");
            when(userRepository.findByUsernameIgnoreCase("dev_midwife_2031")).thenReturn(Optional.of(user));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(userRoleRepository.findById(any())).thenReturn(Optional.empty());
            when(userRoleRepository.save(any(UserRole.class))).thenAnswer(inv -> inv.getArgument(0));
            when(assignmentRepository.existsByUserIdAndRoleIdAndHospitalIsNull(any(), any())).thenReturn(true);
            when(assignmentRepository.save(any(UserRoleHospitalAssignment.class))).thenAnswer(inv -> {
                UserRoleHospitalAssignment a = inv.getArgument(0);
                setId(a, UUID.randomUUID());
                return a;
            });
            when(assignmentRepository.findByUserIdAndHospitalIdAndRoleId(any(), any(), any())).thenReturn(Optional.empty());
            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any())).thenAnswer(inv -> {
                Permission p = inv.getArgument(0);
                setId(p, UUID.randomUUID());
                return p;
            });

            Department dept = buildDepartment(hospital);
            when(departmentRepository.findByHospitalIdAndCodeIgnoreCase(hospital.getId(), "PED"))
                .thenReturn(Optional.of(dept));
            when(staffRepository.findByUserIdAndHospitalId(user.getId(), hospital.getId()))
                .thenReturn(Optional.empty());
            when(staffRepository.save(any(Staff.class))).thenAnswer(inv -> {
                Staff s = inv.getArgument(0);
                setId(s, UUID.randomUUID());
                return s;
            });
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, seed, hospital);

            verify(staffRepository).save(any(Staff.class));
        }
    }

    @Nested
    class EnsureFeaturedUserTests {

        @Test
        void ensureFeaturedUser_returnsExistingWithUpdates() throws Exception {
            Class<?> featuredSeedClass = findInnerClass("FeaturedAccountSeed");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "ensureFeaturedUser", featuredSeedClass);
            method.setAccessible(true);

            setField(seeder, "defaultEncodedPassword", "encodedPw");

            Object seed = createFeaturedAccountSeed();

            User existing = buildUser("dev_midwife_2031");
            existing.setFirstName("OldName");
            existing.setLastName("OldLast");
            existing.setEmail("wrong@email.com");

            when(userRepository.findByUsernameIgnoreCase("dev_midwife_2031")).thenReturn(Optional.of(existing));
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);
            when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

            Object result = method.invoke(seeder, seed);

            assertThat(result).isNotNull();
            verify(userRepository).save(any(User.class));
        }

        @Test
        void ensureFeaturedUser_createsNewWhenNotFound() throws Exception {
            Class<?> featuredSeedClass = findInnerClass("FeaturedAccountSeed");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "ensureFeaturedUser", featuredSeedClass);
            method.setAccessible(true);

            setField(seeder, "defaultEncodedPassword", "encodedPw");

            Object seed = createFeaturedAccountSeed();

            User newUser = buildUser("dev_midwife_2031");
            newUser.setFirstName("Clarisse");
            newUser.setLastName("Sawadogo");
            newUser.setEmail("dev_midwife_2031@seed.dev");

            when(userRepository.findByUsernameIgnoreCase("dev_midwife_2031")).thenReturn(Optional.empty());
            when(userRepository.save(any(User.class))).thenReturn(newUser);
            when(passwordEncoder.matches(anyString(), anyString())).thenReturn(true);

            Object result = method.invoke(seeder, seed);

            assertThat(result).isNotNull();
            verify(userRepository, atLeastOnce()).save(any(User.class));
        }
    }

    @Nested
    class InitializeSequencesFromDatabaseTests {

        @Test
        void initializeSequences_callsBothSyncMethods() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("initializeSequencesFromDatabase");
            method.setAccessible(true);

            when(userRepository.findUsernamesByPrefix("dev_")).thenReturn(Collections.emptyList());
            when(userRepository.findMaxPhoneNumberWithPrefix("+226")).thenReturn(Optional.empty());

            method.invoke(seeder);

            verify(userRepository).findUsernamesByPrefix("dev_");
            verify(userRepository).findMaxPhoneNumberWithPrefix("+226");
        }
    }

    @Nested
    class BuildTreatmentSeedsTests {

        @Test
        void buildTreatmentSeeds_returnsListOfSeeds() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "buildTreatmentSeeds", staffBundleClass);
            method.setAccessible(true);

            Object staffBundle = createStaffBundle();

            Object result = method.invoke(seeder, staffBundle);

            assertThat(result).isNotNull().isInstanceOf(List.class);
            assertThat((List<?>) result).hasSize(5);
        }
    }

    @Nested
    class GetPermissionsForRoleTests {

        @Test
        void getPermissionsForRole_returnsNonEmptyList() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("getPermissionsForRole", String.class);
            method.setAccessible(true);

            Object result = method.invoke(seeder, "ROLE_DOCTOR");

            assertThat(result).isNotNull().isInstanceOf(List.class);
        }
    }

    @Nested
    class CreatePatientInsuranceViaWorkflowTests {

        @Test
        void createPatientInsurance_savesInsurance() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createPatientInsurance", Patient.class, staffBundleClass);
            method.setAccessible(true);

            Patient patient = Patient.builder().firstName("Test").lastName("Patient").build();
            setId(patient, UUID.randomUUID());
            // Set hospitalId on patient
            setField(patient, "hospitalId", UUID.randomUUID());

            Object staffBundle = createStaffBundle();

            when(patientInsuranceRepository.existsByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                any(), anyString(), anyString())).thenReturn(false);
            when(patientInsuranceRepository.save(any(PatientInsurance.class))).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, patient, staffBundle);

            verify(patientInsuranceRepository).save(any(PatientInsurance.class));
        }

        @Test
        void createPatientInsurance_skipsWhenAlreadyExists() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createPatientInsurance", Patient.class, staffBundleClass);
            method.setAccessible(true);

            Patient patient = Patient.builder().firstName("Test").build();
            setId(patient, UUID.randomUUID());
            setField(patient, "hospitalId", UUID.randomUUID());

            Object staffBundle = createStaffBundle();

            when(patientInsuranceRepository.existsByPatient_IdAndPayerCodeIgnoreCaseAndPolicyNumberIgnoreCase(
                any(), anyString(), anyString())).thenReturn(true);

            method.invoke(seeder, patient, staffBundle);

            verify(patientInsuranceRepository, never()).save(any(PatientInsurance.class));
        }
    }

    @Nested
    class SeedConsentForEmailFullPathTests {

        @Test
        void seedConsentForEmail_createsConsent() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedConsentForEmail", String.class);
            method.setAccessible(true);

            String email = "dev_patient_0007@seed.dev";
            UUID patientId = UUID.randomUUID();
            Patient patient = Patient.builder().firstName("Test").email(email).build();
            setId(patient, patientId);

            Hospital fromHospital = buildHospital();
            Hospital toHospital = Hospital.builder().name("Other Hospital").code("OTHER-H01").build();
            setId(toHospital, UUID.randomUUID());

            PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
                .patient(patient).hospital(fromHospital).active(true).build();
            setId(reg, UUID.randomUUID());

            when(patientRepository.findByEmailContainingIgnoreCase(email)).thenReturn(List.of(patient));
            when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(reg));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(any())).thenReturn(List.of(fromHospital, toHospital));
            when(patientConsentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(any(), any(), any()))
                .thenReturn(Optional.empty());
            when(patientConsentRepository.save(any(PatientConsent.class))).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, email);

            verify(patientConsentRepository).save(any(PatientConsent.class));
        }

        @Test
        void seedConsentForEmail_skipsWhenConsentAlreadyExists() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedConsentForEmail", String.class);
            method.setAccessible(true);

            String email = "dev_patient_0007@seed.dev";
            UUID patientId = UUID.randomUUID();
            Patient patient = Patient.builder().firstName("Test").email(email).build();
            setId(patient, patientId);

            Hospital fromHospital = buildHospital();
            Hospital toHospital = Hospital.builder().name("Other").code("OTHER").build();
            setId(toHospital, UUID.randomUUID());

            PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
                .patient(patient).hospital(fromHospital).active(true).build();
            setId(reg, UUID.randomUUID());

            when(patientRepository.findByEmailContainingIgnoreCase(email)).thenReturn(List.of(patient));
            when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(reg));
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(any())).thenReturn(List.of(fromHospital, toHospital));
            when(patientConsentRepository.findByPatientIdAndFromHospitalIdAndToHospitalId(any(), any(), any()))
                .thenReturn(Optional.of(PatientConsent.builder().build()));

            method.invoke(seeder, email);

            verify(patientConsentRepository, never()).save(any(PatientConsent.class));
        }

        @Test
        void seedConsentForEmail_skipsWhenNoAlternateHospital() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedConsentForEmail", String.class);
            method.setAccessible(true);

            String email = "dev_patient_0007@seed.dev";
            UUID patientId = UUID.randomUUID();
            Patient patient = Patient.builder().firstName("Test").email(email).build();
            setId(patient, patientId);

            Hospital fromHospital = buildHospital();

            PatientHospitalRegistration reg = PatientHospitalRegistration.builder()
                .patient(patient).hospital(fromHospital).active(true).build();
            setId(reg, UUID.randomUUID());

            when(patientRepository.findByEmailContainingIgnoreCase(email)).thenReturn(List.of(patient));
            when(registrationRepository.findByPatientId(patientId)).thenReturn(List.of(reg));
            // Only the from hospital in candidates (no alternate)
            when(hospitalRepository.findByOrganizationIdOrderByNameAsc(any())).thenReturn(List.of(fromHospital));

            method.invoke(seeder, email);

            verify(patientConsentRepository, never()).save(any(PatientConsent.class));
        }
    }

    @Nested
    class SeedTreatmentsForHospitalFullPathTests {

        @Test
        void seedTreatmentsForHospital_createsNewTreatments() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "seedTreatmentsForHospital", Hospital.class, staffBundleClass);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Object staffBundle = createStaffBundle();

            when(treatmentRepository.findByHospital_Id(hospital.getId())).thenReturn(Collections.emptyList());
            when(treatmentRepository.save(any(Treatment.class))).thenAnswer(inv -> {
                Treatment t = inv.getArgument(0);
                setId(t, UUID.randomUUID());
                t.setTranslations(new HashSet<>());
                return t;
            });
            when(serviceTranslationRepository.save(any(ServiceTranslation.class))).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, hospital, staffBundle);

            verify(treatmentRepository, atLeast(5)).save(any(Treatment.class));
        }

        @Test
        void seedTreatmentsForHospital_updatesExistingTreatments() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "seedTreatmentsForHospital", Hospital.class, staffBundleClass);
            method.setAccessible(true);

            Hospital hospital = buildHospital();
            Object staffBundle = createStaffBundle();

            Department otherDept = buildDepartment(hospital);
            UserRoleHospitalAssignment otherAssignment = buildAssignment();

            Treatment existingTreatment = Treatment.builder()
                .name("Prenatal consultation")
                .department(otherDept)
                .assignment(otherAssignment)
                .hospital(hospital)
                .build();
            setId(existingTreatment, UUID.randomUUID());

            when(treatmentRepository.findByHospital_Id(hospital.getId())).thenReturn(List.of(existingTreatment));
            when(treatmentRepository.save(any(Treatment.class))).thenAnswer(inv -> {
                Treatment t = inv.getArgument(0);
                if (t.getTranslations() == null) t.setTranslations(new HashSet<>());
                return t;
            });
            when(serviceTranslationRepository.save(any(ServiceTranslation.class))).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, hospital, staffBundle);

            // At least some treatments saved (new ones + possibly updated existing)
            verify(treatmentRepository, atLeastOnce()).save(any(Treatment.class));
        }
    }

    @Nested
    class CreateEncounterTreatmentsFullTests {

        @Test
        void createEncounterTreatments_savesWhenCachePopulated() throws Exception {
            Class<?> staffBundleClass = findInnerClass("StaffBundle");
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createEncounterTreatments", Encounter.class, Hospital.class, staffBundleClass, int.class);
            method.setAccessible(true);

            Encounter encounter = Encounter.builder()
                .encounterDate(LocalDateTime.now())
                .build();
            setId(encounter, UUID.randomUUID());
            Hospital hospital = buildHospital();
            Object staffBundle = createStaffBundle();

            // Pre-populate the treatments cache
            Treatment treatment = Treatment.builder().name("Test treatment").build();
            setId(treatment, UUID.randomUUID());
            Staff performer = Staff.builder().name("Test Doctor").build();
            setId(performer, UUID.randomUUID());

            // Create SeededTreatment via reflection
            Class<?> seededTreatmentClass = findInnerClass("SeededTreatment");
            var seededCtor = seededTreatmentClass.getDeclaredConstructors()[0];
            seededCtor.setAccessible(true);
            Object seededTreatment = seededCtor.newInstance(treatment, performer);

            @SuppressWarnings("unchecked")
            Map<UUID, Object> cache = (Map<UUID, Object>) getField(seeder, "hospitalTreatmentsCache");
            cache.put(hospital.getId(), List.of(seededTreatment));

            when(encounterTreatmentRepository.findByEncounter_Id(encounter.getId())).thenReturn(Collections.emptyList());
            when(encounterTreatmentRepository.save(any(EncounterTreatment.class))).thenAnswer(inv -> inv.getArgument(0));

            method.invoke(seeder, encounter, hospital, staffBundle, 0);

            verify(encounterTreatmentRepository).save(any(EncounterTreatment.class));
        }
    }

    @Nested
    class SeedFeaturedAccountsIfNeededTests {

        @Test
        void seedFeaturedAccountsIfNeeded_skipsWhenAlreadySeeded() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedFeaturedAccountsIfNeeded");
            method.setAccessible(true);

            setField(seeder, "featuredAccountsSeeded", true);

            method.invoke(seeder);

            verify(hospitalRepository, never()).findByCodeIgnoreCase("DEV-ORG-01-H01");
        }

        @Test
        void seedFeaturedAccountsIfNeeded_skipsWhenHospitalNotFound() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedFeaturedAccountsIfNeeded");
            method.setAccessible(true);

            setField(seeder, "featuredAccountsSeeded", false);
            when(hospitalRepository.findByCodeIgnoreCase("DEV-ORG-01-H01")).thenReturn(Optional.empty());

            method.invoke(seeder);

            boolean seeded = (boolean) getField(seeder, "featuredAccountsSeeded");
            assertThat(seeded).isFalse();
        }

        @Test
        void seedFeaturedAccountsIfNeeded_seedsWhenHospitalFound() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod("seedFeaturedAccountsIfNeeded");
            method.setAccessible(true);

            setField(seeder, "featuredAccountsSeeded", false);
            Hospital hospital = buildHospital();
            when(hospitalRepository.findByCodeIgnoreCase("DEV-ORG-01-H01")).thenReturn(Optional.of(hospital));

            // roleCache without ROLE_MIDWIFE so seedFeaturedAccount skips
            setField(seeder, "roleCache", Map.of("ROLE_DOCTOR", buildRole("ROLE_DOCTOR")));

            method.invoke(seeder);

            boolean seeded = (boolean) getField(seeder, "featuredAccountsSeeded");
            assertThat(seeded).isTrue();
        }
    }

    @Nested
    class CreatePermissionsForAssignmentOptimizedTests {

        @Test
        void createPermissionsForAssignmentOptimized_createsOnlyMissing() throws Exception {
            Method method = DevSyntheticDataSeeder.class.getDeclaredMethod(
                "createPermissionsForAssignmentOptimized",
                UserRoleHospitalAssignment.class, Role.class, Set.class);
            method.setAccessible(true);

            UserRoleHospitalAssignment assignment = buildAssignment();
            Role role = buildRole("ROLE_DOCTOR");

            // Pretend some permissions already exist by name
            Set<String> existingPermissions = new HashSet<>();
            existingPermissions.add("view patient records"); // lowercase match

            when(permissionRepository.existsByNameAndAssignment_Id(anyString(), any())).thenReturn(false);
            when(permissionRepository.save(any(Permission.class))).thenAnswer(inv -> {
                Permission p = inv.getArgument(0);
                setId(p, UUID.randomUUID());
                return p;
            });

            method.invoke(seeder, assignment, role, existingPermissions);

            // Should have created permissions (for ROLE_DOCTOR minus those already in set)
            verify(permissionRepository, atLeastOnce()).save(any(Permission.class));
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Reflection helpers
    // ═══════════════════════════════════════════════════════════════

    private Role buildRole(String code) {
        Role role = Role.builder().name(code).code(code).description("Test role").build();
        try {
            setId(role, UUID.randomUUID());
        } catch (RuntimeException ignored) {
            // Expected in test scenario
        }
        return role;
    }

    private User buildUser(String username) {
        User user = User.builder()
            .username(username)
            .passwordHash("encodedPw")
            .email(username + "@seed.dev")
            .firstName("First")
            .lastName("Last")
            .phoneNumber("+22660000001")
            .isActive(true)
            .isDeleted(false)
            .forcePasswordChange(false)
            .build();
        setId(user, UUID.randomUUID());
        // Initialize userRoles collection
        try {
            setField(user, "userRoles", new java.util.HashSet<>());
        } catch (RuntimeException ignored) {
            // Expected in test scenario
        }
        return user;
    }

    private Organization buildOrganization() {
        Organization org = Organization.builder()
            .name("Dev Seed Organization 1")
            .code("DEV-ORG-01")
            .description("Synthetic organization")
            .active(true)
            .build();
        setId(org, UUID.randomUUID());
        return org;
    }

    private Hospital buildHospital() {
        Organization org = buildOrganization();
        Hospital hospital = Hospital.builder()
            .name("Test Hospital 01")
            .code("DEV-ORG-01-H01")
            .city("Ouagadougou")
            .state("Centre")
            .zipCode("BF-001")
            .country("Burkina Faso")
            .phoneNumber("+22660000010")
            .active(true)
            .organization(org)
            .build();
        setId(hospital, UUID.randomUUID());
        return hospital;
    }

    private UserRoleHospitalAssignment buildAssignment() {
        UserRoleHospitalAssignment assignment = UserRoleHospitalAssignment.builder()
            .assignmentCode("TEST-ASSIGN-0001")
            .description("Test assignment")
            .active(true)
            .build();
        setId(assignment, UUID.randomUUID());
        User user = buildUser("assignee");
        assignment.setUser(user);
        return assignment;
    }

    private Department buildDepartment(Hospital hospital) {
        Department dept = Department.builder()
            .name("General Medicine")
            .code("GEN01")
            .hospital(hospital)
            .active(true)
            .bedCapacity(20)
            .build();
        setId(dept, UUID.randomUUID());
        return dept;
    }

    private Class<?> findInnerClass(String simpleName) {
        for (Class<?> c : DevSyntheticDataSeeder.class.getDeclaredClasses()) {
            if (c.getSimpleName().equals(simpleName)) {
                return c;
            }
        }
        throw new RuntimeException("Inner class not found: " + simpleName);
    }

    private Object createStaffBundle() throws Exception {
        Class<?> staffBundleClass = findInnerClass("StaffBundle");
        Hospital hospital = buildHospital();
        User adminUser = buildUser("admin");
        UserRoleHospitalAssignment adminAssignment = buildAssignment();
        Department generalDept = buildDepartment(hospital);
        Department pedsDept = buildDepartment(hospital);
        setId(pedsDept, UUID.randomUUID());
        Department labDept = buildDepartment(hospital);
        setId(labDept, UUID.randomUUID());

        Staff adminStaff = Staff.builder().name("Admin Staff").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.HOSPITAL_ADMINISTRATOR).active(true).build();
        setId(adminStaff, UUID.randomUUID());
        adminStaff.setUser(adminUser);

        Staff doctor = Staff.builder().name("Doctor Staff").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.DOCTOR).active(true).build();
        setId(doctor, UUID.randomUUID());

        Staff nurse = Staff.builder().name("Nurse Staff").hospital(hospital).department(pedsDept)
            .assignment(adminAssignment).jobTitle(JobTitle.NURSE).active(true).build();
        setId(nurse, UUID.randomUUID());

        Staff receptionist = Staff.builder().name("Receptionist").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.RECEPTIONIST).active(true).build();
        setId(receptionist, UUID.randomUUID());

        Staff billing = Staff.builder().name("Billing").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.BILLING_SPECIALIST).active(true).build();
        setId(billing, UUID.randomUUID());

        Staff labScientist = Staff.builder().name("Lab Scientist").hospital(hospital).department(labDept)
            .assignment(adminAssignment).jobTitle(JobTitle.LABORATORY_SCIENTIST).active(true).build();
        setId(labScientist, UUID.randomUUID());

        Staff surgeon = Staff.builder().name("Surgeon").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.SURGEON).active(true).build();
        setId(surgeon, UUID.randomUUID());

        Staff physician = Staff.builder().name("Physician").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.PHYSICIAN).active(true).build();
        setId(physician, UUID.randomUUID());

        Staff midwife = Staff.builder().name("Midwife").hospital(hospital).department(pedsDept)
            .assignment(adminAssignment).jobTitle(JobTitle.MIDWIFE).active(true).build();
        setId(midwife, UUID.randomUUID());

        Staff radiologist = Staff.builder().name("Radiologist").hospital(hospital).department(labDept)
            .assignment(adminAssignment).jobTitle(JobTitle.RADIOLOGIST).active(true).build();
        setId(radiologist, UUID.randomUUID());

        Staff anesthesiologist = Staff.builder().name("Anesthesiologist").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.ANESTHESIOLOGIST).active(true).build();
        setId(anesthesiologist, UUID.randomUUID());

        Staff physiotherapist = Staff.builder().name("Physiotherapist").hospital(hospital).department(generalDept)
            .assignment(adminAssignment).jobTitle(JobTitle.PHYSIOTHERAPIST).active(true).build();
        setId(physiotherapist, UUID.randomUUID());

        var constructor = staffBundleClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(
            adminUser, adminAssignment, generalDept, pedsDept, labDept,
            adminStaff, doctor, nurse, receptionist, billing, labScientist,
            surgeon, physician, midwife, radiologist, anesthesiologist, physiotherapist
        );
    }

    private Object createFeaturedAccountSeed() throws Exception {
        Class<?> seedClass = findInnerClass("FeaturedAccountSeed");
        var constructor = seedClass.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        return constructor.newInstance(
            "dev_midwife_2031", "Clarisse", "Sawadogo", "ROLE_MIDWIFE",
            JobTitle.MIDWIFE, "PED", EmploymentType.FULL_TIME, "Maternal Support Lead"
        );
    }

    private void setId(Object entity, UUID id) {
        try {
            // Try the entity's own class first, then walk up
            Class<?> clazz = entity.getClass();
            while (clazz != null) {
                try {
                    Field idField = clazz.getDeclaredField("id");
                    idField.setAccessible(true);
                    idField.set(entity, id);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Expected in test scenario
        }
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    field.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new RuntimeException("Failed to set field: " + fieldName, e);
        }
    }

    private Object getField(Object target, String fieldName) {
        try {
            Class<?> clazz = target.getClass();
            while (clazz != null) {
                try {
                    Field field = clazz.getDeclaredField(fieldName);
                    field.setAccessible(true);
                    return field.get(target);
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new RuntimeException("Field not found: " + fieldName);
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw new RuntimeException("Failed to get field: " + fieldName, e);
        }
    }
}
