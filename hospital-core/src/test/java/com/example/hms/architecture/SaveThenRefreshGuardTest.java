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
 * QA D923 guard: an update that says it succeeded and discards every edit.
 *
 * <p>{@code repository.save(entity)} inside a transaction only schedules the
 * UPDATE; {@code entityManager.refresh(entity)} right after it re-reads the row
 * from the database and <em>overwrites the pending changes with the old
 * values</em>. {@code PUT /appointments/{id}} returned 200 with the untouched
 * appointment for every reschedule, three rounds in a row, with a service
 * test that mocked both calls and so could not see it.
 *
 * <p>Fails on any {@code save(...)} followed — anywhere in the rest of the
 * enclosing block, before any flush — by a refresh of the saved result, of the
 * saved argument, or of any identifier the argument names
 * ({@code save(mapper.apply(existing, request)); refresh(existing)}), through
 * the entity manager or the house helpers that wrap it ({@code resync},
 * {@code refreshIfManaged}). The pattern never has a legitimate use: either
 * drop the refresh, or {@code saveAndFlush} first when a database-side default
 * must be read back. The allowance below names the one site the defect was
 * reported on; it goes with the fix.
 */
@DisplayName("save() is never followed by a refresh() that discards the pending update")
class SaveThenRefreshGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** {@code [T saved =] repo.save(} or {@code this.repo.save(}: the `(` of the call. */
    static final Pattern SAVE = Pattern.compile("(?:(\\w+)\\s*=\\s*)?[\\w.]+\\.save\\(");

    /** A refresh of {@code x}, through the entity manager or a house helper ({@code item.refresh(view)} is neither). */
    static final Pattern REFRESH = Pattern.compile(
        "\\b(?:(?:entityManager|em)\\.refresh|refreshIfManaged|resync)\\(\\s*(\\w+)\\s*\\)");

    /** The keyword before a `( … ) {` that opens a control block, not a method body. */
    private static final Pattern CONTROL_KEYWORD = Pattern.compile(
        "\\b(?:if|for|while|switch|catch|synchronized|try|else|do)\\s*$|->\\s*$|\\bnew\\s+[\\w.<>]+\\s*$");
    /** A `return` statement inside the save's own block: what follows the block never runs after the save. */
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
    static final Pattern FLUSH = Pattern.compile("\\.(?:flush|saveAndFlush)\\(");
    static final Pattern IDENTIFIER = Pattern.compile("\\b[a-z]\\w*\\b");

    /** Sites the defect was reported on, per file. Shrink only: the fix deletes the line here too. */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "service/AppointmentServiceImpl.java", 1,
        "QA D923: updateAppointment refreshes the saved appointment before the flush; every PUT edit is discarded");

