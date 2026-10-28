package com.example.hms.architecture;

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
 * <p>Fails on any {@code save(...)} whose next statement is
 * {@code entityManager.refresh(x)} where {@code x} is the saved result or the
 * bare variable that was saved — whatever the save's argument looks like
 * ({@code save(existing)}, {@code save(mapper.apply(existing, request))}), and
 * whatever comments sit between the two. The pattern never has a legitimate
 * use: either drop the refresh, or {@code saveAndFlush} first when a
 * database-side default must be read back. The allowance below names the one
 * site the defect was reported on; it goes with the fix.
 */
@DisplayName("save() is never followed by a refresh() that discards the pending update")
class SaveThenRefreshGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    /** {@code [T saved =] repo.save(} or {@code this.repo.save(}: the `(` of the call. */
    static final Pattern SAVE = Pattern.compile("(?:(\\w+)\\s*=\\s*)?[\\w.]+\\.save\\(");

    /**
     * A refresh of {@code x} within the statements that follow the save, before any
     * flush — through the entity manager or one of the house helpers that wrap it
     * ({@code refreshIfManaged}, {@code resync}).
     */
    static final Pattern REFRESH = Pattern.compile(
        "(?:(?:entityManager|em)\\.refresh|refreshIfManaged|resync)\\(\\s*(\\w+)\\s*\\)");
    static final Pattern FLUSH = Pattern.compile("\\.(?:flush|saveAndFlush)\\(");

    /** Sites the defect was reported on. Shrink only: the fix deletes the line here too. */
    private static final Map<String, String> ALLOWED = Map.of(
        "service/AppointmentServiceImpl.java",
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
            Matcher refresh = REFRESH.matcher(rest);
            if (!refresh.find() || FLUSH.matcher(rest.substring(0, refresh.start())).find()) {
                continue;
            }
            String refreshed = refresh.group(1);
            String saved = m.group(1);
            if (refreshed.equals(argument) || refreshed.equals(saved)) {
                out.add(file + ":" + SourceScan.lineOf(source, m.start()) + ": save(" + argument + ") then refresh("
                    + refreshed + ") — the refresh re-reads the row and discards the pending update (QA D923)");
            }
        }
        return out;
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
        Map<String, List<String>> byFile = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(p).toString().replace('\\', '/');
                List<String> hits = offenders(rel, Files.readString(p, StandardCharsets.UTF_8));
                if (!hits.isEmpty()) {
                    byFile.put(rel, hits);
                }
            }
        }
        List<String> fresh = new ArrayList<>();
        byFile.forEach((file, hits) -> {
            if (!ALLOWED.containsKey(file)) {
                fresh.addAll(hits);
            } else if (hits.size() > 1) {
                fresh.add(file + ": " + hits.size() + " sites, 1 allowed — " + hits.get(1));
            }
        });
        assertThat(fresh)
            .as("drop the refresh, or saveAndFlush() before it when a database default must be read back"
                + SourceScan.MANDATE)
            .isEmpty();

        List<String> stale = new ArrayList<>();
        ALLOWED.forEach((file, reason) -> {
            if (!byFile.containsKey(file)) {
                stale.add(file + " (" + reason + ")");
            }
        });
        assertThat(stale)
            .as("the site was fixed: delete its allowance so the pattern cannot return" + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan catches the D923 shapes — bare and expression arguments, comments between — not a flush")
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
        assertThat(offenders("x", old)).hasSize(1);
        assertThat(offenders("x", plain)).hasSize(1);
        assertThat(offenders("x", expression)).hasSize(1);
        assertThat(offenders("x", statementBetween)).hasSize(1);
        assertThat(offenders("x", nestedBlockBetween)).hasSize(1);
        assertThat(offenders("x", qualifiedReceiver)).hasSize(1);
        assertThat(offenders("x", throughHelper)).hasSize(1);
        assertThat(offenders("x", flushed)).isEmpty();
        assertThat(offenders("x", unrelated)).isEmpty();
        assertThat(offenders("x", commented)).isEmpty();
        assertThat(offenders("x", flushBetween)).isEmpty();
        assertThat(offenders("x", nextBlock)).isEmpty();
    }
}
