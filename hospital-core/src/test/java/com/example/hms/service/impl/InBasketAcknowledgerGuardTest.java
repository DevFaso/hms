package com.example.hms.service.impl;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scorecard K4: an urgent clinical in-basket item (any CRITICAL item, an URGENT result) is acknowledged only by an
 * own clinical role ({@link InBasketServiceImpl#CLINICAL_ACKNOWLEDGERS}). Every recipient such an item is filed to
 * must hold one of those roles, or the item stays open for ever: the incompatible crossmatch filed to the lab
 * directors did. Each creator is listed with the roles it files to, and a new creator fails here until it is.
 */
@DisplayName("Every recipient of an urgent clinical in-basket item can acknowledge it (K4)")
class InBasketAcknowledgerGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    private static final Pattern CRITICAL = Pattern.compile("\\bInBasketPriority\\.CRITICAL\\b");
    private static final Pattern URGENT = Pattern.compile("\\bInBasketPriority\\.URGENT\\b");
    private static final Pattern RESULT = Pattern.compile("\\bInBasketItemType\\.RESULT\\b");

    /**
     * Each creator of an urgent clinical item and the roles it files to, with where they come from. Only grows
     * after checking a new creator's recipients.
     */
    private static final Map<String, Set<String>> CREATORS = new TreeMap<>(Map.of(
        // V380: the immediate review of an O positive release to a patient with a known antibody: the hospital's
        // biologists (BIOLOGIST_ROLE_CODES), or with none the physician named and the requester (PRESCRIBER).
        "service/transfusion/OPositiveReleaseNotices.java",
        Set.of("ROLE_LAB_DIRECTOR", "ROLE_DOCTOR", "ROLE_SURGEON", "ROLE_MIDWIFE"),
        // The ordering clinician: POST /lab-orders admits DOCTOR and NURSE; order sets and the super-admin
        // console name a doctor or midwife as the ordering staff.
        "service/LabResultServiceImpl.java", Set.of("ROLE_DOCTOR", "ROLE_NURSE", "ROLE_MIDWIFE"),
        // The requester (PRESCRIBER: DOCTOR, SURGEON, MIDWIFE), the bedside clinician who hung the bag (BEDSIDE:
        // + NURSE), and the lab directors when an emergency request has no requester.
        "service/impl/TransfusionServiceImpl.java",
        Set.of("ROLE_DOCTOR", "ROLE_SURGEON", "ROLE_MIDWIFE", "ROLE_NURSE", "ROLE_LAB_DIRECTOR"),
        // FIRST_NOTICE_ROLES and REMINDER_ROLES, read from the source below.
        "service/maternity/ObstetricAlertEscalationWorker.java", Set.of("ROLE_DOCTOR", "ROLE_MIDWIFE", "ROLE_NURSE")));

    @Test
    @DisplayName("every creator of an urgent clinical item is listed, and its recipients can acknowledge")
    void everyRecipientCanAcknowledge() throws IOException {
        Set<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("service"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                if (rel.equals("service/impl/InBasketServiceImpl.java")) {
                    continue;
                }
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (CRITICAL.matcher(source).find()
                    || (URGENT.matcher(source).find() && RESULT.matcher(source).find())) {
                    found.add(rel);
                }
            }
        }
        assertThat(found).as("files an urgent clinical in-basket item: list it in CREATORS with the roles it files "
            + "to, and make sure InBasketServiceImpl.CLINICAL_ACKNOWLEDGERS holds them").isEqualTo(CREATORS.keySet());
        CREATORS.forEach((creator, roles) -> assertThat(InBasketServiceImpl.CLINICAL_ACKNOWLEDGERS)
            .as(creator + " files to roles that could never acknowledge their item").containsAll(roles));
    }

    @Test
    @DisplayName("the roles read from the creators' own sources are the ones listed")
    void theListedRolesMatchTheSources() throws IOException {
        String obstetric = Files.readString(MAIN.resolve("service/maternity/ObstetricAlertEscalationWorker.java"),
            StandardCharsets.UTF_8);
        Set<String> rota = new TreeSet<>();
        Matcher list = Pattern.compile("(?:FIRST_NOTICE_ROLES|REMINDER_ROLES) = List\\.of\\(([^)]*)\\)").matcher(obstetric);
        while (list.find()) {
            Matcher role = Pattern.compile("\"(ROLE_[A-Z_]+)\"|([A-Z_]+_ROLE)\\b").matcher(list.group(1));
            while (role.find()) {
                rota.add(role.group(1) != null ? role.group(1) : constant(obstetric, role.group(2)));
            }
        }
        assertThat(rota).isNotEmpty();
        assertThat(CREATORS.get("service/maternity/ObstetricAlertEscalationWorker.java")).containsAll(rota);

        String transfusion = Files.readString(MAIN.resolve("service/impl/TransfusionServiceImpl.java"),
            StandardCharsets.UTF_8);
        assertThat(transfusion).contains("LAB_DIRECTOR_ROLE_CODES = Set.of(SecurityConstants.ROLE_LAB_DIRECTOR)");
    }

    private static String constant(String source, String name) {
        Matcher m = Pattern.compile(Pattern.quote(name) + " = \"(ROLE_[A-Z_]+)\"").matcher(source);
        return m.find() ? m.group(1) : name;
    }
}
