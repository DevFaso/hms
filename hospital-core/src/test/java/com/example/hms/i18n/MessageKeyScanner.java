package com.example.hms.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * QA D1536 (round 40): finds every message key the main code hands to the
 * message source, so {@link MessageKeyResolutionGuardTest} can hold each one
 * against every bundle.
 *
 * <p>{@link MessageBundleParityTest#everyKeyTheCodeResolvesExists} read only
 * {@code getMessage(…)}, {@code MessageUtil.resolve…(…)} and
 * {@code new …Exception(…)} with the key as first argument. It never saw a key
 * handed to a service's own wrapper ({@code getLocalizedMessage("staff.nurse…",
 * null, locale)}), to {@code RequiredFields.require…(value, field, key)}, or
 * as the second argument of {@code FieldBusinessException(field, key)}: the
 * four staff keys of D1536 went through the first shape and were in no bundle.
 *
 * <p>A <em>sink</em> is a call whose argument is resolved as a key:
 * <ul>
 *   <li>{@code getMessage(key, …)}, {@code MessageUtil.resolveOrRaw(key, …)},
 *       {@code MessageUtil.resolve([locale,] key, …)},
 *       {@code resolveOrNull(locale, key)}, {@code NotificationLocalizer.key(key…)},
 *       {@code createNotification(key, …)};</li>
 *   <li>{@code .require(present, field, key)} and {@code .requireValue(value, field, key)};</li>
 *   <li>{@code new XException(key, …)}, and {@code new FieldBusinessException(field, key, …)};</li>
 *   <li>a <em>wrapper</em>: any method, in any class, one of whose String
 *       parameters flows unchanged into a sink (found to a fixed point, so a
 *       wrapper of a wrapper counts). It is a sink in its own file, and in
 *       another file through {@code Class.method(…)} or a variable declared
 *       with the class's type.</li>
 * </ul>
 * A sink argument that is a key literal, or a same-file String constant
 * holding one, is a <em>literal</em> site. A key built by concatenation (a
 * key-shaped literal fragment such as {@code "cds.severity." + x} or
 * {@code moduleKey + ".title"}, or a key-prefix constant such as
 * {@code ALERT_MSG_PREFIX + code}), at a sink or held in a variable first, is
 * a <em>computed</em> site, enumerated in the guard's registry.
 */
final class MessageKeyScanner {

    static final Path MAIN_JAVA = Path.of("src/main/java");

    /** A message key: two or more dot-separated segments, as {@code MessageUtil.isMessageKey} reads one. */
    static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+");

    /** Dotted literals that are package or class names, never message keys. */
    private static final Pattern NOT_A_KEY = Pattern.compile("(?:java|javax|jakarta|org|com|spring|hibernate)\\..*");

    /** A literal that can be part of a key: letters, digits, '_', '-' and '.', with at least one dot. */
    private static final Pattern KEY_FRAGMENT = Pattern.compile("[A-Za-z0-9_.-]*\\.[A-Za-z0-9_.-]*");

    /** A key-shaped literal ending in a dot, followed by a concatenation: {@code "cds.severity." + …}. */
    private static final Pattern PREFIX_CONCATENATION =
        Pattern.compile("\"([A-Za-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)*\\.)\"\\s*\\+");

    private static final Pattern HAPI_EXCEPTION_IMPORT =
        Pattern.compile("import\\s+ca\\.uhn\\.fhir\\.rest\\.server\\.exceptions\\.(\\w+)\\s*;");

    private static final Pattern STRING_CONSTANT =
        Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

