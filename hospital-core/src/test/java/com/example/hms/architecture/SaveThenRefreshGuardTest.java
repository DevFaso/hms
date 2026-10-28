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

    /** A refresh of {@code x}, through the entity manager or a house helper. */
    static final Pattern REFRESH = Pattern.compile(
        "(?:(?:entityManager|em)\\.refresh|refreshIfManaged|resync)\\(\\s*(\\w+)\\s*\\)");
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
            // The rest of the ENCLOSING block — an if/try/lambda between the save and
            // the refresh is stepped over: a log line or a mapper call between the two
            // changes nothing about what the refresh discards.
            String rest = source.substring(semicolon + 1, endOfEnclosingBlock(source, semicolon + 1));
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
