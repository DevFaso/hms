package com.example.hms.i18n;

import com.example.hms.config.MessageBundles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The message bundles are split by feature area (see {@link MessageBundles}) so
 * that PRs touching different areas stop conflicting. The split is only safe if
 * every key lives in exactly one area, in the same area for every locale, and
 * every file on disk is registered with the MessageSource.
 */
@DisplayName("Backend message bundles split by area")
class MessageBundleSplitTest {

    private static final Path I18N = Paths.get("src/main/resources/i18n");

    @Test
    @DisplayName("every area file on disk is registered, and every registered file exists")
    void fileSetMatchesRegistry() throws IOException {
        Set<String> expected = new TreeSet<>();
        for (String area : MessageBundles.AREAS) {
            for (String suffix : MessageBundles.SUFFIXES) {
                expected.add(Paths.get(MessageBundles.resource(area, suffix)).getFileName().toString());
            }
        }
        Set<String> actual;
        try (Stream<Path> files = Files.list(I18N)) {
            actual = files.map(p -> p.getFileName().toString())
                .filter(n -> n.startsWith("messages") && n.endsWith(".properties"))
                .collect(Collectors.toCollection(TreeSet::new));
        }
        assertThat(actual).as("i18n/messages*.properties files vs MessageBundles.AREAS x SUFFIXES")
            .isEqualTo(expected);
        assertThat(Paths.get("src/main/resources/messages.properties"))
            .as("the single bundle was split into i18n/; add keys to the area file instead")
            .doesNotExist();
    }

    @Test
    @DisplayName("no key appears in two area files, and a key sits in the same area for every locale")
    void eachKeyHasOneArea() throws IOException {
        List<String> problems = new ArrayList<>();
        Map<String, String> defaultArea = new HashMap<>();
        for (String suffix : MessageBundles.SUFFIXES) {
            Map<String, String> areaOf = new HashMap<>();
            for (String area : MessageBundles.AREAS) {
                for (String key : MessageBundleParityTest.loadFile(area, suffix).stringPropertyNames()) {
                    String other = areaOf.putIfAbsent(key, area);
                    if (other != null) {
                        problems.add(key + " in both " + other + " and " + area + " (" + suffix + ")");
                    }
                    if (suffix.isEmpty()) {
                        defaultArea.put(key, area);
                    } else if (defaultArea.containsKey(key) && !defaultArea.get(key).equals(area)) {
                        problems.add(key + " is in " + defaultArea.get(key) + " by default but in "
                            + area + suffix);
                    }
                }
            }
        }
        assertThat(problems).as("Keys in more than one area:%n%s",
            String.join(System.lineSeparator(), problems)).isEmpty();
    }

    @Test
    @DisplayName("every locale's area file defines every key its default area file has")
    void everyLocaleHasEveryDefaultKey() throws IOException {
        List<String> missing = new ArrayList<>();
        for (String area : MessageBundles.AREAS) {
            Properties base = MessageBundleParityTest.loadFile(area, "");
            for (String suffix : MessageBundles.SUFFIXES.subList(1, MessageBundles.SUFFIXES.size())) {
                Properties locale = MessageBundleParityTest.loadFile(area, suffix);
                for (String key : new TreeSet<>(base.stringPropertyNames())) {
                    if (!locale.containsKey(key)) {
                        missing.add(MessageBundles.resource(area, suffix) + " lacks " + key);
                    }
                }
            }
        }
        assertThat(missing).as("Keys missing from a locale:%n%s",
            String.join(System.lineSeparator(), missing)).isEmpty();
    }
}
