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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One woman, one active labour (QA D1631; labour transfer, 2026-11-12). Every
 * labour transition — a start, a transfer with a handover, an arrival without
 * one, a delivery — is decided under the patient row lock, on state read after
 * the lock, so two transitions racing for one woman cannot leave two active
 * episodes (or a delivered episode next to the one that took over from it).
 *
 * <p>Two checks:
 * <ul>
 *   <li>only LaborServiceImpl builds a labour episode, sets its status, or
 *       writes through LaborEpisodeRepository;</li>
 *   <li>in LaborServiceImpl, every public method that builds an episode or sets
 *       a status (directly or through the class's own helpers, expanded in
 *       place) takes the patient lock first, then reads the episode state it
 *       decides on, then makes the transition.</li>
 * </ul>
 * The partial unique index of V290 is the database's second line for the same
 * rule; H2 builds its tables from the entities and has no such index, so this
 * scan and the service tests are what CI sees.
 */
@DisplayName("Every labour transition is decided under the patient lock")
class LaborTransitionLockGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final String SERVICE = "service/impl/LaborServiceImpl.java";

    private static final Pattern TRANSITION = Pattern.compile("LaborEpisode\\.builder\\(\\)|\\.setStatus\\(LaborStatus\\.");
    private static final Pattern LOCK = Pattern.compile("patientRepository\\.lockByIdUnscoped\\(");
    /** A read of the committed episode state the transition decides on. */
    private static final Pattern STATE_READ = Pattern.compile(
        "episodeRepository\\.(?:findStatusById|existsByPatient_Id\\w*|findFirstByPatient_Id\\w*)\\(");
    private static final Pattern METHOD = Pattern.compile(
        "(public|private|protected)\\s+(?:static\\s+)?[\\w<>\\[\\], .?]+\\s+(\\w+)\\s*\\([^)]*\\)[^;{]*\\{");
    private static final Pattern REPOSITORY_FIELD = Pattern.compile("LaborEpisodeRepository\\s+(\\w+)\\s*[;,)=]");

    @Test
    @DisplayName("only LaborServiceImpl creates, transitions or writes a labour episode")
    void onlyTheLabourServiceTransitions() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = MAIN.relativize(file).toString().replace('\\', '/');
                if (name.equals(SERVICE)) {
                    continue;
                }
                offenders.addAll(outsideWrites(name, SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8))));
            }
        }
        assertThat(offenders).as("labour transitions belong to LaborServiceImpl, under the patient lock").isEmpty();
    }

    @Test
    @DisplayName("every transition in LaborServiceImpl locks the patient, then reads, then transitions")
    void everyTransitionLocksThenReads() throws IOException {
        String source = SourceScan.stripComments(Files.readString(MAIN.resolve(SERVICE), StandardCharsets.UTF_8));
        List<String> transitions = new ArrayList<>();
        List<String> offenders = unlockedTransitions(source, transitions);
        assertThat(transitions).as("the scan must see the transitions it is about")
            .contains("startEpisode", "transferEpisode", "recordArrivalWithoutHandover", "recordDelivery");
        assertThat(offenders).as("lock the patient, read the state, then transition (one active labour)").isEmpty();
    }

    @Test
    @DisplayName("the scan sees the shapes it is for")
    void scanSeesTheShapes() {
        String probe = """
            class P {
                public void locked() {
                    patientRepository.lockByIdUnscoped(id);
                    episodeRepository.findStatusById(id);
                    close(e);
                }
                public void unlocked() {
                    episodeRepository.findStatusById(id);
                    close(e);
                }
                public void readBeforeLock() {
                    episodeRepository.existsByPatient_IdAndStatus(id, ACTIVE);
                    patientRepository.lockByIdUnscoped(id);
                    repo.save(LaborEpisode.builder().build());
                }
                public void viaHelper() {
                    lock(id);
                    repo.save(LaborEpisode.builder().build());
                }
                private void lock(UUID id) {
                    patientRepository.lockByIdUnscoped(id);
                }
                private void close(LaborEpisode e) {
                    e.setStatus(LaborStatus.TRANSFERRED_OUT);
                }
            }
            """;
        List<String> transitions = new ArrayList<>();
        assertThat(unlockedTransitions(probe, transitions)).containsExactly("unlocked", "readBeforeLock", "viaHelper");
        assertThat(transitions).containsExactly("locked", "unlocked", "readBeforeLock", "viaHelper");
        assertThat(outsideWrites("probe.java", """
            class Q {
                private final LaborEpisodeRepository laborEpisodeRepository;
                void a() { laborEpisodeRepository.existsByPatient_IdAndStatus(id, ACTIVE); }
                void b() { laborEpisodeRepository.save(e); }
                void c() { e.setStatus(LaborStatus.CLOSED); }
            }
            """)).hasSize(2);
    }

    /** Public methods whose expanded body transitions without lock, then read, before it. */
    private static List<String> unlockedTransitions(String source, List<String> transitions) {
        Map<String, String> privateBodies = new HashMap<>();
        Map<String, String> publicBodies = new java.util.LinkedHashMap<>();
        Matcher method = METHOD.matcher(source);
        while (method.find()) {
            int open = method.end() - 1;
            String body = source.substring(open, SourceScan.closingBrace(source, open) + 1);
            ("public".equals(method.group(1)) ? publicBodies : privateBodies).put(method.group(2), body);
        }
        List<String> offenders = new ArrayList<>();
        publicBodies.forEach((name, body) -> {
            String expanded = expand(body, privateBodies, 0);
            Matcher transition = TRANSITION.matcher(expanded);
            if (!transition.find()) {
                return;
            }
            transitions.add(name);
            String before = expanded.substring(0, transition.start());
            Matcher lock = LOCK.matcher(before);
            if (!lock.find() || !STATE_READ.matcher(before.substring(lock.end())).find()) {
                offenders.add(name);
            }
        });
        return offenders;
    }

    /** The body with each call of the class's own private methods replaced by that method's body. */
    private static String expand(String body, Map<String, String> privateBodies, int depth) {
        if (depth > 4) {
            return body;
        }
        String result = body;
        for (Map.Entry<String, String> helper : privateBodies.entrySet()) {
            Pattern call = Pattern.compile("(?<![\\w.])" + Pattern.quote(helper.getKey()) + "\\(");
            Matcher found = call.matcher(result);
            StringBuilder out = new StringBuilder();
            while (found.find()) {
                found.appendReplacement(out, Matcher.quoteReplacement(
                    expand(helper.getValue(), privateBodies, depth + 1) + helper.getKey() + "("));
            }
            found.appendTail(out);
            result = out.toString();
        }
        return result;
    }

    private static List<String> outsideWrites(String name, String source) {
        List<String> offenders = new ArrayList<>();
        if (source.contains("LaborEpisode.builder()") || source.contains("new LaborEpisode(")) {
            offenders.add(name + ": builds a labour episode");
        }
        if (source.contains(".setStatus(LaborStatus.")) {
            offenders.add(name + ": sets a labour status");
        }
        Matcher field = REPOSITORY_FIELD.matcher(source);
        while (field.find()) {
            Pattern write = Pattern.compile("\\b" + Pattern.quote(field.group(1)) + "\\.(?:save\\w*|delete\\w*)\\(");
            if (write.matcher(source).find()) {
                offenders.add(name + ": writes through LaborEpisodeRepository");
            }
        }
        return offenders;
    }
}
