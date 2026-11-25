package com.example.hms.i18n;

import com.example.hms.config.MessageBundles;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Public prose comes from translated bundles; machine audit action codes and logs remain identifiers. */
class ServerFrenchProseGuardTest {
    private static final Pattern ENGLISH = Pattern.compile("(?i)\\b(please (?:enter|select|try)|could not|must be|not found|successfully (?:created|updated)|your (?:appointment|result|prescription)|click here)\\b");

    @Test void everyFrenchBundleIsPresentAndDoesNotCarryCommonEnglishFallbackProse() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (String area : MessageBundles.AREAS) {
            Properties base = load(area, "");
            Properties french = load(area, "_fr");
            assertThat(french.stringPropertyNames()).as(area).containsAll(base.stringPropertyNames());
            for (String key : french.stringPropertyNames()) {
                if (ENGLISH.matcher(french.getProperty(key)).find()) offenders.add(area + ":" + key);
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test void theScannerRecognizesEnglishAndDoesNotRejectFrenchOrClinicalCodes() {
        assertThat(ENGLISH.matcher("Your result is ready. Please try again.").find()).isTrue();
        assertThat(ENGLISH.matcher("Votre résultat est disponible. Code HIV-1.").find()).isFalse();
    }

    @Test void displayFallbacksAcrossAllMappersUseBundlesAndMissingKeysNeverEchoArguments() throws IOException {
        Pattern englishSentinel = Pattern.compile("\\breturn\\s+\"(?:Unknown|Unknown author|Staff|Not specified|Not available)\"\\s*;");
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java/com/example/hms/mapper"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (englishSentinel.matcher(SourceScan.stripComments(Files.readString(path))).find()) offenders.add(path.toString());
            }
        }
        assertThat(offenders).isEmpty();
        String source = SourceScan.stripComments(Files.readString(Path.of("src/main/java/com/example/hms/utility/MessageUtil.java")));
        assertThat(source).doesNotContain("[Missing translation]", "args[0]");
        assertThat(englishSentinel.matcher("return \"Staff\";").find()).isTrue();
        assertThat(englishSentinel.matcher("return \"UNKNOWN\";").find()).isFalse();
    }

    private static Properties load(String area, String suffix) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(Path.of("src/main/resources", MessageBundles.resource(area, suffix)), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    @Test void noCallerBranchesOnMissingTranslationProse() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (SourceScan.stripComments(Files.readString(path)).contains("\"[Missing translation]")) offenders.add(path.toString());
            }
        }
        assertThat(offenders).as("Use optional label resolution, never parse fallback error text").isEmpty();
    }
}
