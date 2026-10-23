package com.example.hms.i18n;

import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D636: the duplicate-ward refusal said "Un service avec le code…", while
 * "service" is what this app calls a department. In French a ward is an
 * "unité" (the ward admin page's own word). Every French text about a ward —
 * a backend message whose English text names a ward, or a portal key named
 * WARD — must not call it a "service".
 */
class WardTerminologyTest {

    private static final Pattern SERVICE = Pattern.compile("\\bservices?\\b", Pattern.CASE_INSENSITIVE);
    private static final Path BUNDLES = Path.of("src", "main", "resources", "i18n");
    private static final Path PORTAL_FR = Path.of("..", "hospital-portal", "src", "assets", "i18n", "fr.json");

    @Test
    void frenchBackendMessagesAboutAWardSayUnite() throws Exception {
        List<String> offenders = new ArrayList<>();
        try (DirectoryStream<Path> en = Files.newDirectoryStream(BUNDLES, "messages*_en.properties")) {
            for (Path enFile : en) {
                Path frFile = enFile.resolveSibling(enFile.getFileName().toString().replace("_en.", "_fr."));
                if (!Files.exists(frFile)) continue;
                Properties english = load(enFile);
                Properties french = load(frFile);
                for (String key : english.stringPropertyNames()) {
                    String text = english.getProperty(key).toLowerCase(Locale.ROOT);
                    // A department IS a "service"; its messages may mention wards beside it.
                    boolean aboutWard = !key.startsWith("department.") && (key.startsWith("ward.") || text.matches("(?s).*\\bwards?\\b.*"));
                    String fr = french.getProperty(key);
                    if (aboutWard && fr != null && SERVICE.matcher(fr).find()) {
                        offenders.add(frFile.getFileName() + " " + key + "=" + fr);
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    @Test
    void portalWardLabelsSayUnite() throws Exception {
        String json = Files.readString(PORTAL_FR, StandardCharsets.UTF_8);
        Matcher entry = Pattern.compile("\"([A-Z_]*WARD[A-Z_]*)\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        List<String> offenders = new ArrayList<>();
        int seen = 0;
        while (entry.find()) {
            seen++;
            if (SERVICE.matcher(entry.group(2)).find()) offenders.add(entry.group(1) + "=" + entry.group(2));
        }
        assertThat(seen).as("WARD keys found in %s", PORTAL_FR.toAbsolutePath()).isGreaterThan(5);
        assertThat(offenders).isEmpty();
    }

    private static Properties load(Path file) throws Exception {
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(reader);
        }
        return p;
    }
}
