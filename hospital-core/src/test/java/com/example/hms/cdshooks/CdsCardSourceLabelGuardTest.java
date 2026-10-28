package com.example.hms.cdshooks;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D935/D970 guard: the source label on a CDS card is written in the
 * reader's language. "HMS PEDIATRIC DOSE CHECK" and "HMS DUPLICATE-ORDER
 * CHECK" reached the French prescribing screen because the rules built the
 * card's {@code Source} from an English constant. Every card source in the
 * backend must go through a message key ({@code text.get("cds.…", …)} or
 * {@code CrossReactivityAdvisory.sourceLabel()}), and every {@code cds.source.*}
 * key must exist in the French and Spanish bundles.
 */
class CdsCardSourceLabelGuardTest {

    private static final Pattern SOURCE_CTOR = Pattern.compile("(?:new Source|CdsHookDtos\\.Source)\\(\\s*([^,]+),");
    private static final Pattern KEY = Pattern.compile("text\\.get\\(\"(cds\\.[A-Za-z0-9_.]+)\"");

    @Test
    void everyCardSourceIsLocalised() throws IOException {
        List<String> offenders = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src", "main", "java"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(file);
                if (!text.contains("CdsHookDtos")) continue;
                Matcher m = SOURCE_CTOR.matcher(text);
                while (m.find()) {
                    String arg = m.group(1).trim();
                    Matcher key = KEY.matcher(arg + ",");
                    if (key.find()) {
                        keys.add(key.group(1));
                    } else if (!arg.endsWith("sourceLabel()")) {
                        offenders.add(file.getFileName() + ": " + arg);
                    }
                }
            }
        }
        assertThat(keys).hasSizeGreaterThanOrEqualTo(10);
        assertThat(offenders).isEmpty();
        keys.add("cds.source.allergy");
        for (String locale : List.of("fr", "es")) {
            Properties bundle = new Properties();
            try (var reader = Files.newBufferedReader(
                    Path.of("src", "main", "resources", "i18n", "messages-clinical_" + locale + ".properties"))) {
                bundle.load(reader);
            }
            for (String key : keys) {
                assertThat(bundle.getProperty(key)).as(locale + " " + key).isNotBlank();
                assertThat(bundle.getProperty(key).toLowerCase(Locale.ROOT)).as(locale + " " + key)
                    .doesNotContain("check");
            }
        }
    }
}
