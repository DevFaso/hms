package com.example.hms.repository;

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
 *   <li>a write;</li>
 *   <li>or a caller recorded below with its reason: a plain by-id read
 *       ({@link #BY_ID_RECORDED}, method by method), or an unfiltered list,
 *       count or name/code lookup ({@code RECORDED}). A clinical destination
 *       uses {@code findClinicalById} or a {@code findClinicalBy...} name/code
 *       finder instead, so a new unfiltered caller fails this test until it
 *       is added deliberately.</li>
 * </ul>
 * In the style of {@code HospitalIdParameterCoverageTest}: the map only shrinks.
 */
class HospitalRepositoryCallerCoverageTest {

    private static final String REPOSITORY = HospitalRepository.class.getName().replace('.', '/');
    private static final String HOSPITAL_FILTER = "h.facilityType = com.example.hms.enums.FacilityType.HOSPITAL";

    /** Finders whose query keeps HOSPITAL rows only. */
    private static final Set<String> CLINICAL_FINDERS = Set.of(
        "findAllHospitals", "countHospitals", "countActiveHospitals", "findAllForFilters",
        "searchHospitals", "findAllWithDepartments", "findByOrganizationIdOrderByNameAsc",
        "findByOrganizationIsNull", "findClinicalById", "findClinicalByNameIgnoreCase",
        "findClinicalByName", "findClinicalByCodeIgnoreCase", "findClinicalByNameOrCodeOrEmail");

    /** Writes: not a read of a destination. */
    private static final Set<String> WRITES = Set.of(
        "save", "saveAndFlush", "saveAll", "deleteById", "delete", "flush");

    /**
     * Plain reads of one hospital by id, which see every facility type. A
     * request-supplied clinical destination uses findClinicalById instead, so
     * every caller of these, method by method, is recorded in {@link #BY_ID_RECORDED}.
     */
    private static final Set<String> BY_ID_READS = Set.of(
        "findById", "getReferenceById", "getById", "existsById", "findByIdForUpdate");

    private static final String LABEL = "names a facility for display; it grants, writes and lists nothing";
    private static final String ACTING_SCOPE = "the caller's own acting scope (requirePinned), not request-supplied;"
        + " a provider user never reaches the handler (confinement)";
    private static final String PHARMACY_OWN = "the pharmacy's own workflow, which a PHARMACY provider does in P2-PH;"
        + " the service's own ownership checks scope it";
    private static final String ANY_TYPE = "must see every facility type: ";

    /** Class.method#by-id read of a hospital → why it is not findClinicalById. Frozen: it only shrinks. */
    private static final Map<String, String> BY_ID_RECORDED = Map.ofEntries(
        entry("MeController.myHospital#findById", LABEL + " (the caller's own facility)"),
        entry("AuthBootstrapServiceImpl.resolveCurrentSession#findById", LABEL + " (the caller's own primary facility)"),
        entry("MorbidityAnalyticsServiceImpl.hospitalView#findById", LABEL),
        entry("PatientPortalServiceImpl.toProfileDTO#findById", LABEL + " (a hospital on the patient's own record)"),
        entry("UserServiceImpl.createUserWithRolesAndHospital#findById", LABEL + " (the activation message)"),
        entry("FacilityAssignmentGuard.requireCompatible#findById",
            ANY_TYPE + "the role/facility compatibility check is about providers (slice 1)"),
        entry("RecordAccessPolicyImpl.evaluate#findById", ANY_TYPE + "loads the acting facility to refuse a provider (AC-9)"),
        entry("RecordAccessPolicyImpl.actsAtProvider#findById", ANY_TYPE + "an empty readable set at a provider (AC-9)"),
        entry("HospitalServiceImpl.deleteHospital#findByIdForUpdate", ANY_TYPE + "super-admin delete of a never-verified provider (slice 1)"),
        entry("HospitalServiceImpl.getHospitalOrThrow#findById", ANY_TYPE + "super-admin read of one facility row"),
        entry("HospitalLifecycleServiceImpl.loadOrThrow#findById", ANY_TYPE + "the lifecycle of every facility type (AC-16)"),
        entry("HospitalLifecycleServiceImpl.lockOrThrow#findByIdForUpdate", ANY_TYPE + "the lifecycle of every facility type (AC-16)"),
        entry("ProviderOnboardingServiceImpl.loadProvider#findById", ANY_TYPE + "provider onboarding"),
        entry("ProviderOnboardingServiceImpl.lockProvider#findByIdForUpdate", ANY_TYPE + "provider onboarding"),
        entry("UserRoleHospitalAssignmentServiceImpl.resolveHospitalHumanAware#findById",
            ANY_TYPE + "an assignment target, held to RoleFacilityCompatibility (slice 1)"),
        entry("UserServiceImpl.upsertStaff#findById", ANY_TYPE + "a staff row at a provider, registered by its PROVIDER_ADMIN (slice 1)"),
        entry("UserServiceImpl.resolveHospitalForStaff#findById",
            ANY_TYPE + "admin-register of staff at a provider, held to RoleFacilityCompatibility (slice 1)"),
        entry("MllpAllowedSenderServiceImpl.loadHospital#findById", ANY_TYPE + "P2-LAB binds an HL7 sender to a lab provider (AC-43)"),
        entry("LabOrderServiceImpl.resolvePerformingHospital#findById", ANY_TYPE + "the performing lab, a provider in P2-LAB (AC-40); routability is checked there"),
        entry("LabInstrumentServiceImpl.create#findById", ANY_TYPE + "lab-side setup a LABORATORY provider owns in P2-LAB"),
        entry("LabInventoryServiceImpl.create#findById", ANY_TYPE + "lab-side setup a LABORATORY provider owns in P2-LAB"),
        entry("ApiKeyService.issue#getReferenceById", ANY_TYPE + "a partner credential of the acting facility; nothing clinical"),
        entry("WebhookEndpointService.register#getReferenceById", ANY_TYPE + "a partner endpoint of the acting facility; nothing clinical"),
        entry("AnnouncementServiceImpl.createAnnouncement#getReferenceById", ANY_TYPE + "a staff announcement to the acting facility; nothing clinical"),
        entry("TenantProvisioningService.provision#findById", ANY_TYPE + "super-admin schema provisioning of a tenant row"),
        entry("LegacyAllergyTextBackfillWorker.resolveHospital#findById", "a backfill over rows already written; it creates no destination"),
        entry("PharmacySaleServiceImpl.createSale#findById", PHARMACY_OWN),
        entry("PharmacyClaimServiceImpl.createClaim#findById", PHARMACY_OWN),
        entry("PharmacyPaymentServiceImpl.createPayment#findById", PHARMACY_OWN),
        entry("MtmReviewServiceImpl.startReview#findById", PHARMACY_OWN),
        entry("PanelService.assign#getReferenceById", ACTING_SCOPE),
        entry("ProgramEnrollmentService.enroll#getReferenceById", ACTING_SCOPE),
        entry("RoiRequestService.persistRequest#getReferenceById", ACTING_SCOPE));


    /** Caller#method of an unfiltered finder → why that is right. Frozen: it only shrinks. */
    private static final Map<String, String> RECORDED = Map.ofEntries(
        entry("AppointmentServiceImpl#findAllById",
            "names the hospitals of appointments already read under the caller's scope: a label lookup, not a list of tenants"),
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
                if (CLINICAL_FINDERS.contains(method) || WRITES.contains(method) || BY_ID_READS.contains(method)) {
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
    @DisplayName("every plain by-id hospital read is recorded, method by method; destinations use findClinicalById")
    void everyByIdReadIsRecorded() throws IOException {
        TreeSet<String> byId = new TreeSet<>();
        for (String call : scanByMethod()) {
            if (BY_ID_READS.contains(call.substring(call.indexOf('#') + 1))) {
                byId.add(call);
            }
        }
        TreeSet<String> unrecorded = new TreeSet<>(byId);
        unrecorded.removeAll(BY_ID_RECORDED.keySet());
        assertThat(unrecorded).as("a plain by-id hospital read: a request-supplied clinical destination uses"
            + " findClinicalById; anything else needs a recorded reason").isEmpty();
        assertThat(byId).as("recorded by-id reads that no longer exist: remove them").containsAll(BY_ID_RECORDED.keySet());
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
        Set<String> called = new TreeSet<>();
        scan().values().forEach(called::addAll);
        assertThat(called).as("the repository methods main code calls")
            .isNotEmpty()
            .doesNotContain("findAll", "count");
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

    /** Every HospitalRepository call as "OuterClass.method#repositoryMethod"; a lambda counts as its enclosing method. */
    private static Set<String> scanByMethod() throws IOException {
        Set<String> calls = new TreeSet<>();
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
                        String caller = owner[0] + "." + enclosingMethod(name);
                        return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
                            @Override
                            public void visitMethodInsn(int opcode, String target, String method,
                                                        String desc, boolean isInterface) {
                                if (REPOSITORY.equals(target)) {
                                    calls.add(caller + "#" + method);
                                }
                            }

                            @Override
                            public void visitInvokeDynamicInsn(String method, String desc, Handle bootstrap,
                                                               Object... arguments) {
                                for (Object argument : arguments) {
                                    if (argument instanceof Handle handle && REPOSITORY.equals(handle.getOwner())) {
                                        calls.add(caller + "#" + handle.getName());
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

    /** {@code lambda$foo$3} belongs to {@code foo}. */
    private static String enclosingMethod(String name) {
        if (name.startsWith("lambda$")) {
            int end = name.indexOf('$', "lambda$".length());
            return end < 0 ? name : name.substring("lambda$".length(), end);
        }
        return name;
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
