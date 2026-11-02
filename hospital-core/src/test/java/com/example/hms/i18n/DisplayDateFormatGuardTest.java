package com.example.hms.i18n;

import com.example.hms.utility.DisplayDates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1234 (round 24): the early-dose refusal printed its times as
 * {@code dd/MM HH:mm} in every language - "02/11 00:28" reads as 11 February
 * to an English reader, and the French sentence said "administrée à 02/11".
 * Other refusals handed a {@code LocalDate} straight to the message, which
 * prints the ISO {@code 2026-11-02} in a French or Spanish sentence.
 *
 * <p>Every date or time a person reads in a server-composed sentence goes
 * through {@link DisplayDates} (the sibling of {@code DisplayNumbers}). This
 * reads the whole main tree for the two ways around it:
 * <ul>
 *   <li>a {@code DateTimeFormatter.ofPattern} with the day and the month in a
 *       fixed order ({@code dd/MM}, {@code MM/dd}, {@code d MMM}): the order
 *       and the month words are the reader's, never the code's. The ISO
 *       {@code yyyy-MM-dd} and compact machine tokens ({@code yyyyMMdd}) are
 *       not display formats and pass;</li>
 *   <li>a date or time value handed as an argument to a refusal or a resolved
 *       message - an exception constructor, {@code MessageUtil.resolve},
 *       {@code messageSource.getMessage}, {@code getLocalizedMessage},
 *       {@code NotificationText.of} - without {@code DisplayDates}: a
 *       date-typed getter ({@code getExpiresOn()}, {@code toLocalTime()}), or
 *       a local variable or field declared as a {@code java.time} type.</li>
 * </ul>
 * The FHIR package is outside: its texts are English OperationOutcomes by
 * convention, and its dates are FHIR instants.
 */
@DisplayName("A date in a server-composed sentence is written the reader's way (QA D1234)")
class DisplayDateFormatGuardTest {

    private static final Path MAIN_JAVA = Paths.get("src/main/java");

    /** A display pattern with the day and the month in a fixed order. */
    private static final Pattern DAY_MONTH_PATTERN = Pattern.compile(
        "ofPattern\\(\\s*\"([^\"]*(?:d{1,2}[/. ]M{1,4}|M{1,4}[/. ]d{1,2})[^\"]*)\"");

    /** Sites that parse typed input in both orders: reading, not writing. */
    private static final Map<String, String> PARSING_ONLY = Map.of(
        "com/example/hms/service/UserRoleHospitalAssignmentServiceImpl.java",
        "reads a bulk-import date the admin typed as dd/MM/yyyy or MM/dd/yyyy; never writes one");

    private static final Pattern MESSAGE_CALL = Pattern.compile(
        "(?:new\\s+(?:[a-z]\\w*+\\.)*+\\w*Exception|MessageUtil\\.resolve\\w*|messageSource\\.getMessage"
            + "|getLocalizedMessage|NotificationText\\.of)\\s*+\\(");
    private static final Pattern DATE_GETTER = Pattern.compile(
        "\\.(?:get\\w*(?:Date|At|On|Time)|toLocalDate|toLocalTime)\\(\\)");
    private static final Pattern DATE_DECLARATION = Pattern.compile(
        "\\b(?:LocalDate|LocalDateTime|LocalTime|Instant|OffsetDateTime|ZonedDateTime)\\s++(\\w++)\\s*+[=;,)]");
    /** A comparison or computation on a date is not the date itself. */
    private static final Pattern NOT_A_DATE_VALUE = Pattern.compile(
        "\\.format\\(|isAfter|isBefore|isEqual|compareTo|==|!=|Duration|ChronoUnit|getYear|getMonth|getHour"
            + "|getMinute|getDayOf|toEpoch|until\\(");
    private static final Pattern OTHER_DECLARATION = Pattern.compile(
        "\\b([A-Z]\\w*+)(?:<[^<>;]*+>)?\\s++(\\w++)\\s*+[=;,)]");
    private static final Set<String> DATE_TYPES = Set.of(
        "LocalDate", "LocalDateTime", "LocalTime", "Instant", "OffsetDateTime", "ZonedDateTime");
    private static final Pattern WRAPPED =Pattern.compile("DisplayDates\\.\\w++\\s*+\\(");

