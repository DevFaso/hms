package com.example.hms.architecture;

import com.example.hms.enums.AppointmentStatus;
import com.example.hms.enums.EncounterStatus;
import com.example.hms.i18n.TestMessageSources;
import com.example.hms.utility.MessageUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D825 guard: a refusal that names a visit's or an appointment's status
 * reads the status as people do ("Parti sans être vu"), never the raw code
 * ({@code LEFT_WITHOUT_BEING_SEEN}). Reopening a visit closed unseen answered
 * « Cette visite est déjà au statut LEFT_WITHOUT_BEING_SEEN » in French, and
 * nine sibling refusals of the encounter and reception workflows passed the
 * enum's {@code name()} the same way.
 *
 * <p>Fails when
 * <ul>
 *   <li>a status has no label in a locale, or its French or Spanish label is
 *       the raw code;</li>
 *   <li>a refusal in the encounter, reception, appointment or admission
 *       services passes a status by {@code name()}, as a bare
 *       {@code getStatus()}, or through {@code String.valueOf(...)};</li>
 *   <li>an {@code encounter.*}, {@code reception.*} or {@code appointment.*}
 *       message spells a status code in its own text ("Expected ARRIVED or
 *       TRIAGE").</li>
 * </ul>
 */
class StatusRefusalLabelGuardTest {

    private static final Path SERVICES = Paths.get("src/main/java/com/example/hms/service");
    private static final Pattern WORKFLOW_SERVICE =
        Pattern.compile("(Encounter|Reception|Appointment|Admission)\\w*\\.java");
    private static final Pattern REFUSAL = Pattern.compile(
        "new\\s+(?:\\w+\\.)*(?:Business|BusinessRule)Exception\\s*\\(");
    private static final Pattern RAW_STATUS_ARGUMENT = Pattern.compile(
        "\\.name\\(\\)|getStatus\\(\\)\\s*[,)]"
            + "|String\\.valueOf\\(\\s*\\w*(?i:status|current|previous|from|open)\\w*\\s*\\)");
    private static final Pattern WORKFLOW_KEY = Pattern.compile("^(encounter|reception|appointment)\\..*");
    private static final List<String> LOCALES = List.of("", "_en", "_fr", "_es");

    @AfterEach
    void resetStatics() {
        MessageUtil.setMessageSource(null);
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("every encounter and appointment status has a label in every locale, and FR/ES never show the code")
    void everyStatusHasALabel() {
        MessageUtil.setMessageSource(TestMessageSources.bundles());
        List<String> problems = new ArrayList<>();
        for (Locale locale : List.of(Locale.ENGLISH, Locale.FRENCH, Locale.forLanguageTag("es"))) {
            LocaleContextHolder.setLocale(locale);
            for (EncounterStatus status : EncounterStatus.values()) {
                String label = status.localizedLabel();
                if (label.equals(status.name())) {
                    problems.add("encounter " + status + " in " + locale);
                }
            }
            for (AppointmentStatus status : AppointmentStatus.values()) {
                String label = status.localizedLabel();
                if (label.equals(status.name())) {
                    problems.add("appointment " + status + " in " + locale);
                }
            }
        }
        assertThat(problems).as("statuses that would reach a refusal as their raw code").isEmpty();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(EncounterStatus.LEFT_WITHOUT_BEING_SEEN.localizedLabel()).isEqualTo("Parti sans être vu");
        assertThat(EncounterStatus.labelOf(null)).isEqualTo("—");
    }

    @Test
    @DisplayName("no workflow refusal passes a status as its code (name(), a bare getStatus(), String.valueOf)")
    void refusalsPassTheLabel() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(SERVICES)) {
            for (Path file : files.filter(p -> WORKFLOW_SERVICE.matcher(p.getFileName().toString()).matches()).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                offenders.addAll(rawStatusRefusals(SERVICES.relativize(file).toString().replace('\\', '/'), source));
            }
        }
        assertThat(offenders).as("refusals that name a status by its code; pass status.localizedLabel()"
            + " or EncounterStatus/AppointmentStatus.labelOf(...)").isEmpty();
    }

    @Test
    @DisplayName("the scan catches the shapes the D825 sites had")
    void theScanCatchesTheOldShapes() {
        String old = """
            throw new BusinessException("encounter.status.closed", from.name());
            throw new BusinessException("encounter.status.notSeen", String.valueOf(from), to.name());
            throw new BusinessException("reception.checkin.notEligible", String.valueOf(currentStatus));
            throw new BusinessException("consultation.start.badStatus", consultation.getStatus());
            throw new BusinessException("reception.checkin.notToday", String.valueOf(appointment.getAppointmentDate()));
            throw new BusinessException("encounter.status.closed", from.localizedLabel());
            """;
        assertThat(rawStatusRefusals("x", old)).hasSize(4);
    }

    @Test
    @DisplayName("no encounter/reception/appointment message spells a status code in its own text")
    void messagesNeverSpellACode() throws IOException {
        Set<String> codes = new TreeSet<>();
        for (EncounterStatus status : EncounterStatus.values()) {
            codes.add(status.name());
        }
        for (AppointmentStatus status : AppointmentStatus.values()) {
            codes.add(status.name());
        }
        Pattern code = Pattern.compile("\\b(" + String.join("|", codes) + ")\\b");
        List<String> offenders = new ArrayList<>();
        for (String area : List.of("clinical", "scheduling", "admin")) {
            for (String suffix : LOCALES) {
                Properties bundle = bundle(area, suffix);
                for (String key : bundle.stringPropertyNames()) {
                    if (!WORKFLOW_KEY.matcher(key).matches() || key.contains(".statusLabel.")) {
                        continue;
                    }
                    Matcher m = code.matcher(bundle.getProperty(key));
                    if (m.find()) {
                        offenders.add(area + suffix + ": " + key + " spells " + m.group(1));
                    }
                }
            }
        }
        assertThat(offenders).isEmpty();
    }

    private static Properties bundle(String area, String suffix) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(
                Paths.get("src/main/resources/i18n/messages-" + area + suffix + ".properties"), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static List<String> rawStatusRefusals(String file, String source) {
        List<String> found = new ArrayList<>();
        Matcher refusal = REFUSAL.matcher(source);
        while (refusal.find()) {
            int end = source.indexOf(';', refusal.end());
            String statement = source.substring(refusal.start(), end < 0 ? source.length() : end);
            if (RAW_STATUS_ARGUMENT.matcher(statement).find()) {
                int line = (int) source.substring(0, refusal.start()).chars().filter(c -> c == '\n').count() + 1;
                found.add(file + ":" + line + " " + statement.replaceAll("\\s+", " "));
            }
        }
        return found;
    }
}
