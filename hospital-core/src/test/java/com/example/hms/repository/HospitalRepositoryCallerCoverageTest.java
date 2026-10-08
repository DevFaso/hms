package com.example.hms.repository;

import com.example.hms.security.provider.ClinicalHospitals;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider plan AC-11, T14: a provider row (PHARMACY, LABORATORY) must never
 * appear where code reads "any hospital row" as "a clinical tenant".
 *
 * <p>Every call to a {@link HospitalRepository} method, anywhere in the main
 * code, is found by a bytecode scan (lambdas and method references included)
 * and must be one of:
 * <ul>
 *   <li>a CLINICAL finder, whose query filters {@code facilityType = HOSPITAL}
 *       (checked on the annotation here, so the filter cannot be dropped);</li>
 *   <li>a single row by id, or a write: not a list, a count or a name lookup;
 *       the destinations among them go through {@code ClinicalHospitals};</li>
 *   <li>or a caller recorded below with its reason. A new unfiltered caller
 *       fails this test until it is added deliberately. Callers recorded as
 *       filtering at the call site must reference {@link ClinicalHospitals}.</li>
 * </ul>
 * In the style of {@code HospitalIdParameterCoverageTest}: the map only shrinks.
 */
class HospitalRepositoryCallerCoverageTest {

    private static final String REPOSITORY = HospitalRepository.class.getName().replace('.', '/');
    private static final String CLINICAL_HELPER = ClinicalHospitals.class.getName().replace('.', '/');
    private static final String HOSPITAL_FILTER = "h.facilityType = com.example.hms.enums.FacilityType.HOSPITAL";

    /** Finders whose query keeps HOSPITAL rows only. */
    private static final Set<String> CLINICAL_FINDERS = Set.of(
        "findAllHospitals", "countHospitals", "countActiveHospitals", "findAllForFilters",
        "searchHospitals", "findAllWithDepartments", "findByOrganizationIdOrderByNameAsc",
        "findByOrganizationIsNull");

    /** One row by id, or a write; never a list, a count or a lookup by a user-typed name. */
    private static final Set<String> BY_ID_OR_WRITE = Set.of(
        "findById", "getReferenceById", "getById", "existsById", "findByIdForUpdate",
        "save", "saveAndFlush", "saveAll", "deleteById", "delete", "flush",
        // The confinement filter: the provider types among the caller's own facilities.
        "findProviderFacilityTypesByIdIn");

    private static final String AT_CALL_SITE = "filters with ClinicalHospitals at the call site: ";

    /** Caller#method of an unfiltered finder → why that is right. Frozen: it only shrinks. */
    private static final Map<String, String> RECORDED = Map.ofEntries(
        entry("AppointmentServiceImpl#findByCodeIgnoreCase", AT_CALL_SITE + "the booking hospital (AC-11)"),
        entry("AppointmentServiceImpl#findByNameIgnoreCase", AT_CALL_SITE + "the booking hospital (AC-11)"),
        entry("AppointmentServiceImpl#findAllById",
            "names the hospitals of appointments already read under the caller's scope: a label lookup, not a list of tenants"),
        entry("BillingInvoiceServiceImpl#findByNameIgnoreCase", AT_CALL_SITE + "the invoice hospital (AC-11)"),
        entry("EncounterServiceImpl#findByNameOrCodeOrEmail", AT_CALL_SITE + "the encounter hospital (AC-11)"),
        entry("PatientHospitalRegistrationServiceImpl#findByName", AT_CALL_SITE + "the registration hospital (AC-10, AC-11)"),
        entry("UserController#findByName", AT_CALL_SITE + "admin-register by hospital name (AC-11)"),
        entry("DepartmentServiceImpl#findByNameIgnoreCase", AT_CALL_SITE + "the department hospital (slice 1)"),
        entry("PatientPortalServiceImpl#findAllById",
            "names the hospitals on the patient's own primary-care entries: a label lookup, not a list of tenants"),
        entry("HospitalLifecycleStatusServiceImpl#findIdsByLifecycleStateIn",
            "the lifecycle gate must block a suspended provider as it blocks a hospital: every type, on purpose"),
        entry("LabOrderServiceImpl#findByActiveTrueAndLifecycleStateOrderByNameAsc",
            "the performing-lab picker: filtered by performsLabWork today; P2-LAB (AC-40) decides which provider labs it offers"),
        entry("HospitalServiceImpl#existsByNameIgnoreCaseAndZipCode",
            "duplicate check on hospital creation: a name already taken by any facility is taken"),
        entry("ProviderOnboardingServiceImpl#findByCodeIgnoreCase",
            "duplicate facility code on provider onboarding: codes are unique across every type"),
        entry("StaffServiceImpl#findByName",
            "staff hospital by name; a staff row at a provider is held to the facility's compatible roles by the assignment guard"),
        entry("ChatMessageServiceImpl#findByNameIgnoreCase",
            "the optional hospital label of a chat message; it grants nothing and lists nothing"),
        entry("UserRoleHospitalAssignmentServiceImpl#findByCodeIgnoreCase",
            "the assignment target by code: a provider is a valid target, held to RoleFacilityCompatibility (slice 1)"),
        entry("UserRoleHospitalAssignmentServiceImpl#findByNameIgnoreCase",
            "the assignment target by name: a provider is a valid target, held to RoleFacilityCompatibility (slice 1)"),
        entry("SuperAdminLabOrderServiceImpl#findByCodeIgnoreCase",
            "super-admin lab-order lookup by hospital code; a lab provider performs lab orders"),
        entry("SuperAdminLabOrderServiceImpl#findByNameIgnoreCase",
            "super-admin lab-order lookup by hospital name; a lab provider performs lab orders"),
        entry("UserServiceImpl#findByCodeIgnoreCase", "the dev default hospital by its fixed code"),
        entry("DevSyntheticDataSeeder#findByCodeIgnoreCase", "dev seed data, by the seed's own fixed codes"));

