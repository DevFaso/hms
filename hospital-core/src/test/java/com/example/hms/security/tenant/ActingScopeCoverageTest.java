package com.example.hms.security.tenant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The source ratchet of docs/security/tenant-resolution.md §4.3: every scope
 * decision goes through {@link ActingScopeResolver}, and the ways around it
 * the design inventoried (§1.3) may only shrink.
 *
 * <p>Each pattern below is one of those ways: a raw read of the active
 * hospital (which for a super-admin in global view used to be an incidental
 * assignment, D2), a local super-admin test, a "newest assignment" pick. For
 * each, the files allowed to match and how many times, with the reason. The
 * test fails when a file matches that is not listed or matches more often than
 * listed — route the new code through the resolver instead — and when a listed
 * file matches LESS often than listed: lower the entry (or delete it) in the
 * same change, so the list only ever shrinks.
 *
 * <p>It does not scan authority guards ({@code @PreAuthorize},
 * {@code SecurityConfig}); those are made live by reconciling the authorities
 * in both filters (Q10, option A) and paired by
 * {@code PreAuthorizeMatcherPairingTest}. Comment lines are not counted, and
 * {@code security/context/**} (the context itself) is not scanned.
 */
class ActingScopeCoverageTest {

    private static final Path MAIN = Paths.get("src/main/java");
    private static final String CONTEXT_PACKAGE = "com/example/hms/security/context/";
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(\\*|//|/\\*)");

    private static final String RESOLVER = "com/example/hms/security/tenant/ActingScopeResolver.java";
    private static final String LAB_PR =
        "Owned by the pharmacy/lab/HL7 PR of this batch; a one-line move to "
            + "ActingScopeResolver.pinnedHospitalIdOrNull() after it lands";
    private static final String VERIFIED_SIGNAL =
        "HospitalContext.isSuperAdmin() is the VERIFIED signal now (a live SUPER_ADMIN assignment, "
            + "computed by the shared producer): a local test of it no longer disagrees with the resolver";
    private static final String RECONCILED_AUTHORITY =
        "The authorities are reconciled with the live super-admin signal in both filters (Q10, option A), "
            + "so ROLE_SUPER_ADMIN here means a verified super-admin";
    private static final String DEPRECATED_ADAPTER =
        "RoleValidator.isSuperAdminFromAuth() is a deprecated adapter delegating to the verified signal";
    private static final String OWN_PIN =
        "Reads the pin the one resolver computed (non-sealing); equal to ActingScopeResolver.pinnedHospitalIdOf";

    /** One way around the resolver: a pattern, and the files allowed to match it with their counts. */
    private record Rule(String name, Pattern pattern, Map<String, Allowance> allowed) { }

    private record Allowance(int count, String reason) { }

    private static Map<String, Allowance> allow(Object... fileCountReason) {
        Map<String, Allowance> out = new TreeMap<>();
        for (int i = 0; i < fileCountReason.length; i += 3) {
            out.put("com/example/hms/" + fileCountReason[i],
                new Allowance((Integer) fileCountReason[i + 1], (String) fileCountReason[i + 2]));
        }
        return out;
    }

    private static final List<Rule> RULES = List.of(
        new Rule("raw read of the active hospital",
            Pattern.compile("getActiveHospitalId\\(\\)|HospitalContext::getActiveHospitalId"),
            allow(
                "security/tenant/ActingScopeResolver.java", 3, "The resolver itself",
                "security/TenantLifecycleGate.java", 1,
                "The lifecycle gate keeps its permitted-set semantics (design §3.6)",
                "service/LabResultServiceImpl.java", 1, LAB_PR,
                "service/LabTestDefinitionServiceImpl.java", 1, LAB_PR,
                "controller/LabTestDefinitionController.java", 1, LAB_PR)),
        new Rule("HospitalContext.pinnedHospitalId()",
            Pattern.compile("\\.pinnedHospitalId\\(\\)"),
            allow(
                "security/tenant/ActingScopeResolver.java", 2, "The resolver itself",
                "controller/PatientHospitalRegistrationController.java", 1, OWN_PIN,
                "service/BirthPlanServiceImpl.java", 2, OWN_PIN,
                "service/HighRiskPregnancyCarePlanServiceImpl.java", 2, OWN_PIN,
                "service/UserServiceImpl.java", 1, OWN_PIN,
                "service/impl/ImmunizationServiceImpl.java", 2, OWN_PIN,
                "service/impl/MaternalHistoryServiceImpl.java", 2, OWN_PIN,
                "service/impl/ObgynReferralServiceImpl.java", 1, OWN_PIN,
                "service/impl/UltrasoundServiceImpl.java", 1,
                OWN_PIN + "; the ultrasound service is owned by another PR of this batch")),
        new Rule("RoleValidator.isSuperAdminFromAuth()",
            Pattern.compile("(?<!boolean )isSuperAdminFromAuth\\(\\)"),
            allow(
                "service/DepartmentServiceImpl.java", 5, DEPRECATED_ADAPTER,
                "service/HospitalServiceImpl.java", 1, DEPRECATED_ADAPTER,
                "service/LabOrderServiceImpl.java", 1, DEPRECATED_ADAPTER + "; " + LAB_PR,
                "service/LabResultServiceImpl.java", 1, DEPRECATED_ADAPTER + "; " + LAB_PR,
                "service/PatientInsuranceServiceImpl.java", 1, DEPRECATED_ADAPTER,
                "service/PatientServiceImpl.java", 2, DEPRECATED_ADAPTER,
                "service/StaffSchedulingServiceImpl.java", 3, DEPRECATED_ADAPTER,
                "service/impl/NursingNoteServiceImpl.java", 2, DEPRECATED_ADAPTER)),
        new Rule("hasAuthority(auth, ROLE_SUPER_ADMIN)",
            Pattern.compile("hasAuthority\\((auth|authentication), (\"ROLE_SUPER_ADMIN\"|ROLE_SUPER_ADMIN)\\)"),
            allow(
                "controller/EncounterController.java", 12, RECONCILED_AUTHORITY,
                "controller/InBasketController.java", 2, RECONCILED_AUTHORITY,
                "controller/MedicationCatalogController.java", 1, RECONCILED_AUTHORITY,
                "controller/NurseTaskController.java", 1, RECONCILED_AUTHORITY,
                "controller/PharmacyRegistryController.java", 1, RECONCILED_AUTHORITY + "; " + LAB_PR,
                "service/impl/CdsAcknowledgementServiceImpl.java", 1,
                RECONCILED_AUTHORITY + "; CDS hooks are owned by another PR of this batch",
                "service/impl/PrescriptionSmsDispatchServiceImpl.java", 1,
                RECONCILED_AUTHORITY + "; the prescription services are owned by another PR of this batch")),
        new Rule("HospitalContext.isSuperAdmin() read locally",
            Pattern.compile("(ctx|context)\\.isSuperAdmin\\(\\)|getContextOrEmpty\\(\\)\\s*\\.isSuperAdmin\\(\\)"),
            allow(
                "security/tenant/ActingScopeResolver.java", 3, "The resolver itself",
                "security/TenantLifecycleGate.java", 1, "The lifecycle gate: a verified super-admin passes",
                "security/provider/ProviderConfinementPolicy.java", 1,
                "Provider confinement, a gate like the lifecycle one: a verified super-admin is never confined",
                "security/SuperAdminAuthorities.java", 1, "The Q10 reconciliation reads the verified signal",
                "fhir/FhirTenantBoundary.java", 2, VERIFIED_SIGNAL,
                "controller/SuperAdminDashboardController.java", 1, VERIFIED_SIGNAL,
                "service/AppointmentServiceImpl.java", 1, VERIFIED_SIGNAL,
                "service/HospitalServiceImpl.java", 2, VERIFIED_SIGNAL,
                "service/LabInstrumentServiceImpl.java", 1, VERIFIED_SIGNAL + "; " + LAB_PR,
                "service/LabInventoryServiceImpl.java", 1, VERIFIED_SIGNAL + "; " + LAB_PR,
                "service/LabResultServiceImpl.java", 1, VERIFIED_SIGNAL + "; " + LAB_PR,
                "service/PrenatalSchedulingServiceImpl.java", 1, VERIFIED_SIGNAL,
                "service/StaffServiceImpl.java", 2, VERIFIED_SIGNAL,
                "service/SuperAdminDashboardServiceImpl.java", 1, VERIFIED_SIGNAL,
                "service/impl/ConsultationServiceImpl.java", 1,
                VERIFIED_SIGNAL + "; the consultation service is owned by another PR of this batch",
                "service/impl/FeatureFlagServiceImpl.java", 1, VERIFIED_SIGNAL,
                "service/impl/OrganizationSecurityPolicyServiceImpl.java", 3, VERIFIED_SIGNAL,
                "service/support/PatientChartAccess.java", 1, VERIFIED_SIGNAL)),
        new Rule("findAllDetailedByUserId( (the \"newest assignment\" pick)",
            Pattern.compile("findAllDetailedByUserId\\("),
            allow(
                "repository/UserRoleHospitalAssignmentRepository.java", 1, "The query's declaration",
                "security/auth/JpaTenantRoleAssignmentAccessor.java", 1,
                "The producer's live read of every assignment",
                "controller/AuthController.java", 1,
                "The login response's primaryHospitalId: a UI hint for the chip's first value, never authorization",
                "controller/MeController.java", 1, "GET /me/assignments lists every assignment; it picks none",
                "service/DashboardConfigService.java", 1, "Builds the dashboard from every role; picks no hospital"))
    );

    @Test
    @DisplayName("every scope decision goes through the one resolver: the ways around it only shrink")
    void waysAroundTheResolverOnlyShrink() throws IOException {
        Map<String, String> sources = mainSources();
        List<String> failures = new ArrayList<>();
        for (Rule rule : RULES) {
            Map<String, Integer> found = new TreeMap<>();
            sources.forEach((file, source) -> {
                int count = count(rule.pattern(), source);
                if (count > 0) {
                    found.put(file, count);
                }
            });
            found.forEach((file, count) -> {
                Allowance allowance = rule.allowed().get(file);
                if (allowance == null) {
                    failures.add(rule.name() + ": " + file + " has " + count
                        + " — resolve through ActingScopeResolver instead (current(), requirePinned(), narrowTo())");
                } else if (count > allowance.count()) {
                    failures.add(rule.name() + ": " + file + " has " + count + ", allowed " + allowance.count()
                        + " — resolve the new one through ActingScopeResolver");
                }
            });
            rule.allowed().forEach((file, allowance) -> {
                int count = found.getOrDefault(file, 0);
                if (count < allowance.count()) {
                    failures.add(rule.name() + ": " + file + " now has " + count + ", allowed " + allowance.count()
                        + " — lower the allowance (or delete the entry): the list only shrinks");
                }
            });
        }
        assertThat(failures).as("ways around the one tenant resolver").isEmpty();
    }

    @Test
    @DisplayName("every allowance carries a reason")
    void everyAllowanceIsReasoned() {
        RULES.forEach(rule -> rule.allowed().forEach((file, allowance) ->
            assertThat(allowance.reason()).as("%s — %s", rule.name(), file).isNotBlank()));
        assertThat(RULES.get(0).allowed()).as("the resolver is on the raw-read list").containsKey(RESOLVER);
    }

    @Test
    @DisplayName("the counter sees a match on a code line and ignores one in a comment")
    void counterIgnoresComments() {
        Pattern raw = RULES.get(0).pattern();
        assertThat(count(raw, "UUID h = ctx.getActiveHospitalId();\n")).isEqualTo(1);
        assertThat(count(raw, "  // ctx.getActiveHospitalId() used to be read here\n")).isZero();
        assertThat(count(raw, "   * {@code getActiveHospitalId()} in javadoc\n")).isZero();
    }

    private static int count(Pattern pattern, String source) {
        int count = 0;
        for (String line : source.split("\n", -1)) {
            if (COMMENT_LINE.matcher(line).find()) {
                continue;
            }
            Matcher matcher = pattern.matcher(line);
            while (matcher.find()) {
                count++;
            }
        }
        return count;
    }

    /** Relative path (forward slashes) → source, for every main Java file outside security/context. */
    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(MAIN)) {
            for (Path path : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                String relative = MAIN.relativize(path).toString().replace('\\', '/');
                if (!relative.startsWith(CONTEXT_PACKAGE)) {
                    sources.put(relative, Files.readString(path, StandardCharsets.UTF_8));
                }
            }
        }
        return sources;
    }
}
