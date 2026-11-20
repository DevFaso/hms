package com.example.hms.architecture;

import com.example.hms.controller.RefillApprovalController;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.testsupport.SourceScan;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PutMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Policy rule 1 (provisional, owner decision 2026-11-20; QA r63 D2308): only an authorised prescriber approves the
 * renewal of a prescription. The Code de la santé publique (art. 274, 276) does not list pharmacists as prescribers:
 * the pharmacist reviews, flags, refuses to dispense and dispenses, and never approves. D2308 found Approuver /
 * Mettre en attente / Refuser shown to the pharmacist, and the route admitting them.
 *
 * <p>The class: every way a renewal request becomes APPROVED. There are two, and only two:
 * <ul>
 *   <li>a prescriber's decision, behind a role check that no pharmacy role passes;</li>
 *   <li>a refill the prescriber already wrote on the prescription, released by the pharmacy as ordinary dispensing,
 *       which is refused once the written refills are used.</li>
 * </ul>
 * A third writer of APPROVED, or a decision endpoint that admits a pharmacist, fails here.
 */
@DisplayName("No pharmacist-approved renewal (policy rule 1, QA D2308)")
class RenewalApprovalGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Set<String> PHARMACY_ROLES =
        Set.of("PHARMACIST", "PHARMACY_VERIFIER", "INVENTORY_CLERK", "STORE_MANAGER");

    @Test
    @DisplayName("the prescriber's decisions admit no pharmacist at the door; the pharmacy's steps decide nothing")
    void theDecisionEndpointsShutThePharmacistOut() {
        Map<String, String> gates = new TreeMap<>();
        for (Method method : RefillApprovalController.class.getDeclaredMethods()) {
            PutMapping mapping = method.getAnnotation(PutMapping.class);
            if (mapping != null && mapping.value().length > 0) {
                gates.put(mapping.value()[0], method.getAnnotation(PreAuthorize.class).value());
            }
        }
        assertThat(gates.keySet()).as("a new write on a renewal request: decide here who may make it")
            .containsExactly("/{refillId}/approve", "/{refillId}/pause", "/{refillId}/pharmacist-refuse",
                "/{refillId}/pharmacist-review", "/{refillId}/reject", "/{refillId}/release-as-prescribed");
        for (String decision : List.of("/{refillId}/approve", "/{refillId}/pause", "/{refillId}/reject")) {
            assertThat(PHARMACY_ROLES).as(decision + " admits a pharmacy role: " + gates.get(decision))
                .noneMatch(role -> gates.get(decision).contains("'" + role + "'"));
            assertThat(gates.get(decision)).contains("'DOCTOR'");
        }
        for (String pharmacy : List.of("/{refillId}/pharmacist-refuse", "/{refillId}/pharmacist-review",
                "/{refillId}/release-as-prescribed")) {
            assertThat(gates.get(pharmacy)).contains("'PHARMACIST'").doesNotContain("'DOCTOR'");
        }
    }

    @Test
    @DisplayName("who may approve: a physician any list, a nurse or a midwife a non-controlled drug, a pharmacist never")
    void thePrescriberRuleItself() {
        UUID user = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        for (String physician : List.of("DOCTOR", "PHYSICIAN", "SURGEON")) {
            assertThat(rolesOf(Set.of(physician)).canApproveRenewal(user, hospital, false)).isTrue();
            assertThat(rolesOf(Set.of(physician)).canApproveRenewal(user, hospital, true)).isTrue();
        }
        for (String limited : List.of("NURSE", "MIDWIFE")) {
            assertThat(rolesOf(Set.of(limited)).canApproveRenewal(user, hospital, false))
                .as(limited + " renews Lists I and II").isTrue();
            assertThat(rolesOf(Set.of(limited)).canApproveRenewal(user, hospital, true))
                .as(limited + " never renews a narcotic or controlled drug").isFalse();
        }
        for (String pharmacy : PHARMACY_ROLES) {
            assertThat(rolesOf(Set.of(pharmacy)).canApproveRenewal(user, hospital, false))
                .as(pharmacy + " approves a renewal").isFalse();
            assertThat(rolesOf(Set.of(pharmacy)).canApproveRenewal(user, hospital, true)).isFalse();
        }
        for (String other : List.of("HOSPITAL_ADMIN", "SUPER_ADMIN", "RECEPTIONIST", "ACCOUNTANT")) {
            assertThat(rolesOf(Set.of(other)).canApproveRenewal(user, hospital, false)).as(other).isFalse();
        }
        assertThat(rolesOf(Set.of("DOCTOR")).canApproveRenewal(user, null, false))
            .as("no hospital, no authority").isFalse();
        assertThat(rolesOf(Set.of("PHARMACIST")).canReviewRenewalAtPharmacy(user, hospital)).isTrue();
        assertThat(rolesOf(Set.of("DOCTOR")).canReviewRenewalAtPharmacy(user, hospital)).isFalse();
    }

    @Test
    @DisplayName("a request becomes APPROVED in one class, on two paths: the prescriber's decision and a written refill")
    void onlyTwoPathsApprove() throws IOException {
        List<String> writers = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (code.contains("setStatus(RefillStatus.APPROVED)")
                        || code.contains("decide(auth, refillId, decision, RefillStatus.APPROVED)")) {
                    writers.add(file.getFileName().toString());
                }
            }
        }
        assertThat(writers).as("a renewal request is approved outside the refill approval service" + SourceScan.MANDATE)
            .containsExactly("RefillApprovalServiceImpl.java");

        String service = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/impl/RefillApprovalServiceImpl.java"), StandardCharsets.UTF_8));
        String decide = between(service, "private MedicationRefillResponseDTO decide(", "private UUID requireAuthorisedPrescriber(");
        assertThat(decide).as("the prescriber's decision checks the caller is an authorised prescriber before it writes")
            .contains("requireAuthorisedPrescriber(auth, prescription, newStatus)")
            .contains("isDispensingRole()")
            .matches(body -> body.indexOf("requireAuthorisedPrescriber(") < body.indexOf("refill.setStatus(newStatus)"));
        assertThat(service).contains("roleValidator.canApproveRenewal(userId, hospitalId, controlled)");

        String release = between(service, "public MedicationRefillResponseDTO releaseAsPrescribed(",
            "static boolean hasWrittenRefillLeft(");
        assertThat(release).as("the pharmacy releases only a refill the prescriber wrote and that is not used yet")
            .contains("hasWrittenRefillLeft(prescription)")
            .matches(body -> body.indexOf("hasWrittenRefillLeft(prescription)") < body.indexOf("RefillStatus.APPROVED"));
    }

    private static RoleValidator rolesOf(Set<String> held) {
        UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(any(), any(), anySet())).thenAnswer(asked -> {
            Set<String> codes = asked.getArgument(2);
            return held.stream().anyMatch(role -> codes.contains(role) || codes.contains("ROLE_" + role));
        });
        return new RoleValidator(assignments, mock(ActingScopeResolver.class));
    }

    private static String between(String code, String from, String to) {
        int start = code.indexOf(from);
        int end = code.indexOf(to, start + from.length());
        assertThat(start).as("found: " + from).isNotNegative();
        assertThat(end).as("found: " + to).isGreaterThan(start);
        return code.substring(start, end + to.length());
    }
}
