package com.example.hms.db;

import com.example.hms.service.maternity.UltrasoundObservations;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1525 review: the observation fields are named in three places that must
 * agree: the server ({@link UltrasoundObservations}), the portal
 * ({@code TRI_STATE_FLAGS}, {@code OBSERVATION_FIELDS}, {@code LEGACY_DEFAULTS}
 * in ultrasound-tab.ts) and V282's data step (the columns it clears).
 */
class UltrasoundObservationFieldsTest {

    private static final Path PORTAL_TAB = Path.of("../hospital-portal/src/app/maternity/ultrasound-tab.ts");

    @Test
    void theServerThePortalAndV282NameTheSameObservations() throws Exception {
        String ts = Files.readString(PORTAL_TAB, StandardCharsets.UTF_8);
        Set<String> server = UltrasoundObservations.FIELDS.stream().map(UltrasoundObservations.Field::name)
            .collect(Collectors.toCollection(TreeSet::new));

        Set<String> portal = new TreeSet<>(quoted(arrayLiteral(ts, "TRI_STATE_FLAGS")));
        portal.addAll(quoted(arrayLiteral(ts, "OBSERVATION_FIELDS")));
        assertThat(portal).as("portal TRI_STATE_FLAGS + OBSERVATION_FIELDS").isEqualTo(server);

        Set<String> portalDefaults = new TreeSet<>(keys(objectLiteral(ts, "LEGACY_DEFAULTS")));
        assertThat(portalDefaults).as("portal LEGACY_DEFAULTS")
            .isEqualTo(new TreeSet<>(UltrasoundObservations.LEGACY_DEFAULTS.keySet()));

        Set<String> cleared = new TreeSet<>();
        Matcher m = Pattern.compile("SET (\\w+) = NULL").matcher(v282());
        while (m.find()) {
            cleared.add(m.group(1));
        }
        cleared.remove("gestational_age_at_scan");
        Set<String> expected = UltrasoundObservations.LEGACY_DEFAULTS.keySet().stream()
            .map(UltrasoundObservationFieldsTest::snake).collect(Collectors.toCollection(TreeSet::new));
        assertThat(cleared).as("the observation columns V282 clears").isEqualTo(expected);
    }

    private static String arrayLiteral(String ts, String name) {
        Matcher m = Pattern.compile("export const " + name + "[^=]*=\\s*\\[([^\\]]*)\\]").matcher(ts);
        assertThat(m.find()).as(name + " in ultrasound-tab.ts").isTrue();
        return m.group(1);
    }

    private static String objectLiteral(String ts, String name) {
        Matcher m = Pattern.compile("export const " + name + "[^=]*=\\s*\\{([^}]*)\\}").matcher(ts);
        assertThat(m.find()).as(name + " in ultrasound-tab.ts").isTrue();
        return m.group(1);
    }

    private static Set<String> quoted(String body) {
        Set<String> names = new LinkedHashSet<>();
        Matcher m = Pattern.compile("'(\\w+)'").matcher(body);
        while (m.find()) {
            names.add(m.group(1));
        }
        return names;
    }

    private static Set<String> keys(String body) {
        return Arrays.stream(body.split(",")).map(String::trim).filter(e -> e.contains(":"))
            .map(e -> e.substring(0, e.indexOf(':')).trim()).collect(Collectors.toSet());
    }

    private static String snake(String camel) {
        return camel.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT);
    }

    private static String v282() throws Exception {
        try (InputStream in = UltrasoundObservationFieldsTest.class.getClassLoader()
                .getResourceAsStream("db/migration/V282__ultrasound_observations_stated.sql")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