    @Test
    @DisplayName("no display pattern fixes the order of the day and the month")
    void noFixedDayMonthPattern() throws IOException {
        List<String> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Map.Entry<String, String> file : mainSources().entrySet()) {
            Matcher m = DAY_MONTH_PATTERN.matcher(file.getValue());
            while (m.find()) {
                if (PARSING_ONLY.containsKey(file.getKey())) {
                    seen.add(file.getKey());
                } else {
                    found.add(file.getKey() + ":" + line(file.getValue(), m.start()) + " \"" + m.group(1) + "\"");
                }
            }
        }
        assertThat(found).as("Write the date with DisplayDates (the reader's order and words)").isEmpty();
        assertThat(seen).as("a parsing-only site was removed: delete its entry").containsExactlyInAnyOrderElementsOf(
            PARSING_ONLY.keySet());
    }

    @Test
    @DisplayName("no refusal or resolved message is handed a raw date or time")
    void noRawDateInAMessage() throws IOException {
        List<String> found = new ArrayList<>();
        for (Map.Entry<String, String> file : mainSources().entrySet()) {
            found.addAll(rawDates(file.getKey(), file.getValue()));
        }
        assertThat(found).as("Pass DisplayDates.date/time/dateTime(...) to the message, not the value").isEmpty();
    }

    @Test
    @DisplayName("the scans catch the D1234 shapes and pass the fixed ones")
    void scansCatchTheOldShapes() {
        assertThat(DAY_MONTH_PATTERN.matcher(
            "private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern(\"dd/MM HH:mm\");").find())
            .isTrue();
        assertThat(DAY_MONTH_PATTERN.matcher("DateTimeFormatter.ofPattern(\"MM/dd/yyyy\")").find()).isTrue();
        assertThat(DAY_MONTH_PATTERN.matcher("DateTimeFormatter.ofPattern(\"d MMM yyyy\")").find()).isTrue();
        assertThat(DAY_MONTH_PATTERN.matcher("DateTimeFormatter.ofPattern(\"yyyy-MM-dd HH:mm\")").find()).isFalse();
        assertThat(DAY_MONTH_PATTERN.matcher("DateTimeFormatter.ofPattern(\"yyyyMMdd\")").find()).isFalse();
        assertThat(DAY_MONTH_PATTERN.matcher("DateTimeFormatter.ofPattern(\"HH:mm\")").find()).isFalse();

        assertThat(rawDates("A.java",
            "throw new BusinessException(\"transfusion.unit.expired\", unit.getUnitNumber(), unit.getExpiresOn());"))
            .hasSize(1);
        assertThat(rawDates("A.java",
            "throw new BusinessException(\"reception.checkin.notToday\", String.valueOf(a.getAppointmentDate()));"))
            .hasSize(1);
        assertThat(rawDates("A.java", "LocalDate visitDate = x;\n"
            + "throw new BusinessException(\"program.visit\", visitDate);")).hasSize(1);
        assertThat(rawDates("A.java", "messageSource.getMessage(\"appointment.staff.unavailable\",\n"
            + "    new Object[]{staff.getId(), request.getAppointmentDate(), request.getStartTime()}, locale)"))
            .hasSize(2);
        assertThat(rawDates("A.java",
            "throw new BusinessException(\"transfusion.unit.expired\", unit.getUnitNumber(),\n"
                + "    com.example.hms.utility.DisplayDates.date(unit.getExpiresOn()));")).isEmpty();
        assertThat(rawDates("A.java", "throw new FieldBusinessException(F, KEY,\n"
            + "    DisplayDates.dateTime(HospitalTime.at(lastGiven.get(), hospital)));")).isEmpty();
        assertThat(rawDates("A.java", "throw new BusinessException(\"x\", rx.getId());")).isEmpty();
    }

    @Test
    @DisplayName("QA D1234: the formatter writes the reader's order, never dd/MM in English")
    void theFormatterWritesTheReadersWay() {
        LocalDateTime at = LocalDateTime.of(2026, 11, 2, 0, 28);
        assertThat(DisplayDates.dateTime(at, Locale.ENGLISH)).contains("Nov").contains("2026").doesNotContain("02/11");
        assertThat(DisplayDates.dateTime(at, Locale.FRENCH)).contains("nov.").contains("00:28");
        assertThat(DisplayDates.dateTime(at, Locale.forLanguageTag("es"))).contains("nov").contains("2026");
        assertThat(DisplayDates.date(LocalDate.of(2026, 11, 2), Locale.FRENCH)).isEqualTo("2 nov. 2026");
        assertThat(DisplayDates.time(LocalTime.of(0, 28), Locale.FRENCH)).isEqualTo("00:28");
        assertThat(DisplayDates.dateTime(null, Locale.FRENCH)).isEqualTo("—");
        assertThat(DisplayDates.date(null, Locale.FRENCH)).isEqualTo("—");
        assertThat(DisplayDates.time(null, Locale.FRENCH)).isEqualTo("—");
    }

    private static List<String> rawDates(String file, String source) {
        Set<String> dateNames = new HashSet<>();
        Matcher d = DATE_DECLARATION.matcher(source);
        while (d.find()) {
            dateNames.add(d.group(1));
        }
        // A name the file also declares with another type (a UUID "requested"
        // in one method, a LocalDateTime "requested" in another) cannot be
        // told apart by reading the text: it is left out rather than guessed.
        Matcher other = OTHER_DECLARATION.matcher(source);
        while (other.find()) {
            if (!DATE_TYPES.contains(other.group(1))) {
                dateNames.remove(other.group(2));
            }
        }
        List<String> out = new ArrayList<>();
        Matcher call = MESSAGE_CALL.matcher(source);
        while (call.find()) {
            List<String> args = SourceArguments.split(source, call.end());
            for (String arg : args.subList(Math.min(1, args.size()), args.size())) {
                for (String value : SourceArguments.arrayElements(arg)) {
                    String bare = unwrapped(value);
                    if (isRawDate(bare, dateNames)) {
                        out.add(file + ":" + line(source, call.start()) + " " + value.replaceAll("\\s+", " "));
                    }
                }
            }
        }
        return out;
    }

    private static boolean isRawDate(String value, Set<String> dateNames) {
        String v = value.trim();
        String receiver = v.endsWith(".toString()") ? v.substring(0, v.length() - ".toString()".length()) : v;
        if (dateNames.contains(v) || dateNames.contains(receiver)) {
            return true;
        }
        return DATE_GETTER.matcher(v).find() && !NOT_A_DATE_VALUE.matcher(v).find();
    }

    /** The argument with every {@code DisplayDates.x(...)} call cut out. */
    private static String unwrapped(String value) {
        StringBuilder out = new StringBuilder();
        int from = 0;
        Matcher w = WRAPPED.matcher(value);
        while (w.find(from)) {
            out.append(value, from, w.start());
            from = SourceArguments.closingParen(value, w.end());
            if (from >= value.length()) {
                return out.toString();
            }
        }
        return out.append(value.substring(from)).toString();
    }

    private static int line(String source, int index) {
        int n = 1;
        for (int i = 0; i < index; i++) {
            if (source.charAt(i) == '\n') {
                n++;
            }
        }
        return n;
    }

    private static Map<String, String> mainSources() throws IOException {
        Map<String, String> out = new java.util.TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String rel = MAIN_JAVA.relativize(p).toString().replace('\\', '/');
                if (!rel.startsWith("com/example/hms/fhir/")) {
                    out.put(rel, Files.readString(p, StandardCharsets.UTF_8));
                }
            }
        }
        return out;
    }

    /** Argument splitting that respects strings and nesting. */
    static final class SourceArguments {

        private SourceArguments() {
        }

        /** The top-level arguments of the call whose opening parenthesis ends at {@code from}. */
        static List<String> split(String source, int from) {
            int end = closingParen(source, from);
            return topLevel(source.substring(from, Math.max(from, end - 1)));
        }

        /** The elements of {@code new Object[]{...}}, or the argument itself. */
        static List<String> arrayElements(String arg) {
            String a = arg.trim();
            if (a.startsWith("new Object[]") && a.indexOf('{') > 0 && a.endsWith("}")) {
                return topLevel(a.substring(a.indexOf('{') + 1, a.length() - 1));
            }
            return List.of(a);
        }

        /** The index just past the parenthesis closing the one that ends at {@code from}. */
        static int closingParen(String source, int from) {
            int depth = 1;
            int i = from;
            while (i < source.length() && depth > 0) {
                char ch = source.charAt(i);
                if (ch == '"') {
                    i = skipString(source, i);
                    continue;
                }
                if (ch == '(') {
                    depth++;
                } else if (ch == ')') {
                    depth--;
                }
                i++;
            }
            return i;
        }

        private static int skipString(String source, int quote) {
            int i = quote + 1;
            while (i < source.length() && source.charAt(i) != '"') {
                i += source.charAt(i) == '\\' ? 2 : 1;
            }
            return i + 1;
        }

        private static List<String> topLevel(String text) {
            List<String> out = new ArrayList<>();
            int depth = 0;
            int start = 0;
            int i = 0;
            while (i < text.length()) {
                char ch = text.charAt(i);
                if (ch == '"') {
                    i = skipString(text, i);
                    continue;
                }
                if (ch == '(' || ch == '[' || ch == '{') {
                    depth++;
                } else if (ch == ')' || ch == ']' || ch == '}') {
                    depth--;
                } else if (ch == ',' && depth == 0) {
                    out.add(text.substring(start, i).trim());
                    start = i + 1;
                }
                i++;
            }
            if (!text.substring(start).isBlank()) {
                out.add(text.substring(start).trim());
            }
            return out;
        }
    }
}
