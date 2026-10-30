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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1066 guard (round 18): a cancelled order revived by a later status
 * write. The author could PUT an imaging order back to ORDERED or SCHEDULED
 * after cancelling it; it returned to the worklists still stamped cancelled
 * and the chronology read "Prescrit". Product decision (round-18 brief): a
 * cancelled order is closed; a clinician places a new one.
 *
 * <p>For every order type with a CANCELLED state, each method of its service
 * that writes a status other than CANCELLED (a literal like
 * {@code ImagingOrderStatus.ORDERED}, or one taken from the request) must
 * first refuse a cancelled order: {@code CancelledOrders.requireNotCancelled},
 * an explicit {@code == …CANCELLED} check, {@code isTerminal()}, a transition
 * table ({@code validateStatusTransition}), or a precondition that admits only
 * named live states ({@code getStatus() != X.REQUESTED}).
 */
@DisplayName("A cancelled order is never revived")
class CancelledOrderLockGuardTest {

    private static final Path SERVICES = Path.of("src/main/java/com/example/hms/service");

    /** Each order service and the status enum of its order. */
    private static final Map<String, String> ORDER_SERVICES = Map.ofEntries(
        Map.entry("impl/ImagingOrderServiceImpl.java", "ImagingOrderStatus"),
        Map.entry("impl/UltrasoundServiceImpl.java", "UltrasoundOrderStatus"),
        Map.entry("impl/ProcedureOrderServiceImpl.java", "ProcedureOrderStatus"),
        Map.entry("impl/TransfusionServiceImpl.java", "TransfusionRequestStatus"),
        Map.entry("impl/ConsultationServiceImpl.java", "ConsultationStatus"),
        Map.entry("LabOrderServiceImpl.java", "LabOrderStatus"),
        Map.entry("PrescriptionServiceImpl.java", "PrescriptionStatus"),
        Map.entry("impl/GeneralReferralServiceImpl.java", "ReferralStatus"),
        Map.entry("impl/ObgynReferralServiceImpl.java", "ObgynReferralStatus"),
        Map.entry("TreatmentPlanServiceImpl.java", "TreatmentPlanStatus"));

    private static final Pattern METHOD = Pattern.compile(
        // Possessive, one level of nested parentheses in the parameters (Sonar S8786: no backtracking).
        "\\n    (?:private|public|protected)[^;{=(]*+\\(((?:[^;{()\"]|\"[^\";{]*+\"|\\((?:[^;{()\"]|\"[^\";{]*+\")*+\\))*+)\\)\\s*+(?:throws [\\w., ]++)?+\\{");
    private static final Pattern NAME = Pattern.compile("(\\w++)\\s*+\\($");
    private static final Pattern SET_STATUS = Pattern.compile("(\\w+)\\.setStatus\\(");
    private static final Pattern LOCK = Pattern.compile(
        // A refusal of the cancelled state, not merely a mention of CANCELLED:
        // the D1066 code named it (wasCancelled / reopening) and still reopened.
        "requireNotCancelled\\(|isTerminal\\(|validateStatusTransition\\("
            + "|==\\s*\\w+Status\\.CANCELLED[^{;]*\\{\\s*throw"
            + "|getStatus\\(\\)\\s*!=\\s*\\w+Status\\.(?!CANCELLED\\b)\\w+"
            + "|\\b\\w*[sS]tatus\\s*!=\\s*\\w+Status\\.(?!CANCELLED\\b)\\w+");
    private static final List<String> NOT_THE_ORDER = List.of("dto", "response", "builder", "unit", "administration");

