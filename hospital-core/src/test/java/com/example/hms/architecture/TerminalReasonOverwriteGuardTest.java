package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
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
 * QA D1186 (round 22) guard: a close / cancel / revoke / discard / reject that
 * records why, but does not refuse a record already in that state - so a second
 * call answers 200 and overwrites the reason (the audit record of the first
 * decision). A closed waitlist entry was closed again with a new reason, while
 * the recall cancel next to it refused; the same hole was in the advance
 * directive revoke, the blood-unit discard, the diagnosis removal and the
 * portal proxy revoke.
 *
 * <p>Every method of production code (entities excepted: their transition
 * methods are read through the services that call them) that writes a close
 * reason - a {@code set…Reason(…)} setter of a closing action with a non-null
 * value, or {@code CancelledOrders.requireCancelReason(…)} - must, in its own
 * body, either
 * <ul>
 *   <li>read the terminal status it writes before writing it: the argument of
 *       its {@code setStatus(X)} appears again in the body outside that call
 *       ({@code if (entry.getStatus() == X) throw …}); or</li>
 *   <li>go through a recognised state precondition (a transition table, an
 *       {@code ensurePending}/{@code requireStatus}/{@code requireTransition}/
 *       {@code requireNotCancelled}/{@code assertInStatus}, an entity's
 *       {@code isTerminal()}/{@code isActive()}/{@code isPending()}/
 *       {@code isInProgress()}, an open-status set, a conditional claim); or</li>
 *   <li>be listed below with the reason its state check lives elsewhere.</li>
 * </ul>
 * A heuristic, not a proof. It catches the D1186 shapes (no status read at all;
 * the blood unit that read only TRANSFUSED, a non-terminal state). It is
 * satisfied by a comparison with ANY terminal-named state or by a
 * must-be-in-state ({@code != X}) check, and it reads only the method's own
 * body, so a wrong terminal state or a check in a caller needs the unit test
 * of that service (each D1186 fix has one).
 */
