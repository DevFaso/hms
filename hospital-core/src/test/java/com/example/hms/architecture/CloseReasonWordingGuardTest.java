package com.example.hms.architecture;

import com.example.hms.payload.dto.scheduling.CloseReasonRequestDTO;
import com.example.hms.service.support.CancelledOrders;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1267 (round 26): a guarantor deactivated and a birth plan withdrawn
 * without a reason were refused with "Indiquez le motif de l'annulation :
 * toute demande annulée en garde la raison" - the order-cancellation wording,
 * which names neither the action nor the record. Two roads led there: the
 * shared {@code CloseReasonRequestDTO} carried that message on its
 * {@code @NotBlank}, and the services called {@code requireCancelReason} for
 * actions that are not cancellations.
 *
 * <ul>
 *   <li>the cancellation rule ({@link CancelledOrders#requireCancelReason} or
 *       its key) is used only by a method that cancels (its name, or the
 *       reason it reads, says so); every other closing action -
 *       deactivate, withdraw, revoke, reject, close - calls
 *       {@link CancelledOrders#requireReason} with its own key;</li>
 *   <li>the shared close-reason body carries no message of its own on the
 *       reason.</li>
 * </ul>
 */
@DisplayName("D1267: a closing action that is not a cancellation never answers with the cancellation wording")
class CloseReasonWordingGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern CANCEL_RULE = Pattern.compile(
        "\\brequireCancelReason\\(([^;]*+)|\\bCANCEL_REASON_REQUIRED\\b|\"order\\.lifecycle\\.cancelReasonRequired\"");

    @Test
    @DisplayName("the cancellation wording is used only where something is cancelled")
    void theCancellationRuleIsOnlyUsedByACancellation() throws IOException {
        List<String> misused = new ArrayList<>();
        int uses = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (file.endsWith("CancelledOrders.java")) {
                    continue;
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = CANCEL_RULE.matcher(source);
                while (m.find()) {
                    String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, m.start());
                    String header = method.substring(0, method.indexOf('('));
                    String argument = m.group(1) == null ? "" : m.group(1);
                    uses++;
                    if (!header.toLowerCase(Locale.ROOT).contains("cancel")
                            && !argument.toLowerCase(Locale.ROOT).contains("cancel")) {
                        misused.add(file.getFileName() + ":" + SourceScan.lineOf(source, m.start()) + " in "
                            + header.trim());
                    }
                }
            }
        }
        assertThat(uses).as("uses of the cancellation rule read").isGreaterThanOrEqualTo(8);
        assertThat(misused)
            .as("this action is not a cancellation: CancelledOrders.requireReason(reason, field, <its own key>)"
                + " (QA D1267)" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the shared close-reason body carries no wording of one action")
    void theSharedCloseReasonBodyHasNoActionWording() throws NoSuchFieldException {
        Field reason = CloseReasonRequestDTO.class.getDeclaredField("reason");
        List<String> worded = new ArrayList<>();
        for (Annotation a : reason.getAnnotations()) {
            if (a.annotationType().isAnnotationPresent(jakarta.validation.Constraint.class)
                    && a.toString().contains("cancel")) {
                worded.add(a.toString());
            }
        }
        assertThat(worded).as("CloseReasonRequestDTO serves a cancel, a close, a revoke and a deactivate (QA D1267)")
            .isEmpty();
    }
}
