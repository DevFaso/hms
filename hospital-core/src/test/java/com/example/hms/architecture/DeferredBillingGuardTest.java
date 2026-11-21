package com.example.hms.architecture;

import com.example.hms.controller.pharmacy.DispenseBillingDeferralController;
import com.example.hms.repository.UserRoleHospitalAssignmentRepository;
import com.example.hms.security.tenant.ActingScopeResolver;
import com.example.hms.testsupport.SourceScan;
import com.example.hms.utility.RoleValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Policy rule 6 (provisional, owner decision 2026-11-20; V345): « à facturer » is an exception somebody else
 * approves. The class this guards: a fill that leaves the pharmacy with money due on the word of the person handing
 * it over, with nobody behind it. #1239 gave the counter a reason box; the report asks for an approver.
 *
 * <p>Three things hold it, each checked against the code:
 * <ul>
 *   <li>a fill is marked deferred in ONE place, the deferral gate, which writes it only with a deferral row
 *       (approved by another person, an urgency override that finance reviews, or the open stay);</li>
 *   <li>a fill is completed on exactly two paths (the one-step fill and the hand-over) and both go through that
 *       gate; a third path fails here until it does too;</li>
 *   <li>nobody of the pharmacy approves or reviews: the roles are read from the role checks themselves.</li>
 * </ul>
 */