    static List<String> offenders(String file, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        List<String> out = new ArrayList<>();
        Matcher m = SAVE.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingParen(source, open);
            String argument = source.substring(open + 1, close).strip();
            int semicolon = SourceScan.indexOfOutsideLiterals(source, ';', close);
            if (semicolon < 0) {
                continue;
            }
            // The rest of the enclosing METHOD — an if/try/lambda between the save and the
            // refresh is stepped over, and a save inside an `if` is still followed by the
            // refresh after it: a log line or a mapper call between the two changes
            // nothing about what the refresh discards.
            // …unless the save's own block returns: `if (isNew) { return repo.save(fresh); }`
            // and a refresh after the block never run together, so only the block counts.
            int blockEnd = endOfEnclosingBlock(source, semicolon + 1);
            boolean leavesMethod =
                RETURN.matcher(source.substring(SourceScan.statementStart(source, m.start()), semicolon)).find()
                    || RETURN.matcher(source.substring(semicolon + 1, blockEnd)).find();
            int windowEnd = leavesMethod ? blockEnd : endOfEnclosingMethod(source, m.start(), semicolon + 1);
            String rest = source.substring(semicolon + 1, windowEnd);
            String saved = m.group(1);
            // Every refresh that follows is checked, not only the first: a refresh of
            // another row before the saved one does not clear the saved one.
            Matcher refresh = REFRESH.matcher(rest);
            while (refresh.find()) {
                if (FLUSH.matcher(rest.substring(0, refresh.start())).find()) {
                    break;
                }
                String refreshed = refresh.group(1);
                if (refreshed.equals(saved) || namesIdentifier(argument, refreshed)) {
                    out.add(file + ":" + SourceScan.lineOf(source, m.start()) + ": save(" + argument + ") then refresh("
                        + refreshed + ") — the refresh re-reads the row and discards the pending update (QA D923)");
                    break;
                }
            }
        }
        return out;
    }

    /** Whether {@code argument} is {@code name} or names it: {@code mapper.apply(existing, request)} names {@code existing}. */
    static boolean namesIdentifier(String argument, String name) {
        Matcher id = IDENTIFIER.matcher(argument);
        while (id.find()) {
            if (id.group().equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The index of the `}` that closes the method body containing {@code at}: the
     * innermost brace pair around it whose `{` follows a `)` (or a `throws` clause)
     * that is not an {@code if}/{@code for}/{@code try}/lambda/anonymous-class
     * opener. Brace walking, not indentation, so a parameter list with its own
     * parentheses or a nested class changes nothing. Falls back to the enclosing
     * block when no method surrounds {@code at} (a fixture with no method).
     */
    static int endOfEnclosingMethod(String source, int at, int from) {
        int best = -1;
        for (int i = 0; i < at; i++) {
            int skipped = SourceScan.endOfLiteralOrComment(source, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            if (source.charAt(i) != '{') {
                continue;
            }
            int close = SourceScan.closingBrace(source, i);
            if (close <= at || !opensMethodBody(source, i)) {
                continue;
            }
            // Innermost wins: later openers that still enclose `at` are nested deeper.
            best = close;
        }
        return best > from ? best : endOfEnclosingBlock(source, from);
    }

    /** Whether the `{` at {@code open} follows a parameter list (or `throws` clause) of a method, not a control block. */
    static boolean opensMethodBody(String source, int open) {
        String before = source.substring(Math.max(0, open - 400), open).stripTrailing();
        String head = before.replaceFirst("\\bthrows\\s+[\\w.,\\s<>]+$", "").stripTrailing();
        if (!head.endsWith(")")) {
            return false;
        }
        // The `(` that matches the final `)` — not the last `(`, which may belong to a
        // nested call (`if (x.isNew())`) or an annotation (`@RequestBody(required = false)`).
        int depth = 0;
        int paren = -1;
        for (int i = head.length() - 1; i >= 0; i--) {
            char ch = head.charAt(i);
            if (ch == ')') {
                depth++;
            } else if (ch == '(' && --depth == 0) {
                paren = i;
                break;
            }
        }
        if (paren < 0) {
            return false;
        }
        // The text before the parameter list: a method name, or a control keyword.
        String owner = head.substring(0, paren).stripTrailing();
        return !CONTROL_KEYWORD.matcher(owner).find() && owner.matches("(?s).*\\b\\w+$");
    }

    /** The index of the `}` that closes the block containing {@code from}; nested blocks are stepped over. */
    static int endOfEnclosingBlock(String source, int from) {
        int depth = 0;
        for (int i = from; i < source.length(); i++) {
            int skipped = SourceScan.endOfLiteralOrComment(source, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            char ch = source.charAt(i);
            if (ch == '{') {
                depth++;
            } else if (ch == '}' && depth-- == 0) {
                return i;
            }
        }
        return source.length();
    }

    @Test
    @DisplayName("no service saves an entity and refreshes it before the update is flushed")
    void noSaveThenRefresh() throws IOException {
        Map<String, Integer> perFile = new TreeMap<>();
        Map<String, String> lastHit = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                List<String> hits = offenders(rel, Files.readString(p, StandardCharsets.UTF_8));
                if (!hits.isEmpty()) {
                    perFile.put(rel, hits.size());
                    lastHit.put(rel, hits.get(hits.size() - 1));
                }
            }
        }
        List<String> fresh = new ArrayList<>();
        for (String over : GuardAllowlist.overAllowance(perFile, ALLOWED)) {
            fresh.add(over + " — " + lastHit.get(over.substring(0, over.indexOf(" ×"))));
        }
        assertThat(fresh)
            .as("drop the refresh, or saveAndFlush() before it when a database default must be read back"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(perFile, ALLOWED))
            .as("the site was fixed: delete its allowance so the pattern cannot return" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D923 shapes — any argument, any statements between, helpers — not a flush")
    void theScanCatchesTheOldShape() {
        String old = """
            Appointment saved = appointmentRepository.save(existing);
            entityManager.refresh(saved);
            """;
        String plain = """
            appointmentRepository.save(existing);
            // reload defaults
            /* and the trigger's columns */
            entityManager.refresh(existing);
            """;
        String expression = """
            Appointment saved = appointmentRepository.save(mapper.apply(existing, request));
            entityManager.refresh(saved);
            """;
        String expressionUnassigned = """
            appointmentRepository.save(mapper.apply(existing, request));
            entityManager.refresh(existing);
            """;
        String flushed = """
            Appointment saved = appointmentRepository.saveAndFlush(existing);
            entityManager.refresh(saved);
            """;
        String unrelated = """
            Appointment saved = appointmentRepository.save(existing);
            entityManager.refresh(lot);
            """;
        String commented = """
            Appointment saved = appointmentRepository.save(existing);
            // entityManager.refresh(saved);
            return saved;
            """;
        String statementBetween = """
            Appointment saved = appointmentRepository.save(existing);
            log.debug("rescheduled {}", id);
            webhooks.publish(saved.getId());
            entityManager.refresh(saved);
            """;
        String flushBetween = """
            Appointment saved = appointmentRepository.save(existing);
            entityManager.flush();
            entityManager.refresh(saved);
            """;
        String nextBlock = """
            void a() { Appointment saved = appointmentRepository.save(existing); }
            void b(Appointment saved) { entityManager.refresh(saved); }
            """;
        String nestedBlockBetween = """
            Appointment saved = appointmentRepository.save(existing);
            if (notify) { mailer.send(saved.getId()); }
            try { webhooks.publish(saved); } catch (RuntimeException ex) { log.warn("{}", ex.getMessage()); }
            entityManager.refresh(saved);
            """;
        String qualifiedReceiver = """
            Appointment saved = this.appointmentRepository.save(existing);
            entityManager.refresh(saved);
            """;
        String throughHelper = """
            Dispense saved = dispenseRepository.save(dispense);
            dispense = resync(dispense);
            refreshIfManaged(saved);
            """;
        String otherRefreshFirst = """
            Appointment saved = appointmentRepository.save(existing);
            entityManager.refresh(lot);
            entityManager.refresh(saved);
            """;
        String saveInsideIf = """
                public Appointment update(UUID id, AppointmentRequestDTO request) {
                    Appointment existing = load(id);
                    if (changed(existing, request)) { appointmentRepository.save(existing); }
                    entityManager.refresh(existing);
                    return existing;
                }

                public void other() { entityManager.refresh(existing); }
            """;
        String domainRefresh = """
            inventoryItemRepository.save(item);
            item.refresh(view);
            """;
        String returnInBranch = """
                public Appointment upsert(AppointmentRequestDTO dto) {
                    if (dto.isNew()) { return appointmentRepository.save(fresh); }
                    Appointment fresh = load(dto);
                    entityManager.refresh(fresh);
                    return fresh;
                }
            """;
        String parenParams = """
                public Appointment update(@Valid @RequestBody(required = false) AppointmentRequestDTO d) throws IOException {
                    if (changed(existing, d)) { appointmentRepository.save(existing); }
                    entityManager.refresh(existing);
                    return existing;
                }
            """;
        assertThat(offenders("x", saveInsideIf)).hasSize(1);
        assertThat(offenders("x", parenParams)).hasSize(1);
        assertThat(offenders("x", domainRefresh)).isEmpty();
        assertThat(offenders("x", returnInBranch)).isEmpty();
        assertThat(offenders("x", old)).hasSize(1);
        assertThat(offenders("x", plain)).hasSize(1);
        assertThat(offenders("x", expression)).hasSize(1);
        assertThat(offenders("x", expressionUnassigned)).hasSize(1);
        assertThat(offenders("x", statementBetween)).hasSize(1);
        assertThat(offenders("x", nestedBlockBetween)).hasSize(1);
        assertThat(offenders("x", qualifiedReceiver)).hasSize(1);
        assertThat(offenders("x", throughHelper)).hasSize(1);
        assertThat(offenders("x", otherRefreshFirst)).hasSize(1);
        assertThat(offenders("x", flushed)).isEmpty();
        assertThat(offenders("x", unrelated)).isEmpty();
        assertThat(offenders("x", commented)).isEmpty();
        assertThat(offenders("x", flushBetween)).isEmpty();
        assertThat(offenders("x", nextBlock)).isEmpty();
    }
}