    /** A method declaration with its (annotation-free) parameter list, up to the body's opening brace. */
    private static final Pattern METHOD =
        Pattern.compile("\\b(\\w+)\\s*\\(([^()]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

    private static final Set<String> NOT_A_METHOD = Set.of(
        "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new", "else", "do");

    /** A call, optionally {@code new} and qualified ({@code new com.example.hms.exception.X(}, {@code MessageUtil.resolve(}). */
    private static final Pattern CALL = Pattern.compile("(new\\s+)?((?:[A-Za-z_]\\w*\\s*\\.\\s*)+)?\\b(\\w+)\\s*\\(");

    /** One site: where, the key (null when computed), the key expression, and the sink's name. */
    record Site(String file, int line, String key, String expression, String fragment, String sink) {
        String where() {
            return file + ":" + line;
        }

        boolean computed() {
            return key == null;
        }

        /** The registry entry a computed site belongs to: its file and its first key fragment. */
        String registryId() {
            return file + "|" + fragment;
        }
    }

    /** A method of a class whose parameter {@code index} is resolved as a key. */
    record Wrapper(String className, String method, int index) {
    }

    private MessageKeyScanner() {
    }

    /** Every site in the main tree. */
    static List<Site> scanTree() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                sources.put(file.getFileName().toString(), stripComments(source));
            }
        }
        return scan(sources);
    }

    /** Every site in {@code sources} (file name to source text, comments already stripped or not). */
    static List<Site> scan(Map<String, String> sources) {
        Map<String, String> stripped = new LinkedHashMap<>();
        sources.forEach((name, source) -> stripped.put(name, stripComments(source)));
        Map<String, Map<String, Integer>> wrappers = new HashMap<>();
        stripped.forEach((name, source) -> wrappers.put(className(name), wrappersIn(source)));
        List<Site> out = new ArrayList<>();
        stripped.forEach((name, source) -> out.addAll(sitesIn(name, source, wrappers)));
        return out;
    }

    /** Every wrapper in the tree, for the report. */
    static List<Wrapper> wrappers(Map<String, String> sources) {
        List<Wrapper> out = new ArrayList<>();
        sources.forEach((name, source) -> wrappersIn(stripComments(source))
            .forEach((method, index) -> out.add(new Wrapper(className(name), method, index))));
        return out;
    }

    private static String className(String fileName) {
        return fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
    }

    /** Method name to the index of the String parameter it resolves as a key, to a fixed point. */
    static Map<String, Integer> wrappersIn(String source) {
        Map<String, Integer> found = new LinkedHashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Matcher m = METHOD.matcher(source);
            while (m.find()) {
                String name = m.group(1);
                if (found.containsKey(name) || NOT_A_METHOD.contains(name) || !isDeclaration(source, m.start())) {
                    continue;
                }
                int bodyEnd = matchingBrace(source, m.end());
                String body = source.substring(m.end(), bodyEnd);
                Integer index = keyParameter(m.group(2), body, found);
                if (index != null) {
                    found.put(name, index);
                    changed = true;
                }
            }
        }
        return found;
    }

    /** True when the word before the name is a type (a declaration), not {@code new} or an operator. */
    private static boolean isDeclaration(String source, int nameStart) {
        int i = nameStart - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
        }
        if (i < 0) {
            return false;
        }
        char c = source.charAt(i);
        if (!(Character.isJavaIdentifierPart(c) || c == '>' || c == ']')) {
            return false;
        }
        int end = i + 1;
        while (i >= 0 && Character.isJavaIdentifierPart(source.charAt(i))) {
            i--;
        }
        String word = source.substring(i + 1, end);
        return !word.equals("new") && !word.equals("return") && !word.equals("else") && !word.equals("throw");
    }

    private static Integer keyParameter(String parameters, String body, Map<String, Integer> known) {
        List<String> params = MessageBundleParityTest.topLevelArguments(parameters);
        for (int i = 0; i < params.size(); i++) {
            String[] tokens = params.get(i).replace("final ", "").trim().split("\\s+");
            if (tokens.length < 2 || !tokens[tokens.length - 2].equals("String")) {
                continue;
            }
            String p = Pattern.quote(tokens[tokens.length - 1]);
            List<String> sinks = new ArrayList<>(List.of(
                "\\bgetMessage\\s*\\(\\s*" + p + "\\s*[,)]",
                "\\bresolveOrRaw\\s*\\(\\s*" + p + "\\s*[,)]",
                "\\bresolve\\s*\\(\\s*(?:[\\w.()]+\\s*,\\s*)?" + p + "\\s*[,)]",
                "\\bresolveOrNull\\s*\\(\\s*[\\w.()]+\\s*,\\s*" + p + "\\s*[,)]",
                "\\bNotificationLocalizer\\s*\\.\\s*key\\s*\\(\\s*" + p + "\\s*[,)]",
                // A helper that throws a keyed refusal: requireEndNotBeforeStart(dto, "staff.nurse.endDateAfterStart").
                "\\bnew\\s+(?:[\\w.]*\\.)?(?!FieldBusinessException)\\w*Exception\\s*\\(\\s*" + p + "\\s*[,)]",
                "\\bnew\\s+(?:[\\w.]*\\.)?FieldBusinessException\\s*\\([^,()]*,\\s*" + p + "\\s*[,)]",
                "\\.\\s*require(?:Value)?\\s*\\([^,()]*(?:\\([^()]*\\))?[^,()]*,[^,()]*,\\s*" + p + "\\s*[,)]"));
            for (Map.Entry<String, Integer> w : known.entrySet()) {
                // The parameter in the wrapper's own key position.
                sinks.add("\\b" + Pattern.quote(w.getKey()) + "\\s*\\(" + "(?:[^,()]*(?:\\([^()]*\\))?[^,()]*,){"
                    + w.getValue() + "}\\s*" + p + "\\s*[,)]");
            }
            for (String sink : sinks) {
                if (Pattern.compile(sink).matcher(body).find()) {
                    return i;
                }
            }
        }
        return null;
    }

    private static List<Site> sitesIn(String file, String source, Map<String, Map<String, Integer>> wrappers) {
        Map<String, String> constants = new HashMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        // HAPI FHIR's exceptions take prose, not keys: their constructions are not sinks.
        Set<String> hapiExceptions = new java.util.HashSet<>();
        Matcher hapi = HAPI_EXCEPTION_IMPORT.matcher(source);
        while (hapi.find()) {
            hapiExceptions.add(hapi.group(1));
        }
        Map<String, Integer> local = new HashMap<>(wrappers.getOrDefault(className(file), Map.of()));
        // Variables declared with a wrapper class's type: "PdfLabels t", "CdsCardText text".
        Map<String, String> typed = new HashMap<>();
        for (String cls : wrappers.keySet()) {
            if (wrappers.get(cls).isEmpty() || cls.equals(className(file))) {
                continue;
            }
            Matcher v = Pattern.compile("\\b" + Pattern.quote(cls) + "\\s+(\\w+)\\s*[,;=)]").matcher(source);
            while (v.find()) {
                typed.put(v.group(1), cls);
            }
        }

        List<Site> out = new ArrayList<>();
        List<int[]> sinkArgumentSpans = new ArrayList<>();
        Matcher m = CALL.matcher(source);
        while (m.find()) {
            boolean isNew = m.group(1) != null;
            String qualifier = lastSegment(m.group(2));
            String name = m.group(3);
            if (isNew && (hapiExceptions.contains(name) || "exceptions".equals(qualifier) && m.group(2).contains("fhir"))) {
                continue;
            }
            Integer index = sinkIndex(isNew, qualifier, name, source, m.start(), local, wrappers, typed);
            if (index == null || (!isNew && isDeclaration(source, m.start(3)) && qualifier == null)) {
                continue;
            }
            int close = MessageBundleParityTest.matchingParen(source, m.end());
            if (close < 0) {
                continue;
            }
            List<String> args = MessageBundleParityTest.topLevelArguments(source.substring(m.end(), close));
            if (index == RESOLVE) {
                // MessageUtil.resolve(key, …) or resolve(locale, key, …).
                index = args.size() > 1 && !args.get(0).startsWith("\"") && args.get(0).toLowerCase().contains("locale")
                    ? 1 : 0;
            }
            if ("FieldBusinessException".equals(name) && args.size() < 2) {
                continue;
            }
            if (args.size() <= index) {
                continue;
            }
            String arg = args.get(index);
            int line = lineOf(source, m.start());
            String sink = (isNew ? "new " : "") + (qualifier != null ? qualifier + "." : "") + name;
            Optional<String> key = keyOf(arg, constants);
            if (key.isPresent()) {
                out.add(new Site(file, line, key.get(), arg, null, sink));
            } else {
                computedFragment(arg, constants).ifPresent(fragment ->
                    out.add(new Site(file, line, null, normalize(arg), fragment, sink)));
            }
            int argStart = source.indexOf(arg, m.end());
            if (argStart >= 0) {
                sinkArgumentSpans.add(new int[] {argStart, argStart + arg.length()});
            }
        }

        // A key built first and resolved later: "String key = \"x.y.\" + z;".
        Matcher p = PREFIX_CONCATENATION.matcher(source);
        while (p.find()) {
            if (insideAny(p.start(), sinkArgumentSpans) || NOT_A_KEY.matcher(p.group(1)).matches()) {
                continue;
            }
            out.add(new Site(file, lineOf(source, p.start()), null, normalize(statementAround(source, p.start())),
                p.group(1), "concatenation"));
        }
        for (Map.Entry<String, String> constant : constants.entrySet()) {
            if (!constant.getValue().endsWith(".") || !KEY_FRAGMENT.matcher(constant.getValue()).matches()) {
                continue;
            }
            Matcher u = Pattern.compile("\\b" + Pattern.quote(constant.getKey()) + "\\s*\\+").matcher(source);
            while (u.find()) {
                if (insideAny(u.start(), sinkArgumentSpans)) {
                    continue;
                }
                out.add(new Site(file, lineOf(source, u.start()), null, normalize(statementAround(source, u.start())),
                    constant.getValue(), "concatenation"));
            }
        }
        return out;
    }

    /** Marker for {@code MessageUtil.resolve}, whose key is first or second. */
    private static final int RESOLVE = -1;

    private static Integer sinkIndex(boolean isNew, String qualifier, String name, String source, int at,
                                     Map<String, Integer> local, Map<String, Map<String, Integer>> wrappers,
                                     Map<String, String> typed) {
        if (isNew) {
            if (!name.endsWith("Exception")) {
                return null;
            }
            return "FieldBusinessException".equals(name) ? 1 : 0;
        }
        switch (name) {
            case "getMessage", "resolveOrRaw":
                return 0;
            case "resolveOrNull":
                return 1;
            case "resolve":
                return "MessageUtil".equals(qualifier) || source.contains("import static com.example.hms.utility.MessageUtil")
                    ? Integer.valueOf(RESOLVE) : wrapperIndex(qualifier, name, local, wrappers, typed);
            case "key":
                return "NotificationLocalizer".equals(qualifier) ? Integer.valueOf(0) : wrapperIndex(qualifier, name, local, wrappers, typed);
            case "createNotification":
                return 0;
            case "require", "requireValue":
                // A RequiredFields chain: ".require(present, field, key)".
                return at > 0 && source.charAt(at - 1) == '.' || qualifier != null ? 2 : null;
            default:
                if (qualifier == null && precededByDot(source, at)) {
                    // "builder().message(…)": a method of some other object, not this file's wrapper.
                    return null;
                }
                return wrapperIndex(qualifier, name, local, wrappers, typed);
        }
    }

    /** {@code "com.example.hms.utility.MessageUtil."} to {@code "MessageUtil"}; null for none. */
    private static String lastSegment(String chain) {
        if (chain == null) {
            return null;
        }
        String[] parts = chain.replaceAll("\\s+", "").split("\\.");
        return parts[parts.length - 1];
    }

    private static boolean precededByDot(String source, int at) {
        int i = at - 1;
        while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
            i--;
        }
        return i >= 0 && source.charAt(i) == '.';
    }

    private static Integer wrapperIndex(String qualifier, String name, Map<String, Integer> local,
                                        Map<String, Map<String, Integer>> wrappers, Map<String, String> typed) {
        if (qualifier == null || qualifier.equals("this")) {
            return local.get(name);
        }
        Map<String, Integer> byClass = wrappers.get(qualifier);
        if (byClass == null && typed.containsKey(qualifier)) {
            byClass = wrappers.get(typed.get(qualifier));
        }
        return byClass == null ? null : byClass.get(name);
    }

    private static Optional<String> keyOf(String argument, Map<String, String> constants) {
        String arg = argument.trim();
        String value = null;
        if (arg.length() > 2 && arg.startsWith("\"") && arg.endsWith("\"") && arg.indexOf('"', 1) == arg.length() - 1) {
            value = arg.substring(1, arg.length() - 1);
        } else if (arg.matches("[A-Z][A-Z0-9_]*")) {
            value = constants.get(arg);
        }
        if (value == null || !KEY.matcher(value).matches() || NOT_A_KEY.matcher(value).matches()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /** The first key-shaped literal fragment (or key-prefix constant) of a concatenated argument. */
    /**
     * The key fragment of a concatenated argument: its first top-level operand
     * that is a literal or a key-prefix constant. Empty when the argument is
     * not a concatenation, or when one of its literals cannot be part of a key
     * (a space, a colon: prose such as {@code "Failed to open " + type + ".ndjson"},
     * or a {@code "field:" + message} conflict body whose message is a site of its own).
     */
    private static Optional<String> computedFragment(String argument, Map<String, String> constants) {
        List<String> operands = topLevelOperands(argument);
        if (operands.size() < 2) {
            return Optional.empty();
        }
        String fragment = null;
        for (String operand : operands) {
            String op = operand.trim();
            if (op.startsWith("\"") && op.endsWith("\"") && op.length() >= 2) {
                String text = op.substring(1, op.length() - 1);
                if (!KEY_FRAGMENT.matcher(text).matches() || NOT_A_KEY.matcher(text).matches()) {
                    return Optional.empty();
                }
                if (fragment == null) {
                    fragment = text;
                }
            } else if (fragment == null && constants.containsKey(op)) {
                String value = constants.get(op);
                if (value.endsWith(".") && KEY_FRAGMENT.matcher(value).matches()) {
                    fragment = value;
                }
            }
        }
        return Optional.ofNullable(fragment);
    }

    /** The operands of the top-level {@code +} of an expression. */
    private static List<String> topLevelOperands(String s) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = endOfLiteral(s, i);
            } else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == '?' && depth == 0) {
                // "a == null ? null : \"x.\" + a": the concatenation is the last branch.
                start = i + 1;
                out.clear();
            } else if (ch == ':' && depth == 0) {
                start = i + 1;
                out.clear();
            } else if (ch == '+' && depth == 0) {
                out.add(s.substring(start, i));
                start = i + 1;
            }
        }
        out.add(s.substring(start));
        return out;
    }

    private static boolean insideAny(int at, List<int[]> spans) {
        for (int[] span : spans) {
            if (at >= span[0] && at < span[1]) {
                return true;
            }
        }
        return false;
    }

    private static String statementAround(String source, int at) {
        int start = Math.max(source.lastIndexOf(';', at), Math.max(source.lastIndexOf('{', at), source.lastIndexOf('}', at))) + 1;
        int end = source.indexOf(';', at);
        return source.substring(start, end < 0 ? Math.min(source.length(), at + 120) : end);
    }

    static String normalize(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }

    private static int lineOf(String source, int at) {
        int line = 1;
        for (int i = 0; i < at; i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    /** Index just past the '}' closing the '{' before {@code from}, skipping literals. */
    private static int matchingBrace(String s, int from) {
        int depth = 1;
        for (int i = from; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = endOfLiteral(s, i);
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                return i;
            }
        }
        return s.length();
    }

    private static int endOfLiteral(String s, int open) {
        char quote = s.charAt(open);
        if (quote == '"' && s.startsWith("\"\"\"", open)) {
            int end = s.indexOf("\"\"\"", open + 3);
            return end < 0 ? s.length() : end + 2;
        }
        for (int i = open + 1; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\') {
                i++;
            } else if (ch == quote) {
                return i;
            }
        }
        return s.length();
    }

    /** The source with comments blanked out, newlines kept so line numbers hold. */
    static String stripComments(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        while (i < src.length()) {
            if (src.startsWith("//", i)) {
                int j = src.indexOf('\n', i);
                i = j < 0 ? src.length() : j;
            } else if (src.startsWith("/*", i)) {
                int j = src.indexOf("*/", i + 2);
                int end = j < 0 ? src.length() : j + 2;
                for (int k = i; k < end; k++) {
                    if (src.charAt(k) == '\n') {
                        out.append('\n');
                    }
                }
                i = end;
            } else if (src.charAt(i) == '"' || src.charAt(i) == '\'') {
                int j = endOfLiteral(src, i);
                out.append(src, i, Math.min(src.length(), j + 1));
                i = j + 1;
            } else {
                out.append(src.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The literal arguments of every call of {@code method} in {@code source}, each call's in order. */
    static List<List<String>> literalCallArguments(String source, String method, Map<String, String> constantsOut) {
        String stripped = stripComments(source);
        Map<String, String> constants = new HashMap<>();
        Matcher c = STRING_CONSTANT.matcher(stripped);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        if (constantsOut != null) {
            constantsOut.putAll(constants);
        }
        List<List<String>> calls = new ArrayList<>();
        Matcher m = Pattern.compile("\\b" + Pattern.quote(method) + "\\s*\\(").matcher(stripped);
        while (m.find()) {
            if (isDeclaration(stripped, m.start())) {
                continue;
            }
            int close = MessageBundleParityTest.matchingParen(stripped, m.end());
            if (close < 0) {
                continue;
            }
            List<String> args = new ArrayList<>();
            for (String arg : MessageBundleParityTest.topLevelArguments(stripped.substring(m.end(), close))) {
                String a = arg.trim();
                if (a.startsWith("\"") && a.endsWith("\"") && a.indexOf('"', 1) == a.length() - 1) {
                    args.add(a.substring(1, a.length() - 1));
                } else if (constants.containsKey(a)) {
                    args.add(constants.get(a));
                } else {
                    args.add(null);
                }
            }
            calls.add(args);
        }
        return calls;
    }

    /** Unique, in order. */
    static Set<String> ordered(List<String> values) {
        return new LinkedHashSet<>(values);
    }
}
