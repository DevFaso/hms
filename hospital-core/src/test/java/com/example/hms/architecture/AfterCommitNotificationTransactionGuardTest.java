package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2927 (r66): a credit request wrote no notification, and no warning. The bell was written from an
 * after-commit callback with a plain save: after the commit the request's transaction is over but still bound to
 * the thread, so the save joined it and was never flushed. Nothing failed; nothing was stored.
 *
 * <p>The rule: a class whose after-commit callback writes a notification (in the callback itself, or in a method
 * of the class the callback calls) writes it in a transaction of its own (REQUIRES_NEW), as the lab and
 * break-glass notifiers already do. {@code NotificationServiceImpl} is the writer itself: its after-commit
 * callback only pushes the stored row over the socket.
 */
@DisplayName("A notification written after a commit has a transaction of its own")
class AfterCommitNotificationTransactionGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern CALLBACK = Pattern.compile("TransactionCallbacks\\s*\\.\\s*afterCommit\\s*\\(");
    private static final Pattern SYNCHRONIZATION = Pattern.compile("void\\s+afterCommit\\s*\\(\\s*\\)");
    private static final Pattern WRITES_A_NOTIFICATION = Pattern.compile(
        "\\.\\s*(?:createNotification|createLiteralNotification)\\s*\\(");
    private static final Pattern OWN_TRANSACTION = Pattern.compile("PROPAGATION_REQUIRES_NEW|Propagation\\.REQUIRES_NEW");
    private static final Pattern CALL = Pattern.compile("(?<![.\\w])([a-z]\\w*)\\s*\\(");

    @Test
    void anAfterCommitNotifierOpensItsOwnTransaction() throws IOException {
        List<String> afterCommitNotifiers = new ArrayList<>();
        List<String> joiningTheFinishedTransaction = new ArrayList<>();
        List<Path> sources;
        try (Stream<Path> files = Files.walk(MAIN)) {
            sources = files.filter(f -> f.toString().endsWith(".java"))
                .filter(f -> !f.endsWith("NotificationServiceImpl.java")).sorted().toList();
        }
        for (Path file : sources) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!notifiesAfterCommit(code)) {
                continue;
            }
            String relative = MAIN.relativize(file).toString().replace('\\', '/');
            afterCommitNotifiers.add(relative);
            if (!OWN_TRANSACTION.matcher(code).find()) {
                joiningTheFinishedTransaction.add(relative);
            }
        }

        assertThat(afterCommitNotifiers).as("the scan still finds the after-commit notifiers")
            .contains("service/pharmacy/DispenseBillingDeferralFollowUp.java",
                "service/lab/LabSpecimenRejectionNotifier.java");
        assertThat(joiningTheFinishedTransaction)
            .as("these write a notification after a commit with no transaction of their own: the row is never "
                + "stored (D2927). Write it in a REQUIRES_NEW transaction" + SourceScan.MANDATE)
            .isEmpty();
    }

    /** Whether an after-commit callback of this class writes a notification, itself or through a method it calls. */
    private static boolean notifiesAfterCommit(String code) {
        if (!WRITES_A_NOTIFICATION.matcher(code).find()) {
            return false;
        }
        for (String callback : callbacks(code)) {
            if (WRITES_A_NOTIFICATION.matcher(callback).find()) {
                return true;
            }
            Matcher call = CALL.matcher(callback);
            while (call.find()) {
                String body = SourceScan.methodBody(code, call.group(1));
                if (body != null && WRITES_A_NOTIFICATION.matcher(body).find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The text of every after-commit callback: the argument of the helper, and the body of a synchronization. */
    private static List<String> callbacks(String code) {
        List<String> callbacks = new ArrayList<>();
        Matcher helper = CALLBACK.matcher(code);
        while (helper.find()) {
            int open = helper.end() - 1;
            callbacks.add(code.substring(open, SourceScan.closingParen(code, open) + 1));
        }
        Matcher synchronization = SYNCHRONIZATION.matcher(code);
        while (synchronization.find()) {
            int brace = code.indexOf('{', synchronization.end());
            if (brace >= 0) {
                callbacks.add(code.substring(brace, SourceScan.closingBrace(code, brace) + 1));
            }
        }
        return callbacks;
    }
}
