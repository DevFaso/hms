package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2127 (round 59): {@code GET /panels/my} refused a French reader with an English sentence
 * (« You have no staff profile at the active hospital… »): the panel service threw free text, which
 * {@code BusinessException} passes through unchanged. Every refusal the panel services throw is now a
 * message key, and each key has an English, a French and a Spanish sentence.
 */
class PanelRefusalKeysGuardTest {

    private static final Path PANEL_SERVICES = Path.of("src/main/java/com/example/hms/service/panel");
    private static final Path BUNDLES = Path.of("src/main/resources/i18n");
    private static final Pattern REFUSAL = Pattern.compile(
        "new\\s+(?:[\\w.]+\\.)?(?:BusinessException|FieldBusinessException|ResourceNotFoundException)\\(\\s*"
            + "(?:\"[A-Za-z]\\w*\"\\s*,\\s*)?\"([^\"]*)\"");
    private static final Pattern KEY = Pattern.compile("[a-z][a-zA-Z0-9]*+(?:\\.[a-zA-Z0-9]++)++");

    @Test
    void everyPanelRefusalIsAKeyWithEnglishFrenchAndSpanish() throws IOException {
        List<String> messages = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(PANEL_SERVICES)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = REFUSAL.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    messages.add(m.group(1));
                }
            }
        }
        assertThat(messages).as("the scan finds the panel refusals").hasSizeGreaterThanOrEqualTo(5);
        assertThat(messages).as("free text reaches a French reader in English")
            .allMatch(msg -> KEY.matcher(msg).matches());
        for (String locale : List.of("en", "fr", "es")) {
            Properties bundle = bundle(locale);
            assertThat(messages).as("keys missing from the %s bundles", locale)
                .allMatch(bundle::containsKey);
        }
    }

    @Test
    void theScanReadsAKeyAndCatchesASentence() {
        Matcher key = REFUSAL.matcher("throw new BusinessException(\"panel.alreadyEnded\");");
        assertThat(key.find()).isTrue();
        assertThat(KEY.matcher(key.group(1)).matches()).isTrue();
        Matcher sentence = REFUSAL.matcher("throw new BusinessException(\n    \"You have no panel here.\");");
        assertThat(sentence.find()).isTrue();
        assertThat(KEY.matcher(sentence.group(1)).matches()).isFalse();
        Matcher field = REFUSAL.matcher(
            "throw new com.example.hms.exception.FieldBusinessException(\"assignedOn\", \"panel.assignedOn.future\");");
        assertThat(field.find()).isTrue();
        assertThat(field.group(1)).isEqualTo("panel.assignedOn.future");
    }

    private static Properties bundle(String locale) throws IOException {
        Properties all = new Properties();
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("_" + locale + ".properties")).toList()) {
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    all.load(reader);
                }
            }
        }
        return all;
    }
}
