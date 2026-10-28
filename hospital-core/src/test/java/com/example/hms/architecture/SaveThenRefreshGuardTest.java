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
        "\\b(?:(?:entityManager|em)\\.refresh|refreshIfManaged|resync)\\(\\s*(?:this\\s*\\.\\s*)?(\\w+)\\s*(?:,[^()]*)?\\)");

    /** The keyword before a `( … ) {` that opens a control block, not a method body. */
    private static final Pattern CONTROL_KEYWORD = Pattern.compile(
        "\\b(?:if|for|while|switch|catch|synchronized|try|else|do)\\s*$|->\\s*$|\\bnew\\s+[\\w.<>]+\\s*$");
    /** A `return` statement inside the save's own block: what follows the block never runs after the save. */
    private static final Pattern RETURN = Pattern.compile("\\breturn\\b");
    /** A flush of the persistence context: the entity manager's, a repository's, or saveAndFlush — not a Writer's. */
    static final Pattern FLUSH = Pattern.compile(
        "\\b(?:(?:entityManager|em|\\w*[Rr]epository|\\w*Repo)\\s*\\.\\s*flush\\(|\\w+\\s*\\.\\s*saveAndFlush\\()");
    static final Pattern IDENTIFIER = Pattern.compile("\\b[a-z]\\w*\\b");

    /**
     * Sites the defect was reported on, keyed {@code "file save(argument) refresh(name)"}
     * — per SITE, not per file, so fixing D923 and adding another save-then-refresh in
     * the same file in one PR still fails. Shrink only: the fix deletes the line here too.
     */
    private static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow();
    // QA D923's site (AppointmentServiceImpl save(existing) refresh(saved)) was fixed by #972: none left.

    static List<String> offenders(String file, String rawSource) {
        String source = SourceScan.stripComments(rawSource);
        List<String> out = new ArrayList<>();
        Matcher m = SAVE.matcher(source);
        while (m.find()) {
            int open = m.end() - 1;
            int close = SourceScan.closingParen(source, open);
            // One line, single-spaced: a save argument that spans lines (a builder chain)
            // still makes a parseable message, and reformatting it does not change the key.
            String argument = source.substring(open + 1, close).strip().replaceAll("\\s+", " ");
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
            // Only a `return` that always runs counts: one at the block's own level that
            // starts a statement, not one inside a nested `if { … }`, a lambda or a
            // brace-less `if (x) return y;`.
            // A save inside a `return` statement (`if (isNew) return repo.save(fresh);`)
            // leaves the method itself: nothing after it ever runs after this save.
            boolean returnsTheSave =
                RETURN.matcher(source.substring(SourceScan.statementStart(source, m.start()), semicolon)).find();
            boolean leavesMethod = unconditional(source.substring(semicolon + 1, blockEnd), RETURN, true) >= 0;
            int windowEnd = returnsTheSave ? semicolon + 1
                : leavesMethod ? blockEnd : endOfEnclosingMethod(source, m.start(), semicolon + 1);
            String rest = source.substring(semicolon + 1, windowEnd);
            String saved = m.group(1);
            // Every refresh that follows is checked, not only the first: a refresh of
            // another row before the saved one does not clear the saved one.
            Matcher refresh = REFRESH.matcher(rest);
            // A flush clears the pending update only when it always runs before the
            // refresh: one inside an `if (audit) { em.flush(); }` does not.
            int flush = unconditional(rest, FLUSH, false);
            // `if (dirty) { save(x); } else { refresh(x); }`: the else chain of the save's
            // own `if` never runs after the save.
            int[] exclusive = elseChain(source, blockEnd);
            while (refresh.find()) {
                if (flush >= 0 && flush < refresh.start()) {
                    break;
                }
                int absolute = semicolon + 1 + refresh.start();
                if (exclusive != null && absolute > exclusive[0] && absolute < exclusive[1]) {
                    continue;
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

    /**
     * When the block closed at {@code blockEnd} is followed by {@code else} (an
     * {@code else if} chain included, braced or not): the range from that {@code else}
     * to the end of the chain. {@code null} otherwise. A {@code catch} is not
     * exclusive — the save may have run before the exception.
     */
    static int[] elseChain(String source, int blockEnd) {
        if (blockEnd >= source.length() || source.charAt(blockEnd) != '}') {
            return null;
        }
        int start = skipSpace(source, blockEnd + 1);
        if (!startsWord(source, start, "else")) {
            return null;
        }
        int end = start;
        int at = start;
        while (startsWord(source, at, "else")) {
            at = skipSpace(source, at + 4);
            if (startsWord(source, at, "if")) {
                int paren = source.indexOf('(', at);
                at = skipSpace(source, SourceScan.closingParen(source, paren) + 1);
            }
            if (at < source.length() && source.charAt(at) == '{') {
                end = SourceScan.closingBrace(source, at);
            } else {
                end = SourceScan.indexOfOutsideLiterals(source, ';', at);
            }
            if (end < 0) {
                return null;
            }
            at = skipSpace(source, end + 1);
        }
        return new int[] {start, end};
    }

    private static int skipSpace(String source, int at) {
        while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
            at++;
        }
        return at;
    }

    private static boolean startsWord(String source, int at, String word) {
        return source.startsWith(word, at)
            && (at + word.length() >= source.length() || !Character.isJavaIdentifierPart(source.charAt(at + word.length())));
    }

    /**
     * The offset of the first match of {@code pattern} in {@code text} that runs every
     * time {@code text} runs from its start: outside literals and comments, at the
     * lowest brace/paren depth reached so far (not inside a block, a lambda or an
     * argument opened after the start). With {@code statementStart}, the match must
     * also begin a statement (after `;`, `{`, `}` or the start), which rules out
     * `if (x) return y;` and `else return y;`. {@code -1} when there is none.
     */
    static int unconditional(String text, Pattern pattern, boolean statementStart) {
        int[] depthAt = new int[text.length()];
        int[] minBefore = new int[text.length()];
        boolean[] code = new boolean[text.length()];
        int depth = 0;
        int min = 0;
        for (int i = 0; i < text.length(); i++) {
            int skipped = SourceScan.endOfLiteralOrComment(text, i);
            if (skipped >= 0) {
                int last = Math.min(skipped, text.length() - 1);
                for (int k = i; k <= last; k++) {
                    depthAt[k] = depth;
                    minBefore[k] = min;
                }
                i = last;
                continue;
            }
            char ch = text.charAt(i);
            if (ch == '}' || ch == ')') {
                depth--;
                min = Math.min(min, depth);
            }
            depthAt[i] = depth;
            minBefore[i] = min;
            code[i] = true;
            if (ch == '{' || ch == '(') {
                depth++;
            }
        }
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            int at = m.start();
            if (!code[at] || depthAt[at] != minBefore[at]) {
                continue;
            }
            if (statementStart) {
                String before = text.substring(0, at).stripTrailing();
                if (!before.isEmpty() && "{};".indexOf(before.charAt(before.length() - 1)) < 0) {
                    continue;
                }
            }
            return at;
        }
        return -1;
    }

    /** {@code "file:12: save(existing) then refresh(saved) — …"} → {@code "file save(existing) refresh(saved)"}. */
    static String siteKey(String hit) {
        Matcher m = HIT.matcher(hit);
        if (!m.find()) {
            throw new IllegalStateException("unparseable hit: " + hit);
        }
        return m.group(1) + " save(" + m.group(2) + ") refresh(" + m.group(3) + ")";
    }

    private static final Pattern HIT =
        Pattern.compile("^(.+?):\\d+: save\\((.*)\\) then refresh\\((\\w+)\\) — ", Pattern.DOTALL);

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
        Map<String, Integer> perSite = new TreeMap<>();
        Map<String, String> lastHit = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                for (String hit : offenders(rel, Files.readString(p, StandardCharsets.UTF_8))) {
                    String site = siteKey(hit);
                    perSite.merge(site, 1, Integer::sum);
                    lastHit.put(site, hit);
                }
            }
        }
        List<String> fresh = new ArrayList<>();
        for (String over : GuardAllowlist.overAllowance(perSite, ALLOWED)) {
            fresh.add(over + " — " + lastHit.get(over.substring(0, over.indexOf(" ×"))));
        }
        assertThat(fresh)
            .as("drop the refresh, or saveAndFlush() before it when a database default must be read back"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(perSite, ALLOWED))
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
        // A return that only SOMETIMES runs does not end the window; neither does a
        // flush that only sometimes runs, nor a Writer's flush.
        String nestedReturn = """
                public AppointmentResponseDTO update(AppointmentRequestDTO d) {
                    if (changed) { appointmentRepository.save(existing); if (notify) { mailer.send(); return dto; } }
                    entityManager.refresh(existing);
                    return dto;
                }
            """;
        String bracelessReturn = """
                public AppointmentResponseDTO update(AppointmentRequestDTO d) {
                    if (changed) { appointmentRepository.save(existing); if (notify) return dto; }
                    entityManager.refresh(existing);
                    return dto;
                }
            """;
        String writerFlush = """
                Appointment saved = appointmentRepository.save(existing);
                csvWriter.flush();
                entityManager.refresh(saved);
            """;
        String conditionalFlush = """
                Appointment saved = appointmentRepository.save(existing);
                if (audit) { entityManager.flush(); }
                entityManager.refresh(saved);
            """;
        // The else chain of the save's own `if` never runs after the save; a refresh
        // after the whole if/else still does.
        String elseBranch = """
                public void sync(Appointment existing) {
                    if (dirty) { appointmentRepository.save(existing); }
                    else if (stale) entityManager.refresh(existing);
                    else { entityManager.refresh(existing); }
                }
            """;
        String afterTheElse = """
                public void sync(Appointment existing) {
                    if (dirty) { appointmentRepository.save(existing); } else { log.debug("clean"); }
                    entityManager.refresh(existing);
                }
            """;
        // A multi-line save argument keys and reports on one line; a refresh with a lock
        // mode or through `this.` is the same refresh.
        String multiLine = """
                Appointment saved = appointmentRepository.save(Appointment.builder()
                    .notes(n)
                    .build());
                entityManager.refresh(saved, LockModeType.PESSIMISTIC_READ);
            """;
        String thisField = """
                appointmentRepository.save(this.current);
                entityManager.refresh(this.current);
            """;
        assertThat(siteKey(offenders("service/A.java", multiLine).get(0)))
            .isEqualTo("service/A.java save(Appointment.builder() .notes(n) .build()) refresh(saved)");
        assertThat(offenders("x", thisField)).hasSize(1);
        String bracelessReturnedSave = """
                public Appointment upsert(Appointment fresh) {
                    if (isNew) return appointmentRepository.save(fresh);
                    entityManager.refresh(fresh);
                    return fresh;
                }
            """;
        assertThat(offenders("x", bracelessReturnedSave)).isEmpty();
        assertThat(offenders("x", elseBranch)).isEmpty();
        assertThat(offenders("x", afterTheElse)).hasSize(1);
        assertThat(offenders("x", nestedReturn)).hasSize(1);
        assertThat(offenders("x", bracelessReturn)).hasSize(1);
        assertThat(offenders("x", writerFlush)).hasSize(1);
        assertThat(offenders("x", conditionalFlush)).hasSize(1);
        assertThat(siteKey(offenders("service/A.java", writerFlush).get(0)))
            .isEqualTo("service/A.java save(existing) refresh(saved)");
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