    /** Status writes judged safe without the check, each with why. Shrink only. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "UltrasoundServiceImpl#createOrder", 1, "a new order: it has no earlier status",
        "GeneralReferralServiceImpl#createReferral", 1, "a new referral: it has no earlier status",
        "LabOrderServiceImpl#applyRequestedStatus", 1,
        "the create branch only (an update ignores the requested status and points to the transition endpoint)",
        "PrescriptionServiceImpl#withdrawSignedPrescription", 1,
        "reached only for a SIGNED prescription (updatePrescription routes here on status == SIGNED); it moves "
            + "a signed order to a withdrawn status, never a cancelled one back");

    @Test
    @DisplayName("every non-cancel status write refuses a cancelled order first")
    void cancelledOrdersStayClosed() throws IOException {
        Map<String, Integer> actual = new TreeMap<>();
        int writes = 0;
        for (Map.Entry<String, String> entry : ORDER_SERVICES.entrySet()) {
            Path file = SERVICES.resolve(entry.getKey());
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            String service = file.getFileName().toString().replace(".java", "");
            List<String> found = offenders(service, source, entry.getValue());
            writes += statusWrites(source, entry.getValue());
            for (String site : found) {
                actual.merge(site, 1, Integer::sum);
            }
        }
        assertThat(writes).as("order status writes found").isGreaterThanOrEqualTo(20);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("an order status write that does not refuse a cancelled order first, so the order can be revived "
                + "(QA D1066): call CancelledOrders.requireNotCancelled(order.getStatus(), \"…cancelled.locked\")"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("the site was fixed: delete its line").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D1066 shape")
    void catchesTheOldShape() {
        String before = """
            class ImagingOrderServiceImpl {
                public ImagingOrderResponseDTO updateOrderStatus(UUID orderId, Request request) {
                    ImagingOrder order = getOrderInScope(orderId);
                    boolean wasCancelled = order.getStatus() == ImagingOrderStatus.CANCELLED;
                    boolean cancelling = request.getStatus() == ImagingOrderStatus.CANCELLED && !wasCancelled;
                    boolean reopening = wasCancelled && request.getStatus() != ImagingOrderStatus.CANCELLED;
                    if (cancelling || reopening) {
                        requireCancelAuthor(order);
                    }
                    order.setStatus(request.getStatus());
                    return map(order);
                }
            }
            """;
        assertThat(offenders("ImagingOrderServiceImpl", before, "ImagingOrderStatus"))
            .containsExactly("ImagingOrderServiceImpl#updateOrderStatus");
        String after = before.replace("ImagingOrder order = getOrderInScope(orderId);",
            "ImagingOrder order = getOrderInScope(orderId);\n"
                + "CancelledOrders.requireNotCancelled(order.getStatus(), \"imaging.order.cancelled.locked\");");
        assertThat(offenders("ImagingOrderServiceImpl", after, "ImagingOrderStatus")).isEmpty();
        String cancelOnly = """
            class X {
                public void cancel(UUID id) {
                    order.setStatus(ImagingOrderStatus.CANCELLED);
                }
            }
            """;
        assertThat(offenders("X", cancelOnly, "ImagingOrderStatus")).as("cancelling is not reviving").isEmpty();
    }

    private static int statusWrites(String source, String statusEnum) {
        int n = 0;
        Matcher m = SET_STATUS.matcher(source);
        while (m.find()) {
            if (revives(source, m, statusEnum)) {
                n++;
            }
        }
        return n;
    }

    static List<String> offenders(String service, String source, String statusEnum) {
        List<String> out = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingBrace(source, open);
            String body = source.substring(open, Math.min(close + 1, source.length()));
            Matcher write = SET_STATUS.matcher(body);
            boolean writesLiveStatus = false;
            while (write.find()) {
                if (revives(body, write, statusEnum)) {
                    writesLiveStatus = true;
                }
            }
            if (!writesLiveStatus || LOCK.matcher(body).find()) {
                continue;
            }
            String head = source.substring(m.start(), m.start(1));
            Matcher name = NAME.matcher(head);
            out.add(service + "#" + (name.find() ? name.group(1) : "?"));
        }
        return out;
    }

    /** A status write on the order (not a DTO, a blood unit…) to anything but CANCELLED. */
    private static boolean revives(String source, Matcher write, String statusEnum) {
        String receiver = write.group(1);
        if (NOT_THE_ORDER.stream().anyMatch(r -> receiver.toLowerCase(java.util.Locale.ROOT).contains(r))) {
            return false;
        }
        int argStart = write.end();
        int argEnd = SourceScan.closingParen(source, argStart - 1);
        String arg = source.substring(argStart, Math.max(argStart, argEnd)).trim();
        if (arg.matches("\\w+Status\\.\\w+")) {
            return arg.startsWith(statusEnum + ".") && !arg.endsWith(".CANCELLED");
        }
        return true;
    }
}
