package com.example.hms.i18n;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2101 (round 59): a refusal that names the wrong action.
 *
 * <p>A super-admin refused when REMOVING a price-list line from a draft invoice was told
 * « …peut modifier un prix de la grille »: the price-override check was a private
 * {@code requireTariffWriter} that threw the override message, and the deletion path reused it.
 * The refusal was right; the sentence described another action.
 *
 * <p>Rule, over every class of {@code src/main/java}: a private {@code void} helper that throws an
 * access refusal with a literal message key ({@code new ClientSafeAccessDeniedException(… "key" …)})
 * may be called from ONE method only. A check shared by several actions returns a boolean, and each
 * caller refuses with the message that names its own action. Helpers whose callers are all the same
 * action, or whose message names no action, are listed in {@link #SAME_ACTION}, each with why.
 */
@DisplayName("A shared access check never refuses with one action's message (QA D2101)")
class SharedRefusalHelperGuardTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static final Pattern HELPER = Pattern.compile(
        "private\\s+(?:static\\s+)?void\\s+(\\w+)\\s*\\([^)]*\\)\\s*\\{");
    private static final Pattern KEYED_REFUSAL = Pattern.compile(
        "new\\s+ClientSafeAccessDeniedException\\s*\\([^;]*\"[a-z][\\w]*\\.[\\w.]+\"", Pattern.DOTALL);
    private static final Pattern METHOD = Pattern.compile(
        "(?m)^\\s*(?:@\\w+(?:\\([^)]*\\))?\\s*)*(?:public|protected|private)\\s[^;=(]*?\\b(\\w+)\\s*\\([^;{]*?\\)"
            + "\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

    /**
     * Helpers whose message fits every caller: {@code File#helper} → why (the same action everywhere, or a
     * message that names no action).
     */
    private static final Map<String, String> SAME_ACTION = Map.of(
        "ConsultationServiceImpl#requireAssignedConsultant",
        "consultation.notAssignedConsultant names no action: « …peut effectuer cette action »",
        "ConsultationServiceImpl#requireCoordinator",
        "every caller assigns a consultant: assign, acknowledging an unaddressed consult (which makes the caller"
            + " its consultant) and an update that changes the consultant; the message says « assign »");

    @Test
    @DisplayName("QA D2101: a private refusal helper with a literal message serves one action")
    void everySharedRefusalNamesItsAction() throws IOException {
        List<String> found = new ArrayList<>();
        int files = 0;
        try (Stream<Path> tree = Files.walk(MAIN)) {
            for (Path file : tree.filter(p -> p.toString().endsWith(".java")).toList()) {
                files++;
                String source = Files.readString(file, StandardCharsets.UTF_8);
                String name = file.getFileName().toString().replace(".java", "");
                for (String shared : sharedRefusalHelpers(source)) {
                    if (!SAME_ACTION.containsKey(name + "#" + shared)) {
                        found.add(name + "#" + shared);
                    }
                }
            }
        }
        assertThat(files).as("the scan must read the main tree").isGreaterThan(500);
        assertThat(found)
            .as("return a boolean from the shared check and refuse in each caller with its own message key")
            .isEmpty();
    }

    @Test
    @DisplayName("QA D2101 guard: the scan sees the shared tariff check and passes the split one")
    void theScanSeesTheShape() {
        String shared = """
            class S {
                public void update(Invoice i) { requireTariffWriter(i); }
                public void delete(Invoice i) { requireTariffWriter(i); }
                private void requireTariffWriter(Invoice invoice) {
                    if (!ok) {
                        throw new ClientSafeAccessDeniedException(
                            MessageUtil.resolve("billing.item.overrideNotAllowed"));
                    }
                }
            }
            """;
        assertThat(sharedRefusalHelpers(shared)).containsExactly("requireTariffWriter");
        String split = """
            class S {
                public void update(Invoice i) {
                    if (!isTariffWriter(i)) throw new ClientSafeAccessDeniedException(MessageUtil.resolve("a.b"));
                }
                public void delete(Invoice i) {
                    if (!isTariffWriter(i)) throw new ClientSafeAccessDeniedException(MessageUtil.resolve("a.c"));
                }
                private boolean isTariffWriter(Invoice invoice) { return ok; }
            }
            """;
        assertThat(sharedRefusalHelpers(split)).isEmpty();
        String single = """
            class S {
                public void update(Invoice i) { requireX(i); requireX(i); }
                private void requireX(Invoice invoice) {
                    throw new ClientSafeAccessDeniedException(MessageUtil.resolve("a.b"));
                }
            }
            """;
        assertThat(sharedRefusalHelpers(single)).isEmpty();
    }

    /** The private refusal helpers of a source called from more than one method. */
    static Set<String> sharedRefusalHelpers(String source) {
        Set<String> shared = new TreeSet<>();
        Matcher helper = HELPER.matcher(source);
        while (helper.find()) {
            String name = helper.group(1);
            int bodyStart = helper.end() - 1;
            String body = source.substring(bodyStart, closingBrace(source, bodyStart) + 1);
            if (!KEYED_REFUSAL.matcher(body).find()) {
                continue;
            }
            Set<String> callers = new TreeSet<>();
            Matcher call = Pattern.compile("\\b" + name + "\\s*\\(").matcher(source);
            while (call.find()) {
                if (call.start() >= helper.start() && call.start() < helper.end()) {
                    continue;
                }
                String caller = enclosingMethod(source, call.start());
                if (caller != null && !caller.equals(name)) {
                    callers.add(caller);
                }
            }
            if (callers.size() > 1) {
                shared.add(name);
            }
        }
        return shared;
    }

    private static String enclosingMethod(String source, int index) {
        String enclosing = null;
        Matcher method = METHOD.matcher(source);
        while (method.find() && method.start() < index) {
            int open = method.end() - 1;
            if (closingBrace(source, open) > index) {
                enclosing = method.group(1);
            }
        }
        return enclosing;
    }

    private static int closingBrace(String source, int open) {
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                return i;
            }
        }
        return source.length() - 1;
    }
}
