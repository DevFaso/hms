package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1295 (round 28, Medium, patient safety): the nurse inbox asked the
 * notifications table for {@code PageRequest.of(0, 20)} with no sort. The
 * database returned 20 rows in whatever order it liked - oldest first, from
 * two days earlier - and today's NEWS2 16 escalation was cut off. The
 * round-26 sweep (D1255) fixed the worklists it read by name; this guard
 * reads the BEHAVIOUR instead: a first page cut from a query with no order is
 * an arbitrary subset, wherever it is.
 *
 * <p>Rule: every {@code PageRequest.of(page, size)} written without a
 * {@code Sort} that reaches a repository method (directly as an argument, or
 * through a local {@code Pageable} variable) must reach a method whose result
 * is ordered: its name carries {@code OrderBy}, or its {@code @Query} has an
 * {@code ORDER BY}. Anything else is a silent cap on rows chosen by the
 * planner - add the order, or list the site below with why its subset does not
 * matter (a count read from {@code getTotalElements()}, an existence probe of
 * one row, a batch worker that drains the whole set).</p>
 */
@DisplayName("D1295: a capped page is cut from an ordered query, never from whatever the database returns first")
class UnsortedPageGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern PAGE_REQUEST = Pattern.compile("\\bPageRequest\\.of\\(");
    private static final Pattern ORDER_BY = Pattern.compile("\\border\\s++by\\b");
    private static final Pattern LOCAL_PAGEABLE = Pattern.compile(
        "\\b(?:Pageable|PageRequest)\\s++(\\w++)\\s*+=\\s*+(?:org\\.springframework\\.data\\.domain\\.)?$");

    /**
     * Sites whose unordered subset is harmless, each with why
     * ({@code File#method -> repositoryMethod}). Shrinks only: a stale entry fails.
     */
    private static final Map<String, String> HARMLESS = Map.ofEntries(
        Map.entry("DevSyntheticDataSeeder#seedHospitalGraph -> findByHospital_Id",
            "dev seeder: reads getTotalElements() only (does the hospital have staff at all)"),
        Map.entry("DevSyntheticDataSeeder#createStaffBundle -> findByHospital_Id",
            "dev seeder: any existing staff per job title is reused; which one does not matter"),
        Map.entry("HospitalAdminDashboardServiceImpl#buildStaffingMetrics -> findByHospitalIdAndActiveTrueExcludingDeletedUsers",
            "PageRequest.of(0, 1) read for getTotalElements() only: a count"),
        Map.entry("HospitalAdminDashboardServiceImpl#buildBillingMetrics -> findByHospital_Id",
            "PageRequest.of(0, Integer.MAX_VALUE): every row, summed - nothing is cut"),
        Map.entry("HospitalAdminDashboardServiceImpl#buildInvoiceAging -> findByHospital_Id",
            "PageRequest.of(0, Integer.MAX_VALUE): every row, bucketed - nothing is cut"),
        Map.entry("HospitalAdminDashboardServiceImpl#buildPaymentCollectionRate -> findByHospital_Id",
            "PageRequest.of(0, Integer.MAX_VALUE): every row, summed - nothing is cut"),
        Map.entry("HospitalAdminDashboardServiceImpl#buildWriteOffs -> findByHospital_Id",
            "PageRequest.of(0, Integer.MAX_VALUE): every row, summed - nothing is cut"));

    @Test
    @DisplayName("every PageRequest without a Sort reaches an ordered repository method")
    void everyCappedPageIsOrdered() throws IOException {
        Map<String, Map<String, Boolean>> ordered = repositoryMethodOrdering();
        assertThat(ordered).as("repositories read").hasSizeGreaterThan(50);
        List<String> unordered = new ArrayList<>();
        Set<String> excused = new LinkedHashSet<>();
        int pages = 0;
        Map<Path, String> sources = new HashMap<>();
        for (Path file : mainFiles()) {
            sources.put(file, SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8)));
        }
        for (Map.Entry<Path, String> entry : sources.entrySet()) {
            Path file = entry.getKey();
            String source = entry.getValue();
            Matcher m = PAGE_REQUEST.matcher(source);
            while (m.find()) {
                int open = m.end() - 1;
                int close = SourceScan.closingParen(source, open);
                if (topLevelArguments(source.substring(open + 1, close)) != 2) {
                    continue;
                }
                Call target = targetMethod(source, m.start(), ordered, sources);
                if (target == null || target.ordered()) {
                    continue;
                }
                pages++;
                String owner = file.getFileName().toString().replace(".java", "");
                String site = owner + "#" + methodName(source, m.start()) + " -> " + target.method();
                if (HARMLESS.containsKey(site)) {
                    excused.add(site);
                    continue;
                }
                unordered.add(site + " (line " + SourceScan.lineOf(source, m.start()) + ")");
            }
        }
        assertThat(pages).as("unsorted pages reaching an unordered repository method").isPositive();
        assertThat(unordered)
            .as("a first page cut from an unordered query drops arbitrary rows (the nurse inbox lost today's"
                + " NEWS2 escalation, QA D1295); add a Sort to the PageRequest or OrderBy to the method"
                + SourceScan.MANDATE)
            .isEmpty();
        assertThat(excused).as("a harmless site that disappeared: delete its entry")
            .containsExactlyInAnyOrderElementsOf(HARMLESS.keySet());
    }

    /** A repository call: the method's name (with the service hop, if any) and whether its result is ordered. */
    private record Call(String method, boolean ordered) {
    }

    /**
     * The repository method the page reaches: the call it is an argument of,
     * or - when it initialises a local - every call in the method that passes
     * that local. A call that is not a repository's is followed one hop into
     * the method it names (round 28: a controller hands
     * {@code PageRequest.of(page, size)} to a service that hands it on). The
     * first unordered repository call wins; null when none is reached.
     */
    private static Call targetMethod(String source, int at, Map<String, Map<String, Boolean>> ordered,
                                     Map<Path, String> sources) {
        List<Integer> uses = new ArrayList<>();
        String text = source;
        uses.add(at);
        int lineStart = source.lastIndexOf('\n', at) + 1;
        Matcher local = LOCAL_PAGEABLE.matcher(source.substring(lineStart, at));
        if (local.find()) {
            String variable = local.group(1);
            text = AppointmentSlotRulesGuardTest.enclosingMethod(source, at);
            uses.clear();
            int from = text.indexOf(variable + " =");
            Matcher use = Pattern.compile("\\b" + Pattern.quote(variable) + "\\b").matcher(text);
            use.region(Math.max(0, from) + variable.length() + 2, text.length());
            while (use.find()) {
                uses.add(use.start());
            }
        }
        Call found = null;
        for (int use : uses) {
            Call call = repositoryCall(source, text, use, ordered);
            if (call == null) {
                call = followedCall(calledName(source, text, use), ordered, sources);
            }
            if (call != null && (found == null || !call.ordered())) {
                found = call;
            }
        }
        return found;
    }

    /**
     * One hop into the method a non-repository call names: in the file of the
     * receiver's declared type (the class itself, or a class implementing that
     * interface), the repository call the page parameter reaches. Null when
     * none, or when the receiver's type is unknown.
     */
    private static Call followedCall(String[] typeAndName, Map<String, Map<String, Boolean>> ordered,
                                     Map<Path, String> sources) {
        if (typeAndName == null) {
            return null;
        }
        String name = typeAndName[1];
        Pattern declaration = Pattern.compile("\\b" + Pattern.quote(name)
            + "\\s*+\\(([^()]*+)\\)\\s*+(?:throws\\s++[\\w.,\\s]++)?\\{");
        Pattern pageParameter = Pattern.compile("\\b(?:Pageable|PageRequest)\\s++(\\w++)");
        Call found = null;
        for (String file : sources.values()) {
            if (!ownsType(file, typeAndName[0])) {
                continue;
            }
            Matcher d = declaration.matcher(file);
            while (d.find()) {
                Matcher parameter = pageParameter.matcher(d.group(1));
                if (!parameter.find()) {
                    continue;
                }
                int open = d.end() - 1;
                String body = file.substring(open, SourceScan.closingBrace(file, open) + 1);
                Matcher use = Pattern.compile("\\b" + Pattern.quote(parameter.group(1)) + "\\b").matcher(body);
                while (use.find()) {
                    Call call = repositoryCall(file, body, use.start(), ordered);
                    if (call != null && (found == null || !call.ordered())) {
                        found = new Call(name + " -> " + call.method(), call.ordered());
                    }
                }
            }
        }
        return found;
    }

    /** The file declares {@code type} as a class, or a class implementing it. */
    private static boolean ownsType(String file, String type) {
        Pattern word = Pattern.compile("\\b" + Pattern.quote(type) + "\\b");
        if (Pattern.compile("\\bclass\\s++" + Pattern.quote(type) + "\\b").matcher(file).find()) {
            return true;
        }
        int implementsAt = file.indexOf(" implements ");
        while (implementsAt >= 0) {
            int brace = file.indexOf('{', implementsAt);
            if (brace > 0 && word.matcher(file.substring(implementsAt, brace)).find()) {
                return true;
            }
            implementsAt = file.indexOf(" implements ", implementsAt + 1);
        }
        return false;
    }

    /**
     * The receiver's declared type and the method name of the call whose
     * argument list holds offset {@code at}, or null.
     */
    private static String[] calledName(String file, String text, int at) {
        int open = enclosingOpenParen(text, at);
        if (open < 0) {
            return null;
        }
        int start = open;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        int dot = start - 1;
        while (dot >= 0 && Character.isWhitespace(text.charAt(dot))) {
            dot--;
        }
        if (start == open || dot < 0 || text.charAt(dot) != '.') {
            return null;
        }
        int receiverStart = dot;
        while (receiverStart > 0 && Character.isJavaIdentifierPart(text.charAt(receiverStart - 1))) {
            receiverStart--;
        }
        String type = declaredType(file, text.substring(receiverStart, dot));
        return type == null ? null : new String[]{type, text.substring(start, open)};
    }

    /**
     * The repository call whose argument list holds offset {@code at} of
     * {@code text}: the receiver before {@code .method(} must be declared in
     * the file with a repository type, and that repository must have the method.
     */
    private static Call repositoryCall(String file, String text, int at, Map<String, Map<String, Boolean>> ordered) {
        int open = enclosingOpenParen(text, at);
        if (open < 0) {
            return null;
        }
        int start = open;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        if (start == open) {
            return null;
        }
        String name = text.substring(start, open);
        int dot = start - 1;
        while (dot >= 0 && Character.isWhitespace(text.charAt(dot))) {
            dot--;
        }
        if (dot < 0 || text.charAt(dot) != '.') {
            return null;
        }
        int end = dot;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int receiverStart = end;
        while (receiverStart > 0 && Character.isJavaIdentifierPart(text.charAt(receiverStart - 1))) {
            receiverStart--;
        }
        String receiver = text.substring(receiverStart, end);
        if (receiver.isEmpty()) {
            return null;
        }
        Map<String, Boolean> methods = ordered.get(declaredType(file, receiver));
        if (methods == null || !methods.containsKey(name)) {
            return null;
        }
        return new Call(name, methods.get(name));
    }

    /** The simple type the file declares {@code variable} with (a field, parameter or local), or null. */
    private static String declaredType(String file, String variable) {
        Matcher declared = Pattern.compile("\\b([A-Z]\\w*+)\\s++" + Pattern.quote(variable) + "\\s*+[;=,)]")
            .matcher(file);
        return declared.find() ? declared.group(1) : null;
    }

    /** The offset of the unclosed {@code (} whose argument list holds {@code at}, or -1. */
    private static int enclosingOpenParen(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    return i;
                }
                depth--;
            } else if (c == ';' || c == '{' || c == '}') {
                return -1;
            }
        }
        return -1;
    }

    private static int topLevelArguments(String arguments) {
        if (arguments.isBlank()) {
            return 0;
        }
        int depth = 0;
        int count = 1;
        for (char c : arguments.toCharArray()) {
            if (c == '(' || c == '<' || c == '[') {
                depth++;
            } else if (c == ')' || c == '>' || c == ']') {
                depth--;
            } else if (c == ',' && depth == 0) {
                count++;
            }
        }
        return count;
    }

    private static String methodName(String source, int at) {
        String method = AppointmentSlotRulesGuardTest.enclosingMethod(source, at);
        Matcher name = Pattern.compile("(\\w+)\\s*+\\(").matcher(method);
        return name.find() ? name.group(1) : "?";
    }

    /**
     * Per repository, every method that takes a {@link Pageable}, by name: true when
     * every such overload is ordered (name or query), false when one is not.
     */
    private static Map<String, Map<String, Boolean>> repositoryMethodOrdering() throws IOException {
        Map<String, Map<String, Boolean>> out = new HashMap<>();
        for (Path file : filesUnder(MAIN.resolve("repository"))) {
            Class<?> type = load(file);
            if (type == null || !type.isInterface()) {
                continue;
            }
            for (Method method : type.getMethods()) {
                if (!List.of(method.getParameterTypes()).contains(Pageable.class)) {
                    continue;
                }
                out.computeIfAbsent(type.getSimpleName(), k -> new HashMap<>())
                    .merge(method.getName(), isOrdered(method), Boolean::logicalAnd);
            }
        }
        return out;
    }

    private static boolean isOrdered(Method method) {
        if (method.getName().contains("OrderBy")) {
            return true;
        }
        Query query = method.getAnnotation(Query.class);
        return query != null && ORDER_BY.matcher(query.value().toLowerCase(Locale.ROOT)).find();
    }

    private static Class<?> load(Path file) {
        String rel = MAIN.relativize(file).toString().replace('\\', '/').replace(".java", "");
        try {
            return Class.forName("com.example.hms." + rel.replace('/', '.'));
        } catch (ClassNotFoundException | NoClassDefFoundError e) {
            return null;
        }
    }

    private static List<Path> mainFiles() throws IOException {
        return filesUnder(MAIN);
    }

    private static List<Path> filesUnder(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
