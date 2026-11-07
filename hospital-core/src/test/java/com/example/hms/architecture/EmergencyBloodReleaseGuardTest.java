package com.example.hms.architecture;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Emergency blood release (user-supplied guidance 2026-11-06): how a bag can leave the lab, and what can
 * change a crossmatch result, are decided in one place. A second path past them is how an incompatible
 * bag goes out unrecorded, or a result gets relabelled by whoever released the bag.
 */
@DisplayName("Emergency blood release has one way out and leaves the result alone")
class EmergencyBloodReleaseGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Path SERVICE = MAIN.resolve("service/impl/TransfusionServiceImpl.java");
    private static final Path PORTAL_I18N = Paths.get("../hospital-portal/src/assets/i18n");
    private static final Path BUNDLES = Paths.get("src/main/resources/i18n");

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static List<String> filesContaining(String needle) throws IOException {
        List<String> hits = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (read(file).contains(needle)) {
                    hits.add(MAIN.relativize(file).toString().replace('\\', '/'));
                }
            }
        }
        return hits;
    }

    private static int count(String text, String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + needle.length())) {
            found++;
        }
        return found;
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).as(signature).isNotNegative();
        int end = source.indexOf("\n    }\n", start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("a bag reaches ISSUED in one line, reached by the two issue paths only")
    void oneWayOut() throws IOException {
        assertThat(filesContaining("setStatus(BloodUnitStatus.ISSUED)")).containsExactly("service/impl/TransfusionServiceImpl.java");
        String source = read(SERVICE);
        assertThat(count(source, "setStatus(BloodUnitStatus.ISSUED)")).isEqualTo(1);
        assertThat(method(source, "private BloodUnitResponseDTO putOut(")).contains("setStatus(BloodUnitStatus.ISSUED)");
        assertThat(count(source, "putOut(request, unit)")).as("issueUnit and emergencyRelease").isEqualTo(2);
        assertThat(method(source, "public BloodUnitResponseDTO issueUnit(")).contains("requireUncrossmatchedRelease(");
    }

    @Test
    @DisplayName("every way out checks the bag first and writes its release row")
    void everyWayOutIsRecorded() throws IOException {
        String source = read(SERVICE);
        String issue = method(source, "public BloodUnitResponseDTO issueUnit(");
        String release = method(source, "public BloodUnitResponseDTO emergencyRelease(");
        assertThat(issue).contains("issuableUnit(").contains("recordRelease(");
        assertThat(release).contains("issuableUnit(").contains("recordRelease(").contains("requireAnswerableAuthoriser(")
            .contains("requireIncompatibleReleaseAllowed(").contains("requireOPositiveDecisionAllowed(")
            .contains("fileReleaseReviewAfterCommit(");
    }

    @Test
    @DisplayName("a release never writes the crossmatch, and a verdict is set in one place, behind the rule that fixes it")
    void theResultIsLeftAlone() throws IOException {
        assertThat(filesContaining("setCompatible(")).containsExactly("service/impl/TransfusionServiceImpl.java");
        String source = read(SERVICE);
        assertThat(count(source, "setCompatible(")).isEqualTo(1);
        assertThat(method(source, "public BloodUnitResponseDTO emergencyRelease(")).doesNotContain("crossmatchRepository.save")
            .doesNotContain("setCompatible").doesNotContain("setIncompatibilityReason");
        assertThat(method(source, "private BloodUnitResponseDTO putOut(")).doesNotContain("crossmatch");
        String record = method(source, "public CrossmatchResponseDTO recordCrossmatch(");
        assertThat(record.indexOf("incompatibleResultIsFixed(")).as("checked before the verdict is written")
            .isNotNegative().isLessThan(record.indexOf("setCompatible("));
    }

    @Test
    @DisplayName("who may receive O positive is decided in EmergencyReleasePolicy and AboGroup, read by the service only")
    void theRulesLiveInOnePlace() throws IOException {
        assertThat(filesContaining("EmergencyReleasePolicy.oPositiveStep(")).containsExactly("service/impl/TransfusionServiceImpl.java");
        assertThat(filesContaining("AboGroup.documentedReleaseAllows(")).containsExactly("service/impl/TransfusionServiceImpl.java");
        assertThat(filesContaining("AboGroup.isEmergencyOPositive(")).containsExactly("service/impl/TransfusionServiceImpl.java");
        assertThat(filesContaining("AboGroup.emergencyReleaseRh()")).containsExactly("service/impl/TransfusionServiceImpl.java");
        assertThat(filesContaining("EXEMPT_FROM_AGE")).as("the platelet protocol's age is no emergency release age")
            .containsExactly("enums/ChildbearingPotential.java");
    }

    @Test
    @DisplayName("the action has its exact name in every language, and nothing anywhere says 'least incompatible'")
    void theName() throws IOException {
        assertThat(read(PORTAL_I18N.resolve("en.json"))).contains("\"INCOMPATIBLE_RELEASE\": \"Emergency release — serologically incompatible\"");
        assertThat(read(PORTAL_I18N.resolve("fr.json"))).contains("\"INCOMPATIBLE_RELEASE\": \"Délivrance d'urgence — sérologiquement incompatible\"");
        assertThat(read(PORTAL_I18N.resolve("es.json"))).contains("\"INCOMPATIBLE_RELEASE\": \"Entrega de urgencia — serológicamente incompatible\"");
        List<Path> texts = new ArrayList<>();
        try (Stream<Path> files = Files.list(PORTAL_I18N)) {
            texts.addAll(files.toList());
        }
        try (Stream<Path> files = Files.list(BUNDLES)) {
            texts.addAll(files.toList());
        }
        assertThat(texts).isNotEmpty();
        for (Path file : texts) {
            String text = read(file).toLowerCase(Locale.ROOT);
            assertThat(text).as(file.toString())
                .doesNotContain("least incompatible").doesNotContain("moins incompatible").doesNotContain("menos incompatible");
        }
    }
}