@DisplayName("A record closed with a reason cannot be closed again (the reason is never overwritten)")
class TerminalReasonOverwriteGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern METHOD = Pattern.compile(
        // Possessive, one level of nested parentheses in the parameters (Sonar S8786: no backtracking).
        "\\n    (?:(?:private|public|protected|static|final|synchronized)\\s++)++(?:[\\w<>,.?\\[\\]]++\\s++)++"
            + "(\\w++)\\s*+\\((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+\\)\\s*+(?:throws [\\w., ]++)?+\\{");

    /**
     * A close reason written with a value: not {@code null} (clearing it on a
     * re-open) and not another object's reason getter (a mapper copying it).
     */
    static final Pattern CLOSE_REASON_WRITE = Pattern.compile(
        "\\.set(?:Cancel|Cancellation|Close|Closure|Revocation|Revoke|Discard|Void|Rejection|Withdrawal"
            + "|Discontinuation|Stop|End|Decline|NoShow)Reason\\(\\s*+(?!null\\s*+\\))(?!\\w++\\.get\\w*Reason\\(\\)\\s*+\\))"
            + "|\\brequireCancelReason\\("
            // the closing stamp of a revoke / cancel (the portal proxy revoke re-stamped revokedAt)
            + "|\\.set(?:Revoked|Cancelled|Closed|Voided|Discarded)At\\(\\s*+(?!null\\s*+\\))(?!\\w++\\.get\\w*At\\(\\)\\s*+\\))"
            // a status change written with its reason (the diagnosis removal)
            + "|\\.setStatusChangeReason\\(\\s*+(?!null\\s*+\\))");

    private static final Pattern SET_STATUS = Pattern.compile("\\.setStatus\\(\\s*+([\\w.]++)\\s*+\\)");

    private static final String TERMINAL =
        "(?:CANCELLED|CLOSED|REVOKED|DISCARDED|VOIDED|REJECTED|WITHDRAWN|ENDED|COMPLETED|INACTIVE|EXPIRED)";

    /** Recognised state preconditions (see the class comment). */
    static final Pattern PRECONDITION = Pattern.compile(
        // a named precondition helper
        "\\b(?:ensurePending|requireStatus|requireTransition|requireNotCancelled|requireResultStaysClosed"
            + "|assertInStatus|requireActiveInTenant|requireValidStatusChange|validateStatusTransition"
            + "|claimTransition|transitionStatus)\\s*+\\("
            // an entity's own state question
            + "|\\.(?:isTerminal|isActive|isPending|isInProgress|isSigned)\\(\\)"
            // an allowed-state set or a transition table
            + "|\\b(?:[A-Z]++_)*+(?:STATUSES|TRANSITIONS|STATES)\\b|\\ballowed\\w*+\\.contains\\("
            + "|\\b(?:REJECTABLE|SUSPENDABLE|ARCHIVABLE|PURGE_SCHEDULABLE|RESTORABLE)\\b"
            // must-be-in-state: anything but X is refused
            + "|[Ss]tatus\\(\\)\\s*+!=|!(?:\\w++\\.)++equals\\((?:\\w++\\.)*+get(?:(?!Status\\()\\w)*+Status\\(\\)\\)"
            // the record is compared with a terminal state
            + "|[Ss]tatus\\(\\)\\s*+==\\s*+(?:\\w++\\.)*+" + TERMINAL + "\\b"
            // a closing timestamp still unset
            + "|get(?:(?!(?:Revoked|Cancelled|Closed|Ended|Voided)At\\()\\w)*+(?:Revoked|Cancelled|Closed|Ended|Voided)At\\(\\)\\s*+[!=]=\\s*+null"
            // a load restricted to a live status
            + "|\\bfind\\w*(?:AndStatus|IsNull)\\w*\\(");

    private static final String UPSTREAM = "QA D1186 audit (round 22): the state check is upstream - ";

    /**
     * Writers whose state check is not in their own body, per {@code "file#method"}.
     * Each reason names where the check is (verified by reading the code). Shrink only.
     */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "BedAssignmentService#cancelPendingTransfer", 1,
            UPSTREAM + "private; its rows come only from findPendingForAdmission (status = REQUESTED)",
        "BillingInvoiceServiceImpl#recordCancellation", 1,
            UPSTREAM + "private; called only when next == CANCELLED && current != CANCELLED (updateBillingInvoice)",
        "GeneralReferralServiceImpl#cancelReferral", 1,
            UPSTREAM + "GeneralReferral.cancel(...) throws on a terminal referral (isTerminal, in the entity)",
        "ObgynReferralServiceImpl#cancelReferral", 1,
            UPSTREAM + "ObgynReferral.cancel(...) throws on a terminal referral (isTerminal, in the entity)",
        "StaffSchedulingServiceImpl#cancelOverlappingShiftsForLeave", 1,
            UPSTREAM + "loads only active shifts (findActiveShiftsBetween excludes CANCELLED)",
        "StayOrderDiscontinuation#discontinueStayOrders", 1,
            UPSTREAM + "endsWithStay(rx, stay) requires one of ENDABLE_STATUSES before the order is touched",
        "TransfusionServiceImpl#stopInternal", 1,
            UPSTREAM + "private; both callers require administration.isInProgress()",
        "ApiKeyService#close", 1,
            UPSTREAM + "private; rotate and revoke load the key with requireActiveInTenant (status ACTIVE), plus @Version",
        "PatientRecallServiceImpl#finish", 1,
            UPSTREAM + "private; closeRecall and cancelRecall refuse a recall that is not open first",
        "PatientServiceImpl#handleDiagnosisChronicityChange", 1,
            "not a close: the chronic flag toggles, a same-value request is a no-op (request.getChronic() == problem.isChronic())");

    @Test
    @DisplayName("every close-with-reason refuses a record already closed, or says where it does")
    void closeReasonWritersCheckTheStateFirst() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        List<Path> javaFiles;
        try (Stream<Path> files = Files.walk(MAIN)) {
            javaFiles = files.filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.startsWith(MAIN.resolve("model")))
                .toList();
        }
        int writers = 0;
        for (Path file : javaFiles) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!CLOSE_REASON_WRITE.matcher(source).find()) {
                continue;
            }
            writers++;
            for (String site : offenders(file.getFileName().toString().replace(".java", ""), source)) {
                actual.merge(site, 1, Integer::sum);
            }
        }
        assertThat(writers).as("the scan found the close-reason writers").isGreaterThan(20);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a close / cancel / revoke with a reason that does not refuse a record already closed: "
                + "a second call overwrites the reason (QA D1186)" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed or removed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1186 shapes and accepts their fixes")
    void catchesTheD1186Shape() {
        String waitlistBefore = """
            class S {
                public void closeWaitlistEntry(UUID id, UUID hospitalId, String reason) {
                    AppointmentWaitlist entry = load(id, hospitalId);
                    entry.setCloseReason(CancelledOrders.requireCancelReason(reason));
                    entry.setStatus(WAITLIST_STATUS_CLOSED);
                    waitlistRepo.save(entry);
                }
            }
            """;
        assertThat(offenders("S", waitlistBefore)).containsExactly("S#closeWaitlistEntry");
        String waitlistAfter = waitlistBefore.replace("AppointmentWaitlist entry = load(id, hospitalId);",
            "AppointmentWaitlist entry = load(id, hospitalId);\n"
                + "        if (WAITLIST_STATUS_CLOSED.equals(entry.getStatus())) { throw new BusinessException(\"k\"); }");
        assertThat(offenders("S", waitlistAfter)).isEmpty();

        String discardBefore = """
            class S {
                public Dto discardUnit(UUID unitId, String reason) {
                    BloodUnit unit = load(unitId);
                    if (unit.getStatus() == BloodUnitStatus.TRANSFUSED) {
                        throw new BusinessException("transfusion.unit.alreadyTransfused");
                    }
                    unit.setStatus(BloodUnitStatus.DISCARDED);
                    unit.setDiscardReason(reason);
                    return map(unit);
                }
            }
            """;
        assertThat(offenders("S", discardBefore))
            .as("reading another status is not enough: the written one must be checked")
            .containsExactly("S#discardUnit");
        assertThat(offenders("S", discardBefore.replace("if (unit.getStatus() == BloodUnitStatus.TRANSFUSED) {",
            "if (unit.getStatus() == BloodUnitStatus.DISCARDED) { throw x; }\n        if (unit.getStatus() == BloodUnitStatus.TRANSFUSED) {")))
            .isEmpty();

        String reopen = """
            class S {
                public void reopen(Enrollment e) {
                    e.setStatus(ProgramEnrollmentStatus.ACTIVE);
                    e.setClosureReason(null);
                }
            }
            """;
        assertThat(offenders("S", reopen)).as("clearing a reason on a re-open is not a close").isEmpty();
    }

    static List<String> offenders(String file, String source) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            if (!CLOSE_REASON_WRITE.matcher(body).find() || PRECONDITION.matcher(body).find()
                    || writtenStatusIsChecked(body)) {
                continue;
            }
            out.add(file + "#" + m.group(1));
        }
        return out;
    }

    /** Some status this body writes is named again outside its {@code setStatus(…)}. */
    static boolean writtenStatusIsChecked(String body) {
        String rest = SET_STATUS.matcher(body).replaceAll("");
        Matcher s = SET_STATUS.matcher(body);
        while (s.find()) {
            String written = s.group(1);
            if (Pattern.compile("(?<![\\w.])" + Pattern.quote(written) + "(?![\\w])").matcher(rest).find()) {
                return true;
            }
        }
        return false;
    }
}
