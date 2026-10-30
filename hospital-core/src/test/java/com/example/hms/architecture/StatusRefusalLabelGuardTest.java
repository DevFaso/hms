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
 * QA D825 guard (widened for QA D1090 to the billing and pharmacy services,
 * {@code ConflictException}, and the invoice and routing statuses): a refusal that names a visit's or an appointment's status
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
 *       {@code getStatus()}, through {@code String.valueOf(...)}, or into a
 *       sentence composed in code ({@code String.format}, {@code .formatted},
 *       a {@code +} join) around a status variable;</li>
 *   <li>an {@code encounter.*}, {@code reception.*} or {@code appointment.*}
 *       message spells a status code in its own text ("Expected ARRIVED or
 *       TRIAGE").</li>
 * </ul>
 */
class StatusRefusalLabelGuardTest {

    private static final Path SERVICES = Paths.get("src/main/java/com/example/hms/service");
    private static final Pattern WORKFLOW_SERVICE =
        Pattern.compile("(Encounter|Reception|Appointment|Admission|Billing|Invoice|Pharmacy|Dispense|Stock|Payment)\\w*\\.java");
    private static final Pattern REFUSAL = Pattern.compile(
        "new\\s+(?:\\w+\\.)*(?:Business|BusinessRule|Conflict)Exception\\s*\\(");
    private static final Pattern RAW_STATUS_ARGUMENT = Pattern.compile(
        "\\.name\\(\\)|getStatus\\(\\)\\s*[,)]"
            + "|String\\.valueOf\\(\\s*+(?=\\w*?(?i:status|current|previous|from|open))\\w++\\s*+\\)");
    /** A sentence composed in code: {@code String.format}, {@code .formatted}, or a {@code +} join with a literal. */
    private static final Pattern COMPOSED = Pattern.compile("String\\.format\\(|\\.formatted\\(|\"\\s*\\+|\\+\\s*\"");
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    /** A status already turned into its label is not a raw code, whatever surrounds it. */
    private static final Pattern LABELLED = Pattern.compile(
        "\\w*\\.labelOf\\((?:[^()]|\\([^()]*\\))*\\)|[\\w.]+(?:\\(\\))?\\.localizedLabel\\(\\)"
            // QA D1090: a status code looked up as a bundle label (pharmacy.claim.status.X) is a label.
            + "|MessageUtil\\.resolve\\(\\s*\"[\\w.]*status\\.\"\\s*\\+\\s*[\\w.()]+?\\.name\\(\\)\\)");
    private static final Pattern STATUS_IDENTIFIER = Pattern.compile(
        "\\b\\w*(?i:status|current|requested|previous|from|open)\\w*\\b");
    private static final Pattern WORKFLOW_KEY = Pattern.compile("^(encounter|reception|appointment|billing|stock\\.routing)\\..*");
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
            // QA D1090: the billing and pharmacy refusals name their statuses too.
            for (com.example.hms.enums.InvoiceStatus status : com.example.hms.enums.InvoiceStatus.values()) {
                if (status.localizedLabel().equals(status.name())) {
                    problems.add("invoice " + status + " in " + locale);
                }
            }
            for (com.example.hms.enums.RoutingDecisionStatus status : com.example.hms.enums.RoutingDecisionStatus.values()) {
                if (status.localizedLabel().equals(status.name())) {
                    problems.add("routing " + status + " in " + locale);
                }
            }
        }
        assertThat(problems).as("statuses that would reach a refusal as their raw code").isEmpty();
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(EncounterStatus.LEFT_WITHOUT_BEING_SEEN.localizedLabel()).isEqualTo("Parti sans être vu");
        assertThat(EncounterStatus.labelOf(null)).isEqualTo("—");
    }

    @Test
    @DisplayName("no workflow refusal passes a status as its code (name(), a bare getStatus(), String.valueOf,"
        + " or a sentence composed around it with String.format, .formatted or +)")
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
            throw new BusinessException(
                String.format("Cannot transition from %s to %s", current, requested));
            throw new BusinessException("Cannot mark appointment as %s before (%s)".formatted(newStatus, start));
            throw new BusinessException("Cannot transition from " + currentStatus);
            throw new BusinessException(currentStatus + " cannot be confirmed");
            throw new BusinessException("appointment.status.invalidTransition",
                AppointmentStatus.labelOf(current), AppointmentStatus.labelOf(requested));
            throw new BusinessException("Invalid action: " + action);
            throw new BusinessException("Moved from %s".formatted(AppointmentStatus.labelOf(previous)));
            """;
        assertThat(rawStatusRefusals("x", old)).hasSize(8);
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
        for (com.example.hms.enums.InvoiceStatus status : com.example.hms.enums.InvoiceStatus.values()) {
            codes.add(status.name());
        }
        for (com.example.hms.enums.RoutingDecisionStatus status : com.example.hms.enums.RoutingDecisionStatus.values()) {
            codes.add(status.name());
        }
        Pattern code = Pattern.compile("\\b(" + String.join("|", codes) + ")\\b");
        List<String> offenders = new ArrayList<>();
        for (String area : List.of("clinical", "scheduling", "admin", "billing", "pharmacy")) {
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

    /**
     * A refusal whose sentence is built in code around a status variable
     * ("Cannot transition from %s to %s", current, requested): the enum's
     * toString() is its code, and the sentence is English in every locale.
     * Literals are blanked first so their words ("from") do not count, and a
     * status already passed through labelOf/localizedLabel is not one.
     */
    private static boolean composesAStatus(String statement) {
        if (!COMPOSED.matcher(statement).find()) {
            return false;
        }
        // Labels first: a bundle-label lookup is recognised by its key literal (QA D1090).
        String code = LABELLED.matcher(statement).replaceAll("");
        code = LITERAL.matcher(code).replaceAll("\"\"");
        return STATUS_IDENTIFIER.matcher(code).find();
    }

    private static List<String> rawStatusRefusals(String file, String source) {
        List<String> found = new ArrayList<>();
        Matcher refusal = REFUSAL.matcher(source);
        while (refusal.find()) {
            int end = source.indexOf(';', refusal.end());
            String statement = source.substring(refusal.start(), end < 0 ? source.length() : end);
            // A status already passed through labelOf/localizedLabel is not a raw code (QA D1090).
            String unlabelled = LABELLED.matcher(statement).replaceAll("label");
            if (RAW_STATUS_ARGUMENT.matcher(unlabelled).find() || composesAStatus(statement)) {
                int line = (int) source.substring(0, refusal.start()).chars().filter(c -> c == '\n').count() + 1;
                found.add(file + ":" + line + " " + statement.replaceAll("\\s+", " "));
            }
        }
        return found;
    }
}
