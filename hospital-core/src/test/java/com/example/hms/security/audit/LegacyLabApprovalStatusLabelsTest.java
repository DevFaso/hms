package com.example.hms.security.audit;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D905: lab approval audit rows written before #968 carry the status as a
 * label in the approver's language ("…nouveau statut Actif."), and the
 * portal read only codes, so ES and EN readers saw French. The portal now
 * maps the server's labels back to their code
 * ({@code LEGACY_LAB_APPROVAL_STATUS} in {@code audit-description.service.ts}).
 * This keeps that map complete: every {@code lab.definition.status.*} label in
 * every bundle must be there, under its own code.
 */
class LegacyLabApprovalStatusLabelsTest {

    private static final Path BUNDLES = Path.of("src", "main", "resources", "i18n");
    private static final Path SERVICE = Path.of("..", "hospital-portal", "src", "app", "audit-logs",
        "audit-description.service.ts");
    private static final String PREFIX = "lab.definition.status.";
    private static final Pattern ENTRY = Pattern.compile(
        "^\\s*+(?:'([^']*+)'|\"([^\"]*+)\"|([\\p{L}]++)):\\s*+'([A-Z_]++)',?\\s*+$");

    @Test
    void everyServerStatusLabelIsReadBackAsItsCode() throws IOException {
        Map<String, String> portal = portalMap();
        assertThat(portal).as("LEGACY_LAB_APPROVAL_STATUS entries").isNotEmpty();

        List<String> missing = new ArrayList<>();
        for (String bundle : List.of("messages-lab.properties", "messages-lab_en.properties",
                "messages-lab_fr.properties", "messages-lab_es.properties")) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(BUNDLES.resolve(bundle), StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            for (String key : properties.stringPropertyNames()) {
                if (!key.startsWith(PREFIX)) {
                    continue;
                }
                String code = key.substring(PREFIX.length());
                String label = properties.getProperty(key).replace("''", "'").replace('’', '\'')
                    .trim().toLowerCase(Locale.ROOT);
                if (!code.equals(portal.get(label))) {
                    missing.add(bundle + ": " + label + " -> " + code + " (portal has " + portal.get(label) + ")");
                }
            }
        }
        assertThat(missing).as("server status labels the audit log cannot read back").isEmpty();
    }

    private static Map<String, String> portalMap() throws IOException {
        String source = Files.readString(SERVICE, StandardCharsets.UTF_8).replace("\r\n", "\n");
        int start = source.indexOf("const LEGACY_LAB_APPROVAL_STATUS");
        assertThat(start).as("LEGACY_LAB_APPROVAL_STATUS in the audit description service").isNotNegative();
        int end = source.indexOf("\n};", start);
        Map<String, String> map = new HashMap<>();
        for (String line : source.substring(start, end).split("\n")) {
            Matcher matcher = ENTRY.matcher(line);
            if (matcher.matches()) {
                map.put(firstPresent(matcher.group(1), matcher.group(2), matcher.group(3)), matcher.group(4));
            }
        }
        return map;
    }

    /** The key as written: single-quoted, double-quoted or bare, whichever alternative matched. */
    private static String firstPresent(String singleQuoted, String doubleQuoted, String bare) {
        if (singleQuoted != null) {
            return singleQuoted;
        }
        return doubleQuoted != null ? doubleQuoted : bare;
    }
}