    @Test
    @DisplayName("every unfiltered HospitalRepository caller is recorded with its reason")
    void everyUnfilteredCallerIsRecorded() throws IOException {
        Map<String, Set<String>> calls = scan();
        Map<String, String> unrecorded = new TreeMap<>();
        TreeSet<String> seen = new TreeSet<>();
        calls.forEach((caller, methods) -> {
            for (String method : methods) {
                if (CLINICAL_FINDERS.contains(method) || BY_ID_OR_WRITE.contains(method)) {
                    continue;
                }
                String key = caller + "#" + method;
                seen.add(key);
                if (!RECORDED.containsKey(key)) {
                    unrecorded.put(key, "no recorded reason");
                }
            }
        });
        assertThat(unrecorded).as("a new unfiltered HospitalRepository caller: use a clinical finder,"
            + " filter with ClinicalHospitals, or record why it needs every facility type").isEmpty();
        assertThat(seen).as("recorded callers that no longer exist: remove them").containsAll(RECORDED.keySet());
    }

    @Test
    @DisplayName("the callers recorded as filtering at the call site do reference ClinicalHospitals")
    void callSiteFiltersAreThere() throws IOException {
        Set<String> referencing = classesReferencingTheHelper();
        RECORDED.forEach((key, reason) -> {
            if (reason.startsWith(AT_CALL_SITE)) {
                assertThat(referencing).as(key).contains(key.substring(0, key.indexOf('#')));
            }
        });
    }

    @Test
    @DisplayName("every clinical finder's query keeps HOSPITAL rows only")
    void clinicalFindersFilterTheType() {
        for (String finder : CLINICAL_FINDERS) {
            Method method = null;
            for (Method candidate : HospitalRepository.class.getDeclaredMethods()) {
                if (candidate.getName().equals(finder)) {
                    method = candidate;
                }
            }
            assertThat(method).as("clinical finder %s is gone", finder).isNotNull();
            Query query = method.getAnnotation(Query.class);
            assertThat(query).as("%s must carry an explicit @Query", finder).isNotNull();
            assertThat(query.value().replaceAll("\\s+", " ")).as(finder).contains(HOSPITAL_FILTER);
        }
    }

    @Test
    @DisplayName("no main code counts or lists hospitals through the inherited unfiltered findAll / count")
    void inheritedFindAllAndCountAreUnused() throws IOException {
        Map<String, Set<String>> calls = scan();
        calls.forEach((caller, methods) -> assertThat(methods).as(caller)
            .doesNotContain("findAll", "count"));
    }

    // ── bytecode scan ───────────────────────────────────────────────────────

    /** Outer class simple name → the HospitalRepository methods it calls. */
    private static Map<String, Set<String>> scan() throws IOException {
        Map<String, Set<String>> calls = new TreeMap<>();
        for (Resource resource : mainClasses()) {
            String[] owner = new String[1];
            try (InputStream in = resource.getInputStream()) {
                new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                                      String superName, String[] interfaces) {
                        owner[0] = outerSimpleName(name);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                            @Override
                            public void visitMethodInsn(int opcode, String target, String method,
                                                        String desc, boolean isInterface) {
                                if (REPOSITORY.equals(target)) {
                                    calls.computeIfAbsent(owner[0], k -> new TreeSet<>()).add(method);
                                }
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap,
                                                               Object... arguments) {
                                for (Object argument : arguments) {
                                    if (argument instanceof Handle handle && REPOSITORY.equals(handle.getOwner())) {
                                        calls.computeIfAbsent(owner[0], k -> new TreeSet<>()).add(handle.getName());
                                    }
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return calls;
    }

    private static Set<String> classesReferencingTheHelper() throws IOException {
        Set<String> found = new TreeSet<>();
        for (Resource resource : mainClasses()) {
            String[] owner = new String[1];
            try (InputStream in = resource.getInputStream()) {
                new ClassReader(in).accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
                    @Override
                    public void visit(int version, int access, String name, String signature,
                                      String superName, String[] interfaces) {
                        owner[0] = outerSimpleName(name);
                    }

                    @Override
                    public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                     String signature, String[] exceptions) {
                        return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                            @Override
                            public void visitMethodInsn(int opcode, String target, String method,
                                                        String desc, boolean isInterface) {
                                if (CLINICAL_HELPER.equals(target)) {
                                    found.add(owner[0]);
                                }
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap,
                                                               Object... arguments) {
                                for (Object argument : arguments) {
                                    if (argument instanceof Handle handle && CLINICAL_HELPER.equals(handle.getOwner())) {
                                        found.add(owner[0]);
                                    }
                                }
                            }
                        };
                    }
                }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            }
        }
        return found;
    }

    private static Resource[] mainClasses() throws IOException {
        Resource[] all = new PathMatchingResourcePatternResolver().getResources("classpath*:com/example/hms/**/*.class");
        return java.util.Arrays.stream(all)
            .filter(resource -> {
                try {
                    return resource.getURL().toString().contains("/main/");
                } catch (IOException e) {
                    return false;
                }
            })
            .toArray(Resource[]::new);
    }

    private static String outerSimpleName(String internalName) {
        String simple = internalName.substring(internalName.lastIndexOf('/') + 1);
        int nested = simple.indexOf('$');
        return nested < 0 ? simple : simple.substring(0, nested);
    }
}
