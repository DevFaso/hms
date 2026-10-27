package com.example.hms.security.audit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D894: the audit log in French still showed descriptions the server had
 * written in English ("Super-admin global-view request…", "Login failed: bad
 * credentials"): each one is read in the reader's language only when the
 * portal has a pattern for its shape, and nothing noticed a description that
 * had none.
 *
 * <p>This builds every description the server can write, from the source:
 * each {@code .eventDescription(…)} argument is followed through string
 * concatenation, {@code String.format}, ternaries, local variables, string
 * constants (of any scanned class, {@code Type.NAME}, and a constant naming
 * another), the arguments callers pass to a helper's parameter, a helper's
 * return values and English bundle messages. Anything that is not a literal
 * becomes a placeholder, except where it IS the whole description: there, a
 * value the scan cannot build (a message in the caller's language, an unknown
 * call) fails the guard for its file, even when another caller of the same
 * helper passes a literal. Every description built must match one of the
 * portal's patterns ({@code audit-description-patterns.ts}), and every
 * pattern must have its text in the three locales. A site the scan cannot
 * build is listed in {@link #NOT_BUILT} with the reason it needs no pattern;
 * that list may only shrink.
 */
class AuditDescriptionCoverageTest {

    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path BUNDLES = Path.of("src", "main", "resources", "i18n");
    private static final Path PORTAL = Path.of("..", "hospital-portal", "src");
    private static final Path PATTERNS = PORTAL.resolve(Path.of("app", "audit-logs", "audit-description-patterns.ts"));
    private static final char HOLE = '\u0001';
    private static final List<String> CANDIDATES = List.of("12", "SAMPLE_VALUE", "Sample value");
    private static final int MAX_DEPTH = 5;
    private static final int MAX_SAMPLES = 4000;

    /** Files whose {@code .eventDescription} the scan cannot build, and why no pattern is owed. */
    private static final Map<String, String> NOT_BUILT = Map.of(
        "com/example/hms/service/AuditEventLogServiceImpl.java",
        "the writer every emitter goes through: it copies the request's description",
        "com/example/hms/mapper/AuditEventLogMapper.java", "copies a stored row into the response",
        "com/example/hms/service/SuperAdminDashboardServiceImpl.java", "copies a stored row into the dashboard",
        "com/example/hms/security/audit/WriteAuditInterceptor.java",
        "\"METHOD /route\": read by WRITE_DESCRIPTION in AuditDescriptionService, with the entity");

    private static final Pattern METHOD = Pattern.compile(
        "(?:(public|private|protected)\\s++)?(?:(?:static|final|synchronized|default)\\s++)*+"
            + "[\\w\\[\\].?]++(?:<[^>]*+>[>\\[\\]]*+)?\\s++(\\w++)\\s*+\\(");
    private static final Pattern BODY_START = Pattern.compile("^\\s*+(?:throws\\s++[\\w.,][\\w.,\\s]*+)?\\{");
    private static final Set<String> NOT_METHODS = Set.of("if", "for", "while", "switch", "catch", "return",
        "new", "else", "synchronized", "try");
    private static final Pattern LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final Pattern FORMAT = Pattern.compile("^String\\.format\\(\\s*(\"(?:[^\"\\\\]|\\\\.)*\")");
    private static final Pattern ENGLISH_MESSAGE = Pattern.compile(
        "^(?:\\w++\\.)*MessageUtil\\.resolve\\(\\s*+(?:java\\.util\\.)?Locale\\.ENGLISH\\s*+,\\s*+(.*)\\)$",
        Pattern.DOTALL);
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_]\\w*$");
    /** {@code Type.CONSTANT} or {@code com.example.Type.CONSTANT}: a constant of another class. */
    private static final Pattern QUALIFIED_CONSTANT = Pattern.compile("^(?:[a-z]\\w*\\.)*([A-Z]\\w*)\\.([A-Z][A-Z0-9_]*)$");
    /** {@code messageSource.getMessage(key, args, locale)}: English bundle text only with Locale.ENGLISH. */
    private static final Pattern SOURCE_MESSAGE = Pattern.compile(
        "^(?:\\w+\\.)?messageSource\\.getMessage\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern ENGLISH_LOCALE = Pattern.compile("^(?:java\\.util\\.)?Locale\\.ENGLISH$");
    private static final Pattern LOCAL_CALL = Pattern.compile("^([a-z]\\w*)\\s*\\((.*)\\)$", Pattern.DOTALL);
    private static final Pattern PATTERN_LINE = Pattern.compile("^\\s*re: /(.*)/,\\s*$");
    private static final Pattern KEY_LINE = Pattern.compile("^\\s*key: '([A-Z0-9_.]+)',\\s*$");

    private record Method(String name, String visibility, int start, int end, List<String> params) {
    }

    private record Source(String path, String className, String text, String masked, List<Method> methods) {
    }

    private static final Map<String, Source> SOURCES = new TreeMap<>();
    private static final Map<String, String> ENGLISH = new HashMap<>();
    private static final Map<String, Set<String>> SAMPLES = new TreeMap<>();
    private static final Set<String> UNBUILT = new TreeSet<>();
    /** For each unbuilt file, the expressions that could not be built, with their line. */
    private static final Map<String, Set<String>> UNBUILT_SITES = new TreeMap<>();
    private static final List<Pattern> PORTAL_PATTERNS = new ArrayList<>();
    private static final List<String> PORTAL_KEYS = new ArrayList<>();

    @BeforeAll
    static void scan() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                String masked = mask(text);
                String path = MAIN.relativize(file).toString().replace('\\', '/');
                String className = file.getFileName().toString().replace(".java", "");
                SOURCES.put(path, new Source(path, className, text, masked, methods(text, masked)));
            }
        }
        try (Stream<Path> files = Files.list(BUNDLES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("_en.properties")).toList()) {
                Properties properties = new Properties();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    properties.load(reader);
                }
                properties.forEach((key, value) -> ENGLISH.put((String) key, (String) value));
            }
        }
        for (Source source : SOURCES.values()) {
            Matcher site = Pattern.compile("\\.eventDescription\\s*\\(").matcher(source.masked());
            while (site.find()) {
                int open = site.end() - 1;
                int close = matching(source.masked(), open);
                String argument = source.text().substring(open + 1, close);
                List<String> built = evaluate(argument, new Context(source, open, Set.of(), true), 0);
                List<String> usable = (built == null ? List.of(String.valueOf(HOLE)) : built).stream()
                    .map(AuditDescriptionCoverageTest::collapse)
                    .filter(sample -> !sample.replace(String.valueOf(HOLE), "").isBlank())
                    .toList();
                if (usable.isEmpty()) {
                    unbuilt(source, open, argument);
                }
                for (String sample : usable) {
                    SAMPLES.computeIfAbsent(sample, k -> new TreeSet<>())
                        .add(source.path() + ":" + (lineOf(source.text(), open)));
                }
            }
        }
        for (String line : Files.readAllLines(PATTERNS, StandardCharsets.UTF_8)) {
            Matcher regex = PATTERN_LINE.matcher(line);
            if (regex.matches()) {
                PORTAL_PATTERNS.add(Pattern.compile(regex.group(1).replace("\\/", "/")));
            }
            Matcher key = KEY_LINE.matcher(line);
            if (key.matches()) {
                PORTAL_KEYS.add(key.group(1));
            }
        }
    }

    @Test
    void theScanFindsTheDescriptionsTheReportNamed() {
        assertThat(SAMPLES.keySet()).as("descriptions built from the source").hasSizeGreaterThan(250);
        assertThat(SAMPLES).containsKeys(
            "Login failed: bad credentials",
            "Super-admin global-view request: " + HOLE + " " + HOLE,
            "Super-admin cross-tenant read: recent-activity (" + HOLE + " rows)",
            // a qualified constant passed to a helper, and a constant naming another one (#968)
            "Cross-hospital lab result read at the performing laboratory",
            // a bundle message read in English, whatever the caller's language (#968)
            "Low stock alert: " + HOLE + " at " + HOLE + " — " + HOLE + " on hand, reorder threshold " + HOLE);
        assertThat(PORTAL_PATTERNS).as("portal patterns").hasSizeGreaterThan(150);
        assertThat(PORTAL_KEYS).hasSameSizeAs(PORTAL_PATTERNS);
    }

    @Test
    void everyDescriptionTheServerWritesHasAPortalPattern() {
        Map<String, Set<String>> missing = new LinkedHashMap<>();
        SAMPLES.forEach((sample, sites) -> {
            if (!covered(sample)) {
                missing.put(sample.replace(String.valueOf(HOLE), "<…>"), sites);
            }
        });
        assertThat(missing)
            .as("audit descriptions with no pattern in hospital-portal/src/app/audit-logs/"
                + "audit-description-patterns.ts (add one, with its text in en/fr/es under AUDIT.DESC)")
            .isEmpty();
    }

    @Test
    void onlyTheListedSitesAreLeftUnbuiltAndTheListOnlyShrinks() {
        Map<String, Set<String>> unlisted = new TreeMap<>(UNBUILT_SITES);
        unlisted.keySet().removeAll(NOT_BUILT.keySet());
        assertThat(unlisted).as("sites whose description the scan cannot build: write the description "
            + "as one expression, or list the file in NOT_BUILT with the reason").isEmpty();
        assertThat(NOT_BUILT.keySet()).as("NOT_BUILT entries that are now built: remove them")
            .isSubsetOf(UNBUILT);
    }

    @Test
    void everyPatternHasItsTextInTheThreeLocales() {
        JsonMapper mapper = JsonMapper.builder().build();
        List<String> missing = new ArrayList<>();
        for (String locale : List.of("en", "fr", "es")) {
            JsonNode root = mapper.readTree(PORTAL.resolve(Path.of("assets", "i18n", locale + ".json")).toFile());
            for (String key : PORTAL_KEYS) {
                JsonNode node = root;
                for (String part : key.split("\\.")) {
                    node = node.path(part);
                }
                if (!node.isString() || node.asString().isBlank()) {
                    missing.add(locale + ": " + key);
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    // ---------------------------------------------------------------- matching

    private static boolean covered(String sample) {
        int holes = (int) sample.chars().filter(c -> c == HOLE).count();
        if (holes == 0) {
            return matchesAny(sample);
        }
        if (holes > 6) {
            return CANDIDATES.stream().anyMatch(value -> matchesAny(sample.replace(String.valueOf(HOLE), value)));
        }
        int combinations = (int) Math.pow(CANDIDATES.size(), holes);
        for (int combination = 0; combination < combinations; combination++) {
            StringBuilder text = new StringBuilder();
            int rest = combination;
            for (char c : sample.toCharArray()) {
                if (c == HOLE) {
                    text.append(CANDIDATES.get(rest % CANDIDATES.size()));
                    rest /= CANDIDATES.size();
                } else {
                    text.append(c);
                }
            }
            if (matchesAny(text.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAny(String text) {
        return PORTAL_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(text).find());
    }

    // --------------------------------------------------------------- evaluation

    /**
     * Where an expression sits: the file, a position in it, the parameters
     * already followed, and whether its value is the WHOLE description (not a
     * part of a concatenation or a message key), so a value the scan cannot
     * build there is a description nobody checked, not a parameter.
     */
    private record Context(Source source, int position, Set<String> trail, boolean whole) {

        Context at(Source where, int at) {
            return new Context(where, at, trail, whole);
        }

        Context part() {
            return new Context(source, position, trail, false);
        }
    }

    /**
     * A description the scan cannot build: its file fails the guard unless it
     * is in {@link #NOT_BUILT}. Recorded for the whole description of a site
     * and for every alternative of it (a ternary branch, a local assignment, a
     * constant, a return, an argument a caller passes for a helper's
     * parameter), so one unbuildable caller is not hidden by another caller's
     * literal.
     */
    private static void unbuilt(Source source, int position, String expression) {
        UNBUILT.add(source.path());
        UNBUILT_SITES.computeIfAbsent(source.path(), k -> new TreeSet<>())
            .add(lineOf(source.text(), position) + ": " + expression.strip().replaceAll("\\s+", " "));
    }

    /** {@code values} as one alternative of a whole description: an unbuildable one is recorded. */
    private static List<String> alternative(List<String> values, Context context, int position, String expression) {
        if (values == null && context.whole()) {
            unbuilt(context.source(), position, expression);
        }
        return orHole(values);
    }

    private static List<String> evaluate(String expression, Context context, int depth) {
        String e = expression.strip();
        String maskedE = mask(e);
        while (e.startsWith("(") && matching(maskedE, 0) == e.length() - 1) {
            e = e.substring(1, e.length() - 1).strip();
            maskedE = mask(e);
        }
        List<String> condition = splitTop(e, maskedE, "?");
        if (condition.size() >= 2 && maskedE.contains(":")) {
            int question = indexOfTop(maskedE, '?');
            String rest = e.substring(question + 1);
            int colon = indexOfTop(mask(rest), ':');
            if (colon >= 0) {
                List<String> out = new ArrayList<>();
                String yes = rest.substring(0, colon);
                String no = rest.substring(colon + 1);
                out.addAll(alternative(evaluate(yes, context, depth + 1), context, context.position(), yes));
                out.addAll(alternative(evaluate(no, context, depth + 1), context, context.position(), no));
                return distinct(out);
            }
        }
        List<String> parts = splitTop(e, maskedE, "+");
        if (parts.size() > 1) {
            List<String> results = List.of("");
            for (String part : parts) {
                List<String> values = orHole(evaluate(part, context.part(), depth + 1));
                List<String> next = new ArrayList<>();
                for (String prefix : results) {
                    for (String value : values) {
                        next.add(prefix + value);
                    }
                }
                results = distinct(next);
                if (results.size() > MAX_SAMPLES) {
                    results = results.subList(0, MAX_SAMPLES);
                }
            }
            return results;
        }
        if (LITERAL.matcher(e).matches()) {
            return List.of(unescape(e.substring(1, e.length() - 1)));
        }
        if ("null".equals(e)) {
            return List.of(String.valueOf(HOLE)); // no description at all: nothing to read
        }
        Matcher format = FORMAT.matcher(e);
        if (format.find()) {
            String fmt = unescape(format.group(1).substring(1, format.group(1).length() - 1));
            return List.of(fmt.replaceAll("%[-0-9.]*[sdf]", String.valueOf(HOLE)));
        }
        Matcher message = ENGLISH_MESSAGE.matcher(e);
        if (message.matches()) {
            return englishMessage(message.group(1), context, depth);
        }
        Matcher sourceMessage = SOURCE_MESSAGE.matcher(e);
        if (sourceMessage.matches()) {
            List<String> args = splitTop(sourceMessage.group(1), mask(sourceMessage.group(1)), ",");
            if (ENGLISH_LOCALE.matcher(args.get(args.size() - 1)).matches()) {
                return englishMessage(sourceMessage.group(1), context, depth);
            }
            return null; // the caller's language: the row would stay in it for every reader
        }
        if (depth >= MAX_DEPTH) {
            return null;
        }
        if (IDENTIFIER.matcher(e).matches()) {
            return variable(e, context, depth);
        }
        Matcher qualified = QUALIFIED_CONSTANT.matcher(e);
        if (qualified.matches()) {
            return qualifiedConstant(qualified.group(1), qualified.group(2), context, depth);
        }
        Matcher call = LOCAL_CALL.matcher(e);
        if (call.matches() && matching(maskedE, e.indexOf('(')) == e.length() - 1) {
            return returnsOf(call.group(1), context, depth);
        }
        return null;
    }

    private static List<String> englishMessage(String arguments, Context context, int depth) {
        List<String> args = splitTop(arguments, mask(arguments), ",");
        List<String> out = new ArrayList<>();
        for (String key : orHole(evaluate(args.get(0), context.part(), depth + 1))) {
            String text = ENGLISH.get(key);
            if (text != null) {
                out.add(text.replace("''", "'").replaceAll("\\{\\d+}", String.valueOf(HOLE)));
            }
        }
        return out.isEmpty() ? null : out;
    }

    private static List<String> variable(String name, Context context, int depth) {
        Source source = context.source();
        Method method = enclosing(source, context.position());
        if (method != null) {
            String body = source.text().substring(method.start(), method.end());
            String maskedBody = source.masked().substring(method.start(), method.end());
            Matcher assignment = Pattern.compile("(?<![\\w.])" + Pattern.quote(name) + "\\s*=(?!=)\\s*").matcher(maskedBody);
            List<String> out = new ArrayList<>();
            boolean assigned = false;
            while (assignment.find()) {
                assigned = true;
                String rest = body.substring(assignment.end());
                String value = splitTop(rest, mask(rest), ";").get(0);
                out.addAll(alternative(evaluate(value, context.at(source, method.start() + 1), depth + 1), context,
                    method.start() + assignment.end(), value));
            }
            if (assigned) {
                return distinct(out);
            }
        }
        List<String> constant = constant(source, name, context, depth);
        if (constant != null) {
            return constant;
        }
        if (method == null || !method.params().contains(name)) {
            return null;
        }
        return argumentsFor(source, method, method.params().indexOf(name), context, depth);
    }

    /** What callers pass for parameter {@code index} of {@code method}. */
    private static List<String> argumentsFor(Source declaring, Method method, int index, Context context, int depth) {
        String key = declaring.path() + "#" + method.name() + "#" + index;
        if (context.trail().contains(key)) {
            return List.of(String.valueOf(HOLE));
        }
        Set<String> trail = new LinkedHashSet<>(context.trail());
        trail.add(key);
        List<Source> callers = new ArrayList<>();
        callers.add(declaring);
        if (!"private".equals(method.visibility())) {
            SOURCES.values().stream()
                .filter(other -> other != declaring && other.masked().contains(declaring.className()))
                .forEach(callers::add);
        }
        List<String> out = new ArrayList<>();
        for (Source caller : callers) {
            Matcher call = Pattern.compile("(?<!\\w)" + Pattern.quote(method.name()) + "\\s*\\(").matcher(caller.masked());
            while (call.find()) {
                int open = call.end() - 1;
                int close = matching(caller.masked(), open);
                if (BODY_START.matcher(caller.masked().substring(close + 1, Math.min(caller.masked().length(), close + 300)))
                    .find()) {
                    continue; // a declaration, not a call
                }
                String arguments = caller.text().substring(open + 1, close);
                List<String> args = splitTop(arguments, mask(arguments), ",");
                if (args.size() != method.params().size()) {
                    continue;
                }
                Context atCall = new Context(caller, open, Set.copyOf(trail), context.whole());
                out.addAll(alternative(evaluate(args.get(index), atCall, depth + 1), atCall, open, args.get(index)));
            }
        }
        return out.isEmpty() ? null : distinct(out);
    }

    private static List<String> returnsOf(String name, Context context, int depth) {
        Source source = context.source();
        List<String> out = new ArrayList<>();
        for (Method method : source.methods()) {
            if (!method.name().equals(name)) {
                continue;
            }
            String body = source.text().substring(method.start(), method.end());
            Matcher ret = Pattern.compile("(?<![\\w.])return\\s+").matcher(source.masked().substring(method.start(), method.end()));
            while (ret.find()) {
                String rest = body.substring(ret.end());
                String value = splitTop(rest, mask(rest), ";").get(0);
                out.addAll(alternative(evaluate(value, context.at(source, method.start() + 1), depth + 1), context,
                    method.start() + ret.end(), value));
            }
        }
        return out.isEmpty() ? null : distinct(out);
    }

    /**
     * The value of the {@code static final String} constant {@code name} of
     * {@code source}, whatever its initializer: a literal, a concatenation or
     * another constant ({@code A = B.C}), followed in the file that declares
     * it. Null when {@code source} declares no such constant.
     */
    private static List<String> constant(Source source, String name, Context context, int depth) {
        Matcher declaration = Pattern.compile("static\\s+final\\s+String\\s+" + Pattern.quote(name) + "\\s*=(?!=)\\s*")
            .matcher(source.masked());
        if (!declaration.find()) {
            return null;
        }
        String rest = source.text().substring(declaration.end());
        String value = splitTop(rest, mask(rest), ";").get(0);
        Context inFile = context.at(source, declaration.end());
        return alternative(evaluate(value, inFile, depth + 1), inFile, declaration.end(), value);
    }

    /** {@code Type.NAME}: the constant as declared by every scanned class named {@code Type}. */
    private static List<String> qualifiedConstant(String type, String name, Context context, int depth) {
        List<String> out = new ArrayList<>();
        for (Source source : SOURCES.values()) {
            if (source.className().equals(type)) {
                List<String> values = constant(source, name, context, depth);
                if (values != null) {
                    out.addAll(values);
                }
            }
        }
        return out.isEmpty() ? null : distinct(out);
    }

    // ------------------------------------------------------------ source model

    private static List<Method> methods(String text, String masked) {
        List<Method> out = new ArrayList<>();
        Matcher m = METHOD.matcher(masked);
        while (m.find()) {
            String name = m.group(2);
            if (NOT_METHODS.contains(name)) {
                continue;
            }
            int open = m.end() - 1;
            int close = matching(masked, open);
            if (close < 0) {
                continue;
            }
            Matcher body = BODY_START.matcher(masked.substring(close + 1, Math.min(masked.length(), close + 300)));
            if (!body.find()) {
                continue;
            }
            int bodyOpen = close + body.end();
            int bodyClose = matching(masked, bodyOpen);
            if (bodyClose < 0) {
                continue;
            }
            out.add(new Method(name, m.group(1), bodyOpen, bodyClose, parameterNames(text.substring(open + 1, close))));
        }
        return out;
    }

    private static List<String> parameterNames(String declaration) {
        List<String> names = new ArrayList<>();
        int depth = 0;
        StringBuilder current = new StringBuilder();
        for (char c : (declaration + ",").toCharArray()) {
            if (c == '<') {
                depth++;
            } else if (c == '>') {
                depth--;
            }
            if (c == ',' && depth == 0) {
                String cleaned = current.toString().replaceAll("@\\w+(\\([^)]*\\))?\\s*", "").strip();
                if (!cleaned.isEmpty()) {
                    String[] words = cleaned.split("\\s+");
                    names.add(words[words.length - 1]);
                }
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        return names;
    }

    private static Method enclosing(Source source, int position) {
        Method best = null;
        for (Method method : source.methods()) {
            if (method.start() < position && position < method.end()
                && (best == null || method.start() > best.start())) {
                best = method;
            }
        }
        return best;
    }

    // ------------------------------------------------------------- text helpers

    /** Comments removed, strings kept. */
    private static String stripComments(String s) {
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3) + 3;
                out.append(s, i, end);
                i = end;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(s, i, j + 1);
                i = j + 1;
            } else if (s.startsWith("//", i)) {
                int end = s.indexOf('\n', i);
                i = end < 0 ? s.length() : end;
            } else if (s.startsWith("/*", i)) {
                int end = s.indexOf("*/", i + 2) + 2;
                for (int k = i; k < end; k++) {
                    out.append(s.charAt(k) == '\n' ? '\n' : ' ');
                }
                i = end;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The same text with every string and char literal's content blanked, so structure can be scanned. */
    private static String mask(String s) {
        char[] out = s.toCharArray();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (s.startsWith("\"\"\"", i)) {
                int end = s.indexOf("\"\"\"", i + 3);
                end = end < 0 ? s.length() : end;
                for (int k = i + 3; k < end; k++) {
                    out[k] = s.charAt(k) == '\n' ? '\n' : 'x';
                }
                i = Math.min(s.length(), end + 3);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < s.length() && s.charAt(j) != c) {
                    j += s.charAt(j) == '\\' ? 2 : 1;
                }
                for (int k = i + 1; k < Math.min(j, s.length()); k++) {
                    out[k] = 'x';
                }
                i = j + 1;
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** The index of the bracket closing the one at {@code open}, in masked text; -1 if none. */
    private static int matching(String masked, int open) {
        int depth = 0;
        for (int j = open; j < masked.length(); j++) {
            char c = masked.charAt(j);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
                if (depth == 0) {
                    return j;
                }
            }
        }
        return -1;
    }

    private static List<String> splitTop(String text, String masked, String separator) {
        List<String> parts = new ArrayList<>();
        int depth = 0;
        int from = 0;
        for (int j = 0; j < masked.length(); j++) {
            char c = masked.charAt(j);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && masked.startsWith(separator, j)) {
                parts.add(text.substring(from, j).strip());
                from = j + separator.length();
            }
        }
        parts.add(text.substring(from).strip());
        return parts;
    }

    private static int indexOfTop(String masked, char wanted) {
        int depth = 0;
        for (int j = 0; j < masked.length(); j++) {
            char c = masked.charAt(j);
            if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']' || c == '}') {
                depth--;
            } else if (depth == 0 && c == wanted) {
                return j;
            }
        }
        return -1;
    }

    private static String unescape(String literal) {
        return literal.replace("\\\"", "\"").replace("\\'", "'").replace("\\n", "\n")
            .replace("\\t", "\t").replace("\\\\", "\\");
    }

    private static List<String> orHole(List<String> values) {
        return values == null || values.isEmpty() ? List.of(String.valueOf(HOLE)) : values;
    }

    private static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }

    private static String collapse(String sample) {
        return sample.replaceAll(HOLE + "+", String.valueOf(HOLE));
    }

    private static int lineOf(String text, int position) {
        int line = 1;
        for (int i = 0; i < position; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