@DisplayName("No fill leaves « à facturer » without a deferral somebody else answers for (policy rule 6)")
class DeferredBillingGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final String GATE = "DispenseBillingDeferralService.java";
    private static final Pattern MARKS_A_FILL_DEFERRED = Pattern.compile(
        "\\.setBillingDeferredReason\\(|\\.setBillingDeferredBy\\(|\\.setBillingDeferredAt\\(|\\.setBillingDeferralId\\("
            + "|\\.setBillingDeferredAmount\\(");

    /**
     * Review of #1244 (HIGH): what takes money off a fill's charge at the counter, by name. A claim "sent to the
     * payer" used to be one, and a pharmacist alone could create and submit a claim for the whole charge: nothing
     * was due, so the gate was never asked. Every source is listed here with who stands behind it; a new one fails
     * until somebody names it and says who.
     */
    private static final java.util.Map<String, String> COVER_SOURCES = java.util.Map.of(
        "paymentRepository.sumPostedForDispense(", "money the checkout took and posted",
        "FillClaimCover.coveredFor(", "a claim a money desk, other than the dispenser, stands behind");

    /** A call that reads money or a claim for a fill: a repository read, or the claim-cover rule. */
    private static final Pattern COVER_READ = Pattern.compile(
        "\\b(?:\\w+Repository\\.(?:sum|find|count|exists)\\w*ForDispense\\(|\\w+Repository\\.findByDispense\\w*\\("
            + "|FillClaimCover\\.coveredFor\\()");
    private static final Pattern COMPLETES_A_PREPARED_FILL = Pattern.compile("\\.completePreparedFill\\(");

    /** The roles of the pharmacy: whoever holds only these never approves, grants or reviews. */
    private static final Set<String> PHARMACY_ROLES =
        Set.of("PHARMACIST", "PHARMACY_VERIFIER", "INVENTORY_CLERK", "STORE_MANAGER");

    @Test
    @DisplayName("only the deferral gate marks a fill deferred")
    void onlyTheGateMarksAFillDeferred() throws IOException {
        List<String> writers = new ArrayList<>();
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (MARKS_A_FILL_DEFERRED.matcher(code).find()) {
                writers.add(file.getFileName().toString());
            }
        }
        assertThat(writers)
            .as("a fill's « à facturer » columns are written outside the deferral gate: a fill could be marked "
                + "deferred with no deferral row, so with no approver (policy rule 6)" + SourceScan.MANDATE)
            .containsExactly(GATE);
    }

    @Test
    @DisplayName("both paths that complete a fill go through the gate, and there is no third")
    void everyCompletionGoesThroughTheGate() throws IOException {
        List<String> completers = new ArrayList<>();
        for (Path file : javaFiles()) {
            if (file.toString().replace('\\', '/').contains("/repository/")) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (COMPLETES_A_PREPARED_FILL.matcher(code).find()) {
                completers.add(file.getFileName().toString());
            }
        }
        assertThat(completers)
            .as("a new class hands a prepared fill over: it must pass the deferral gate too" + SourceScan.MANDATE)
            .containsExactly("DispenseServiceImpl.java");

        String service = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/pharmacy/DispenseServiceImpl.java"), StandardCharsets.UTF_8));
        String handOver = between(service, "public DispenseResponseDTO handOver(", "public DispenseResponseDTO cancelReady(");
        String oneStep = between(service, "public DispenseResponseDTO createDispenseTransactionally(",
            "dispenseRepository.save(dispense)");
        assertThat(handOver).as("the hand-over asks the gate before it completes the fill")
            .contains("requirePaidCoveredOrDeferred(")
            .matches(body -> body.indexOf("requirePaidCoveredOrDeferred(") < body.indexOf("completePreparedFill("));
        assertThat(oneStep).as("the one-step fill asks the gate before it is saved").contains("requireFreeOrDeferred(");
        assertThat(service).as("both reach the one gate").contains("billingDeferrals.admit(");
        assertThat(count(service, "billingDeferrals.admit(")).isEqualTo(1);
    }

    @Test
    @DisplayName("whoever holds only pharmacy roles approves, grants and reviews nothing")
    void thePharmacyNeverApproves() {
        UUID user = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        for (String role : PHARMACY_ROLES) {
            RoleValidator roles = rolesOf(Set.of(role));
            assertThat(roles.canApproveThirdPartyCover(user, hospital)).as(role + " checks a third-party cover").isFalse();
            assertThat(roles.canApproveOutpatientCredit(user, hospital)).as(role + " grants a credit").isFalse();
            assertThat(roles.canReviewDispenseBillingOverride(user, hospital)).as(role + " reviews an override").isFalse();
        }
        // Billing checks a voucher and never grants a credit alone; finance and the hospital admin do both.
        assertThat(rolesOf(Set.of("BILLING_SPECIALIST")).canApproveThirdPartyCover(user, hospital)).isTrue();
        assertThat(rolesOf(Set.of("BILLING_SPECIALIST")).canApproveOutpatientCredit(user, hospital)).isFalse();
        for (String role : List.of("ACCOUNTANT", "HOSPITAL_ADMIN")) {
            assertThat(rolesOf(Set.of(role)).canApproveThirdPartyCover(user, hospital)).isTrue();
            assertThat(rolesOf(Set.of(role)).canApproveOutpatientCredit(user, hospital)).isTrue();
            assertThat(rolesOf(Set.of(role)).canReviewDispenseBillingOverride(user, hospital)).isTrue();
        }
        // The override is the counter's: a pharmacist or a hospital admin, never a verifier or a clerk.
        assertThat(rolesOf(Set.of("PHARMACIST")).canOverrideDispenseBillingForUrgency(user, hospital)).isTrue();
        assertThat(rolesOf(Set.of("PHARMACY_VERIFIER")).canOverrideDispenseBillingForUrgency(user, hospital)).isFalse();
        assertThat(rolesOf(Set.of("SUPER_ADMIN")).canApproveOutpatientCredit(user, hospital))
            .as("never the super-admin alone").isFalse();
    }

    @Test
    @DisplayName("the decision endpoints admit no pharmacy role at the door")
    void theDecisionEndpointsShutThePharmacyOut() {
        Set<String> decided = new TreeSet<>();
        for (Method method : DispenseBillingDeferralController.class.getDeclaredMethods()) {
            PostMapping mapping = method.getAnnotation(PostMapping.class);
            if (mapping == null || mapping.value().length == 0) {
                continue;
            }
            String path = mapping.value()[0];
            if (path.endsWith("/approve") || path.endsWith("/reject") || path.endsWith("/review")) {
                decided.add(path);
                String gate = method.getAnnotation(PreAuthorize.class).value();
                assertThat(PHARMACY_ROLES).as(path + " admits a pharmacy role: " + gate)
                    .noneMatch(role -> gate.contains("'" + role + "'"));
            }
        }
        assertThat(decided).containsExactly("/{id}/approve", "/{id}/reject", "/{id}/review");
    }

    /** A validator over a user who holds exactly {@code held} at the hospital asked about. */
    private static RoleValidator rolesOf(Set<String> held) {
        UserRoleHospitalAssignmentRepository assignments = mock(UserRoleHospitalAssignmentRepository.class);
        when(assignments.existsActiveByUserAndHospitalAndAnyRoleCode(any(), any(), anySet())).thenAnswer(asked -> {
            Set<String> codes = asked.getArgument(2);
            return held.stream().anyMatch(role -> codes.contains(role) || codes.contains("ROLE_" + role));
        });
        return new RoleValidator(assignments, mock(ActingScopeResolver.class));
    }

    private static List<Path> javaFiles() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            return files.filter(f -> f.toString().endsWith(".java")).toList();
        }
    }

    private static String between(String code, String from, String to) {
        int start = code.indexOf(from);
        int end = code.indexOf(to, start + from.length());
        assertThat(start).as("found: " + from).isNotNegative();
        assertThat(end).as("found: " + to).isGreaterThan(start);
        return code.substring(start, end + to.length());
    }

    private static int count(String code, String what) {
        int n = 0;
        for (int at = code.indexOf(what); at >= 0; at = code.indexOf(what, at + what.length())) {
            n++;
        }
        return n;
    }

    @Test
    @DisplayName("review of #1244: every source of cover at the counter is named, and a claim counts only through the claim-cover rule")
    void everyCoverSourceIsNamed() throws IOException {
        String service = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/pharmacy/DispenseServiceImpl.java"), StandardCharsets.UTF_8));
        String settlement = between(service, "private FillSettlement settlementOf(", "return new FillSettlement(");
        Set<String> read = new TreeSet<>();
        java.util.regex.Matcher calls = COVER_READ.matcher(settlement);
        while (calls.find()) {
            read.add(calls.group());
        }
        assertThat(read)
            .as("the counter takes something off a fill's charge that this guard does not name: say what it is "
                + "and who stands behind it (a claim the pharmacist sent alone covered a whole fill)"
                + SourceScan.MANDATE)
            .containsExactlyInAnyOrderElementsOf(COVER_SOURCES.keySet());
        assertThat(service).as("what is owed is the charge less the named cover, nothing else")
            .contains("record FillSettlement(BigDecimal charge, BigDecimal paid, BigDecimal claimed)");

        // The bare sum of claims "sent" is gone, and nobody but the claim-cover rule reads a fill's claims by state.
        List<String> rawSum = new ArrayList<>();
        List<String> claimReaders = new ArrayList<>();
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains("sumSentForDispense")) {
                rawSum.add(file.getFileName().toString());
            }
            if (code.contains(".findByDispenseIdAndClaimStatusIn(")) {
                claimReaders.add(file.getFileName().toString());
            }
        }
        assertThat(rawSum).as("a claim sent is not cover by itself").isEmpty();
        assertThat(claimReaders).containsExactly("FillClaimCover.java");

        // The reconciliation and the « still due » balance ask the same question as the gate.
        for (String sameQuestion : List.of("service/pharmacy/PharmacyReconciliationService.java",
                "service/pharmacy/DeferredFillBalance.java")) {
            String code = SourceScan.stripComments(Files.readString(MAIN.resolve(sameQuestion), StandardCharsets.UTF_8));
            assertThat(code).as(sameQuestion).contains("FillClaimCover.coveredFor(").doesNotContain("sumLiveForDispense(");
        }
    }

    @Test
    @DisplayName("review round 2 of #1244: cover reads only what was recorded, never today's roles nor who touched the claim last")
    void coverReadsOnlyWhatWasRecorded() throws IOException {
        String cover = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/pharmacy/FillClaimCover.java"), StandardCharsets.UTF_8));
        assertThat(cover).as("the cover reads the vouchers recorded on the claim").contains("getVouchedBy()",
            "getSecondVouchedBy()");
        // A billing clerk who left the hospital, or a pharmacist recording the insurer's payment after the
        // hand-over, turned fills of closed days into unpaid differences: a live fact was read here.
        for (String live : List.of("RoleValidator", "AssignmentRepository", "vouchesForClaimCover", "hasAnyCode",
                "getSubmittedByUser", "getUpdatedBy", "getCurrentUserId", "SecurityContextHolder")) {
            assertThat(cover).as("FillClaimCover reads a live fact (" + live + "): the cover of a closed day would "
                + "move when a role or the claim's last actor changes; record the fact in ClaimVouching when it "
                + "happens and read the stored column" + SourceScan.MANDATE).doesNotContain(live);
        }

        // One place asks the role rule (when somebody acts on a claim), one place writes the vouchers, each once.
        List<String> askTheRoleRule = new ArrayList<>();
        List<String> writeAVoucher = new ArrayList<>();
        for (Path file : javaFiles()) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (code.contains(".vouchesForClaimCover(")) {
                askTheRoleRule.add(file.getFileName().toString());
            }
            if (Pattern.compile("\\.(?:setVouchedBy|setSecondVouchedBy|setVouchedAt|setSecondVouchedAt|vouchedBy|"
                    + "secondVouchedBy)\\(").matcher(code).find()) {
                writeAVoucher.add(file.getFileName().toString());
            }
        }
        assertThat(askTheRoleRule).as("who holds a money desk is asked only when a claim is sent or answered")
            .containsExactly("ClaimVouching.java");
        assertThat(writeAVoucher).as("a claim's vouchers are written only by ClaimVouching").containsExactly(
            "ClaimVouching.java");
        String vouching = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/pharmacy/ClaimVouching.java"), StandardCharsets.UTF_8));
        assertThat(count(vouching, ".setVouchedBy(")).as("each voucher is written in one place").isEqualTo(1);
        assertThat(count(vouching, ".setSecondVouchedBy(")).isEqualTo(1);
        assertThat(vouching).as("a voucher is written only while its slot is empty: nobody is ever erased")
            .contains("if (claim.getVouchedBy() == null) {")
            .contains("} else if (claim.getSecondVouchedBy() == null && !claim.getVouchedBy().equals(actorId)) {")
            .doesNotContain("(null)");

        // Sending a claim and recording its acceptance or payment are the moments it is recorded.
        String claims = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/pharmacy/PharmacyClaimServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(count(claims, "ClaimVouching.recordVoucher(")).isEqualTo(2);
        assertThat(SourceScan.methodBody(claims, "submitClaim")).contains("ClaimVouching.recordVoucher(");
        assertThat(SourceScan.methodBody(claims, "transition")).contains("ClaimVouching.recordVoucher(");
    }

    @Test
    @DisplayName("QA D2925 (r66): the payer's decision on a claim is recorded by a money desk or a hospital admin, never the pharmacy nor the super-admin alone")
    void thePharmacyNeverRecordsThePayersDecision() throws IOException {
        UUID user = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        for (String role : PHARMACY_ROLES) {
            assertThat(rolesOf(Set.of(role)).canRecordPayerDecision(user, hospital)).as(role).isFalse();
        }
        for (String role : List.of("BILLING_SPECIALIST", "CLAIMS_REVIEWER", "ACCOUNTANT", "HOSPITAL_ADMIN")) {
            assertThat(rolesOf(Set.of(role)).canRecordPayerDecision(user, hospital)).as(role).isTrue();
        }
        assertThat(rolesOf(Set.of("SUPER_ADMIN")).canRecordPayerDecision(user, hospital)).isFalse();
        assertThat(rolesOf(Set.of("BILLING_SPECIALIST")).canRecordPayerDecision(user, null)).isFalse();

        // Accepted, rejected and paid all go through the one transition, which asks before it writes anything.
        String claims = SourceScan.stripComments(Files.readString(
            MAIN.resolve("service/pharmacy/PharmacyClaimServiceImpl.java"), StandardCharsets.UTF_8));
        String transition = SourceScan.methodBody(claims, "transition");
        assertThat(transition).contains("requirePayerDecisionDesk(claim)");
        assertThat(transition.indexOf("requirePayerDecisionDesk(claim)"))
            .as("the desk is asked before the claim changes state").isLessThan(transition.indexOf("setClaimStatus("));
        for (String decision : List.of("markAccepted", "markRejected", "markPaid")) {
            assertThat(SourceScan.methodBody(claims, decision)).as(decision).contains("transition(");
        }
        assertThat(SourceScan.methodBody(claims, "requirePayerDecisionDesk"))
            .contains("canRecordPayerDecision(", "claim.getHospital()");
    }

    @Test
    @DisplayName("review of #1244: who stands behind a claim as cover is a money desk or a hospital admin, never the pharmacy")
    void thePharmacyStandsBehindNoClaim() {
        UUID user = UUID.randomUUID();
        UUID hospital = UUID.randomUUID();
        for (String role : PHARMACY_ROLES) {
            assertThat(rolesOf(Set.of(role)).vouchesForClaimCover(user, hospital)).as(role).isFalse();
        }
        for (String role : List.of("BILLING_SPECIALIST", "CLAIMS_REVIEWER", "ACCOUNTANT", "HOSPITAL_ADMIN")) {
            assertThat(rolesOf(Set.of(role)).vouchesForClaimCover(user, hospital)).as(role).isTrue();
        }
        assertThat(rolesOf(Set.of("SUPER_ADMIN")).vouchesForClaimCover(user, hospital)).isFalse();
        assertThat(rolesOf(Set.of("BILLING_SPECIALIST")).vouchesForClaimCover(user, null)).isFalse();
        // The verifier reads the counter view; it approves, grants and reviews nothing (checked above).
        assertThat(rolesOf(Set.of("PHARMACY_VERIFIER")).canReadDispenseBillingCounter(user, hospital)).isTrue();
        assertThat(rolesOf(Set.of("RECEPTIONIST")).canReadDispenseBillingCounter(user, hospital)).isFalse();
    }
}
