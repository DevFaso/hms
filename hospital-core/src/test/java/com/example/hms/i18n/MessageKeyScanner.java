package com.example.hms.i18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * null, locale)}), to {@code RequiredFields.require…(value, field, key)}, or as
 * the second argument of {@code FieldBusinessException(field, key)}: the staff
 * keys of D1536 went through the first shape and were in no bundle.
 *
 * <p>A <em>sink</em> is a call whose argument is resolved as a key:
 * <ul>
 *   <li>{@code getMessage(key, …)}, {@code MessageUtil.resolveOrRaw(key, …)},
 *       {@code MessageUtil.resolve([locale,] key, …)}, {@code resolveOrNull(locale, key)},
 *       {@code NotificationLocalizer.key(key…)}, {@code createNotification(key, …)};</li>
 *   <li>{@code .require(present, field, key)} and {@code .requireValue(value, field, key)};</li>
 *   <li>{@code new XException(key, …)}, and {@code new FieldBusinessException(field, key, …)};</li>
 *   <li>a <em>wrapper</em>: any method one of whose String parameters flows
 *       unchanged into a sink (found to a fixed point, so a wrapper of a wrapper
 *       counts), keyed by its name and arity, so overloads with the key in
 *       different positions are each read. It is a sink in its own file, and in
 *       another file through {@code Class.method(…)} or a variable declared with
 *       the class's type.</li>
 * </ul>
 *
 * <p>Each argument in a key position is resolved: a literal; a constant of the
 * file, of another class ({@code LabOrderMapper.DELETED_TEST_LABEL}) or
 * statically imported; a local variable or a parameter assigned in the method
 * (every value it is given); either branch of a {@code ?:}. The result is a
 * <em>literal</em> site per key. A key built by concatenation ({@code "cds.severity." + x},
 * {@code moduleKey + ".title"}, {@code ALERT_MSG_PREFIX + code}), at a sink or
 * held in a variable first, is a <em>computed</em> site, enumerated in
 * {@link ComputedKeys}. Anything else in a key position of a message sink (a
 * key returned by a method, read from a record) is an <em>unresolved</em> site,
 * which the guard lists too: nothing is skipped silently. A parameter of a
 * wrapper in its key position is not a site: its callers are.
 *
 * <p>Sources are keyed by their path under {@code src/main/java}: two classes
 * with one simple name (the two {@code EmpiMapper}s) are both read, and a class
 * named in another file is the one that file imports or shares a package with.
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

    private static final Pattern IMPORT = Pattern.compile("import\\s+([\\w.]+)\\.(\\w+)\\s*;");

    private static final Pattern STATIC_IMPORT = Pattern.compile("import\\s+static\\s+([\\w.]+)\\.(\\w+)\\s*;");

    private static final Pattern PACKAGE = Pattern.compile("package\\s+([\\w.]+)\\s*;");

    private static final Pattern STRING_CONSTANT =
        Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

    private static final Pattern CONSTANT_EXPRESSION =
        Pattern.compile("static\\s+final\\s+String\\s+(\\w+)\\s*=\\s*([^;]+);");

    private static final Pattern INTERFACE_CONSTANT =
        Pattern.compile("(?m)^\\s*(?:public\\s+)?String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*;");

    /** A method declaration with its (annotation-free) parameter list, up to the body's opening brace. */
    private static final Pattern METHOD =
        Pattern.compile("\\b(\\w+)\\s*\\(([^()]*)\\)\\s*(?:throws\\s+[\\w.,\\s]+)?\\{");

    private static final Set<String> NOT_A_METHOD = Set.of(
        "if", "for", "while", "switch", "catch", "synchronized", "try", "return", "new", "else", "do");

    /** The name of a call: an identifier followed by '('. Its qualifier and {@code new} are read by {@link CallMatcher}. */
    private static final Pattern CALL_NAME = Pattern.compile("\\b([A-Za-z_]\\w*+)\\s*+\\(");

    /**
     * A call, optionally {@code new} and qualified ({@code new com.example.hms.exception.X(},
     * {@code MessageUtil.resolve(}). Rex on #1096: the regex it replaces repeated a group per
     * qualifier segment, and a long qualified name overflowed the stack (StackOverflowError),
     * aborting the whole scan. The name is matched by a flat regex and the qualifier is read
     * backwards by hand, in linear time, with no recursion. Groups as before: 1 is
     * {@code new} (or null), 2 the qualifier with its dots (or null), 3 the name.
     */
    static final class CallMatcher {
        private final String source;
        private final Matcher names;
        private int start;
        private String isNew;
        private String chain;

        CallMatcher(String source) {
            this.source = source;
            this.names = CALL_NAME.matcher(source);
        }

        boolean find() {
            if (!names.find()) {
                return false;
            }
            int chainStart = names.start(1);
            int i = chainStart - 1;
            while (true) {
                int j = skipSpaceBack(i);
                if (j < 0 || source.charAt(j) != '.') {
                    break;
                }
                int k = skipSpaceBack(j - 1);
                int end = k + 1;
                while (k >= 0 && Character.isJavaIdentifierPart(source.charAt(k))) {
                    k--;
                }
                if (k + 1 == end || !Character.isJavaIdentifierStart(source.charAt(k + 1))) {
                    break;
                }
                chainStart = k + 1;
                i = k;
            }
            chain = chainStart < names.start(1) ? source.substring(chainStart, names.start(1)) : null;
            start = chainStart;
            isNew = null;
            int before = skipSpaceBack(chainStart - 1);
            if (before >= 2 && before < chainStart - 1 && source.startsWith("new", before - 2)
                    && (before - 3 < 0 || !Character.isJavaIdentifierPart(source.charAt(before - 3)))) {
                isNew = source.substring(before - 2, chainStart);
                start = before - 2;
            }
            return true;
        }

        private int skipSpaceBack(int from) {
            int i = from;
            while (i >= 0 && Character.isWhitespace(source.charAt(i))) {
                i--;
            }
            return i;
        }

        String group(int group) {
            return switch (group) {
                case 1 -> isNew;
                case 2 -> chain;
                case 3 -> names.group(1);
                default -> throw new IllegalArgumentException(String.valueOf(group));
            };
        }

        int start() {
            return start;
        }

        int start(int group) {
            if (group != 3) {
                throw new IllegalArgumentException(String.valueOf(group));
            }
            return names.start(1);
        }

        int end() {
            return names.end();
        }
    }

    /** The exceptions that resolve their message as a key, and every class that extends one. */
    private static final Set<String> KEYED_EXCEPTION_ROOTS = Set.of(
        "BusinessException", "FieldBusinessException", "ResourceNotFoundException", "ClientSafeAccessDeniedException");

    /** Where a key sits in a refusal that must be handed a key, never a message resolved first. */
    private static final Map<String, Integer> REFUSAL_KEY_POSITION = Map.of(
        "FieldBusinessException", 1, "BusinessException", 0, "BusinessRuleException", 0,
        "require", 2, "requireValue", 2);

    enum Kind { LITERAL, COMPUTED, UNRESOLVED }

    /** One site: where, the key (literal sites only), the key expression, its fragment (computed sites) and the sink. */
    record Site(String file, int line, Kind kind, String key, String expression, String fragment, String sink,
                int occurrence) {
        String where() {
            return file + ":" + line;
        }

        boolean computed() {
            return kind == Kind.COMPUTED;
        }

        /**
         * The registry entry of a computed or unresolved site: its file, its expression and,
         * when the same expression appears more than once in the file, its occurrence. No
         * line number, so an edit elsewhere in the file does not move it; never shared.
         */
        String registryId() {
            return file + "|" + expression + (occurrence > 1 ? " #" + occurrence : "");
        }
    }

    /** A method whose parameter {@code index} is resolved as a key; {@code varargs} when its last parameter is. */
    record Wrapper(int arity, boolean varargs, int index) {
        boolean accepts(int arguments) {
            return varargs ? arguments >= arity - 1 : arguments == arity;
        }
    }

    /** What one source file declares, as the scan reads it. */
    private record Unit(String path, String pkg, String simpleName, String source, Map<String, String> constants,
                        Map<String, String> imports, Map<String, String> staticImports, Set<String> hapi,
                        List<int[]> methodSpans, List<String> methodParams) {
    }

    private MessageKeyScanner() {
    }

    /** Every site in the main tree. */
    static List<Site> scanTree() throws IOException {
        return scan(mainSources());
    }

    /** Every main source, keyed by its path under {@code src/main/java} ({@code /}-separated). */
    static Map<String, String> mainSources() throws IOException {
        Map<String, String> sources = new TreeMap<>();
        try (Stream<Path> files = Files.walk(MAIN_JAVA)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                sources.put(MAIN_JAVA.relativize(file).toString().replace('\\', '/'),
                    Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        return sources;
    }

    /** Every site in {@code sources} (path to source text). */
    static List<Site> scan(Map<String, String> sources) {
        Tree tree = new Tree(sources);
        List<Site> out = new ArrayList<>();
        for (Unit unit : tree.units.values()) {
            out.addAll(tree.sitesIn(unit));
        }
        return numbered(out);
    }

    /**
     * Every refusal handed a message resolved first: {@code getMessage(…)} or
     * {@code getLocalizedMessage(…)} in the key position of a {@code FieldBusinessException},
     * {@code BusinessException} or {@code BusinessRuleException}, or of a {@code RequiredFields}
     * check. The lookup then runs before the refusal is decided (D1536: on every save, valid
     * fields or not) and a missing key is a 500; the key itself must be passed.
     */
    static List<String> eagerRefusals(Map<String, String> sources) {
        List<String> out = new ArrayList<>();
        Pattern resolved = Pattern.compile("^(?:this\\.)?(?:[\\w.]+\\.)?(?:getMessage|getLocalizedMessage)\\s*\\(");
        for (Map.Entry<String, String> file : sources.entrySet()) {
            String source = stripComments(file.getValue());
            CallMatcher m = new CallMatcher(source);
            while (m.find()) {
                boolean isNew = m.group(1) != null;
                String name = m.group(3);
                Integer position = REFUSAL_KEY_POSITION.get(name);
                boolean refusal = position != null
                    && (isNew ? name.endsWith("Exception") : !name.endsWith("Exception") && precededByDot(source, m.start(3)));
                if (!refusal) {
                    continue;
                }
                int close = MessageBundleParityTest.matchingParen(source, m.end());
                if (close < 0) {
                    continue;
                }
                List<String> args = MessageBundleParityTest.topLevelArguments(source.substring(m.end(), close));
                if (args.size() > position && resolved.matcher(args.get(position).trim()).find()) {
                    out.add(file.getKey() + ":" + lineOf(source, m.start()) + " " + name + " <- "
                        + normalize(args.get(position)));
                }
            }
        }
        return out;
    }

    /** Numbers repeated expressions within a file, so each computed or unresolved site has its own id. */
    private static List<Site> numbered(List<Site> sites) {
        Map<String, Integer> seen = new HashMap<>();
        List<Site> out = new ArrayList<>(sites.size());
        for (Site s : sites) {
            if (s.kind() == Kind.LITERAL) {
                out.add(s);
                continue;
            }
            int n = seen.merge(s.kind() + "|" + s.file() + "|" + s.expression(), 1, Integer::sum);
            out.add(new Site(s.file(), s.line(), s.kind(), s.key(), s.expression(), s.fragment(), s.sink(), n));
        }
        return out;
    }

    /** The whole tree: every unit, its wrappers, and which classes are keyed exceptions. */
    private static final class Tree {
        final Map<String, Unit> units = new LinkedHashMap<>();
        /** Fully qualified class name to path. */
        final Map<String, String> byQualifiedName = new HashMap<>();
        /** Simple class name to every path declaring it. */
        final Map<String, List<String>> bySimpleName = new HashMap<>();
        final Map<String, Map<String, List<Wrapper>>> wrappers = new HashMap<>();
        final Set<String> keyedExceptions = new HashSet<>(KEYED_EXCEPTION_ROOTS);

        Tree(Map<String, String> sources) {
            sources.forEach((path, text) -> {
                Unit unit = unit(path, stripComments(text));
                units.put(path, unit);
                byQualifiedName.put(unit.pkg().isEmpty() ? unit.simpleName() : unit.pkg() + "." + unit.simpleName(), path);
                bySimpleName.computeIfAbsent(unit.simpleName(), k -> new ArrayList<>()).add(path);
            });
            // To a fixed point across classes: a wrapper may call another class's wrapper, through
            // the class's name or a variable of its type ("guard.requireNotDischargedWithoutVisit").
            units.values().forEach(u -> wrappers.put(u.path(), wrappersIn(u.source())));
            for (int pass = 0; pass < 4; pass++) {
                boolean changed = false;
                for (Unit u : units.values()) {
                    Map<String, List<Wrapper>> found = wrappersIn(u.source(), crossWrappers(u));
                    if (!found.equals(wrappers.get(u.path()))) {
                        wrappers.put(u.path(), found);
                        changed = true;
                    }
                }
                if (!changed) {
                    break;
                }
            }
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Unit u : units.values()) {
                    Matcher ext = Pattern.compile("class\\s+(\\w+)[^{]*?\\bextends\\s+(?:[\\w.]+\\.)?(\\w+)").matcher(u.source());
                    while (ext.find()) {
                        if (keyedExceptions.contains(ext.group(2)) && keyedExceptions.add(ext.group(1))) {
                            grew = true;
                        }
                    }
                }
            }
        }

        /**
         * The other classes' wrappers {@code unit} can call: qualifier (a class name or a variable
         * of the class's type) to the wrapper names and their key positions.
         */
        private Map<String, Map<String, Set<Integer>>> crossWrappers(Unit unit) {
            Map<String, Map<String, Set<Integer>>> out = new HashMap<>();
            Matcher decl = Pattern.compile("\\b([A-Z]\\w*)(?:\\s+(\\w+)\\s*[,;=)]|\\s*\\.)").matcher(unit.source());
            while (decl.find()) {
                String path = resolveClass(unit, decl.group(1));
                if (path == null || path.equals(unit.path())) {
                    continue;
                }
                Map<String, List<Wrapper>> theirs = wrappers.getOrDefault(path, Map.of());
                if (theirs.isEmpty()) {
                    continue;
                }
                String qualifier = decl.group(2) != null ? decl.group(2) : decl.group(1);
                Map<String, Set<Integer>> byName = out.computeIfAbsent(qualifier, k -> new HashMap<>());
                theirs.forEach((name, list) -> list.forEach(w ->
                    byName.computeIfAbsent(name, k -> new java.util.TreeSet<>()).add(w.index())));
            }
            return out;
        }

        /** The path of the class {@code name} as {@code from} sees it: imported, same package, or the only one. */
        String resolveClass(Unit from, String name) {
            if (name.contains(".")) {
                return byQualifiedName.get(name);
            }
            String imported = from.imports().get(name);
            if (imported != null) {
                return byQualifiedName.get(imported);
            }
            String samePackage = byQualifiedName.get(from.pkg() + "." + name);
            if (samePackage != null) {
                return samePackage;
            }
            List<String> candidates = bySimpleName.getOrDefault(name, List.of());
            return candidates.size() == 1 ? candidates.get(0) : null;
        }

        List<Site> sitesIn(Unit unit) {
            String source = unit.source();
            String file = unit.path();
            Map<String, List<Wrapper>> local = wrappers.getOrDefault(file, Map.of());
            // Variables declared with a wrapper class's type: "PdfLabels t", "CdsCardText text".
            Map<String, String> typed = new HashMap<>();
            Matcher decl = Pattern.compile("\\b([A-Z]\\w*)\\s+(\\w+)\\s*[,;=)]").matcher(source);
            while (decl.find()) {
                String path = resolveClass(unit, decl.group(1));
                if (path != null && !path.equals(file) && !wrappers.getOrDefault(path, Map.of()).isEmpty()) {
                    typed.put(decl.group(2), path);
                }
            }

            List<Site> out = new ArrayList<>();
            List<int[]> sinkArgumentSpans = new ArrayList<>();
            CallMatcher m = new CallMatcher(source);
            while (m.find()) {
                boolean isNew = m.group(1) != null;
                String qualifier = lastSegment(m.group(2));
                String name = m.group(3);
                if (isNew && (unit.hapi().contains(name) || m.group(2) != null && m.group(2).contains("fhir"))) {
                    continue;
                }
                if (!isNew && qualifier == null && isDeclaration(source, m.start(3))) {
                    continue;
                }
                int close = MessageBundleParityTest.matchingParen(source, m.end());
                if (close < 0) {
                    continue;
                }
                List<String> args = MessageBundleParityTest.topLevelArguments(source.substring(m.end(), close));
                Sink sink = sink(unit, isNew, qualifier, name, m.start(), args.size(), local, typed);
                if (sink == null) {
                    continue;
                }
                if ("FieldBusinessException".equals(name) && args.size() < 2) {
                    continue;
                }
                for (int index : sink.indices()) {
                    if (index == RESOLVE) {
                        // MessageUtil.resolve(key, …) or resolve(locale, key, …).
                        index = args.size() > 1 && !args.get(0).startsWith("\"")
                            && args.get(0).toLowerCase(java.util.Locale.ROOT).contains("locale") ? 1 : 0;
                    }
                    if (args.size() <= index) {
                        continue;
                    }
                    String arg = args.get(index);
                    int at = source.indexOf(arg, m.end());
                    if (at >= 0) {
                        sinkArgumentSpans.add(new int[] {at, at + arg.length()});
                    }
                    String sinkName = (isNew ? "new " : "") + (qualifier != null ? qualifier + "." : "") + name;
                    out.addAll(resolveArgument(unit, arg, at < 0 ? m.start() : at, lineOf(source, m.start()), sinkName,
                        sink.reportUnresolved(), local));
                }
            }

            // A key built first and resolved later: "String key = \"x.y.\" + z;".
            Matcher p = PREFIX_CONCATENATION.matcher(source);
            while (p.find()) {
                if (insideAny(p.start(), sinkArgumentSpans) || NOT_A_KEY.matcher(p.group(1)).matches()) {
                    continue;
                }
                out.add(new Site(file, lineOf(source, p.start()), Kind.COMPUTED, null,
                    normalize(statementAround(source, p.start())), p.group(1), "concatenation", 1));
            }
            for (Map.Entry<String, String> constant : unit.constants().entrySet()) {
                if (!constant.getValue().endsWith(".") || !KEY_FRAGMENT.matcher(constant.getValue()).matches()) {
                    continue;
                }
                Matcher u = Pattern.compile("\\b" + Pattern.quote(constant.getKey()) + "\\s*\\+").matcher(source);
                while (u.find()) {
                    if (insideAny(u.start(), sinkArgumentSpans)) {
                        continue;
                    }
                    out.add(new Site(file, lineOf(source, u.start()), Kind.COMPUTED, null,
                        normalize(statementAround(source, u.start())), constant.getValue(), "concatenation", 1));
                }
            }
            return out;
        }

        /** The key positions of a sink (a wrapper may take several keys) and whether an unknown key is listed. */
        private record Sink(List<Integer> indices, boolean reportUnresolved) {
            Sink(int index, boolean reportUnresolved) {
                this(List.of(index), reportUnresolved);
            }
        }

        private Sink sink(Unit unit, boolean isNew, String qualifier, String name, int at, int arguments,
                          Map<String, List<Wrapper>> local, Map<String, String> typed) {
            String source = unit.source();
            if (isNew) {
                if (!name.endsWith("Exception")) {
                    return null;
                }
                return new Sink("FieldBusinessException".equals(name) ? 1 : 0, keyedExceptions.contains(name));
            }
            switch (name) {
                case "getMessage":
                    // MessageSource.getMessage(code, args, [default,] locale); a getMessage(id) of
                    // some other service is not a lookup.
                    return arguments >= 3 ? new Sink(0, true) : null;
                case "resolveOrRaw":
                    return new Sink(0, true);
                case "resolveOrNull":
                    return new Sink(1, true);
                case "resolve":
                    if ("MessageUtil".equals(qualifier) || source.contains("import static com.example.hms.utility.MessageUtil")) {
                        return new Sink(RESOLVE, true);
                    }
                    break;
                case "key":
                    if ("NotificationLocalizer".equals(qualifier)) {
                        return new Sink(0, true);
                    }
                    break;
                case "createNotification":
                    return new Sink(0, true);
                case "ofKey":
                    if ("BusinessRuleException".equals(qualifier)) {
                        return new Sink(0, true);
                    }
                    break;
                case "require", "requireValue":
                    // A RequiredFields chain: ".require(present, field, key)".
                    if (qualifier == null && precededByDot(source, at)) {
                        return new Sink(2, true);
                    }
                    break;
                default:
                    break;
            }
            Map<String, List<Wrapper>> byName;
            if (qualifier == null || qualifier.equals("this")) {
                if (precededByDot(source, at)) {
                    // "builder().message(…)": a method of some other object, not this file's wrapper.
                    return null;
                }
                byName = local;
            } else {
                String path = typed.containsKey(qualifier) ? typed.get(qualifier) : resolveClass(unit, qualifier);
                byName = path == null ? null : wrappers.get(path);
            }
            if (byName == null) {
                return null;
            }
            return wrapperFor(byName.getOrDefault(name, List.of()), arguments);
        }

        /** The overload a call with {@code arguments} arguments reaches: an exact arity before a varargs one. */
        private static Sink wrapperFor(List<Wrapper> overloads, int arguments) {
            List<Integer> exact = overloads.stream()
                .filter(w -> !w.varargs() && w.arity() == arguments).map(Wrapper::index).distinct().toList();
            if (!exact.isEmpty()) {
                return new Sink(exact, true);
            }
            int best = overloads.stream().filter(w -> w.varargs() && w.accepts(arguments))
                .mapToInt(Wrapper::arity).max().orElse(-1);
            List<Integer> varargs = overloads.stream().filter(w -> w.varargs() && w.arity() == best)
                .map(Wrapper::index).distinct().toList();
            return varargs.isEmpty() ? null : new Sink(varargs, true);
        }

        /** The sites one argument in a key position stands for. */
        private List<Site> resolveArgument(Unit unit, String argument, int at, int line, String sink,
                                           boolean reportUnresolved, Map<String, List<Wrapper>> local) {
            String expression = normalize(argument);
            List<Site> out = new ArrayList<>();
            Resolution r = resolve(unit, argument.trim(), at, local, 0);
            for (String key : r.keys()) {
                out.add(new Site(unit.path(), line, Kind.LITERAL, key, expression, null, sink, 1));
            }
            if (r.fragment() != null) {
                out.add(new Site(unit.path(), line, Kind.COMPUTED, null, expression, r.fragment(), sink, 1));
            }
            if (r.unresolved() && reportUnresolved) {
                out.add(new Site(unit.path(), line, Kind.UNRESOLVED, null, expression, null, sink, 1));
            }
            return out;
        }

        private record Resolution(Set<String> keys, String fragment, boolean unresolved) {
            static Resolution none() {
                return new Resolution(Set.of(), null, false);
            }

            static Resolution unknown() {
                return new Resolution(Set.of(), null, true);
            }

            Resolution and(Resolution other) {
                Set<String> keys = new LinkedHashSet<>(this.keys);
                keys.addAll(other.keys);
                return new Resolution(keys, fragment != null ? fragment : other.fragment, unresolved || other.unresolved);
            }
        }

        private Resolution resolve(Unit unit, String arg, int at, Map<String, List<Wrapper>> local, int depth) {
            if (depth > 4) {
                return Resolution.unknown();
            }
            String a = stripParens(arg.trim());
            if (a.equals("null") || a.isEmpty() || a.equals("true") || a.equals("false")
                    || a.matches("-?\\d[\\d_.]*[LlDdFf]?")) {
                // Not a String: the call reached an overload whose key sits elsewhere.
                return Resolution.none();
            }
            int question = topLevelIndex(a, '?');
            if (question > 0) {
                int colon = topLevelIndex(a.substring(question + 1), ':');
                if (colon >= 0) {
                    String yes = a.substring(question + 1, question + 1 + colon);
                    String no = a.substring(question + 2 + colon);
                    return resolve(unit, yes, at, local, depth + 1).and(resolve(unit, no, at, local, depth + 1));
                }
            }
            if (a.startsWith("switch")) {
                // A switch expression: every "-> value" and "yield value" it can answer.
                Matcher arm = Pattern.compile("(?:->|\\byield)\\s*([^;{}]+);").matcher(a);
                Resolution r = null;
                while (arm.find()) {
                    Resolution value = resolve(unit, arm.group(1), at, local, depth + 1);
                    r = r == null ? value : r.and(value);
                }
                return r == null ? Resolution.unknown() : r;
            }
            List<String> operands = topLevelOperands(a);
            if (operands.size() > 1) {
                return concatenation(unit, operands);
            }
            Matcher call = Pattern.compile("^((?:[A-Za-z_]\\w*\\s*\\.\\s*)*)(\\w+)\\s*\\(").matcher(a);
            if (call.find() && MessageBundleParityTest.matchingParen(a, call.end()) == a.length() - 1) {
                return callResult(unit, lastSegment(call.group(1).isEmpty() ? null : call.group(1)), call.group(2),
                    MessageBundleParityTest.topLevelArguments(a.substring(call.end(), a.length() - 1)).size(),
                    local, depth);
            }
            if (a.startsWith("\"") && a.endsWith("\"") && a.length() >= 2 && a.indexOf('"', 1) == a.length() - 1) {
                String text = a.substring(1, a.length() - 1);
                return isKey(text) ? new Resolution(Set.of(text), null, false) : Resolution.none();
            }
            String constant = constantValue(unit, a);
            if (constant != null) {
                return isKey(constant) ? new Resolution(Set.of(constant), null, false) : Resolution.none();
            }
            if (a.matches("[a-z]\\w*")) {
                return variable(unit, a, at, local, depth);
            }
            if (Pattern.compile("\"[^\"]*\\s[^\"]*\"").matcher(a).find()) {
                // Prose built some other way ("…".formatted(x)): text, not a key.
                return Resolution.none();
            }
            return Resolution.unknown();
        }

        /**
         * A key returned by a call. A message lookup (its own key is a site of its own) is no
         * key here; a method of this class or of a named class answers every value it returns.
         */
        private Resolution callResult(Unit unit, String qualifier, String name, int arguments,
                                      Map<String, List<Wrapper>> local, int depth) {
            boolean lookup = switch (name) {
                case "getMessage", "resolve", "resolveOrRaw", "resolveOrNull", "ofKey" -> true;
                case "key" -> "NotificationLocalizer".equals(qualifier);
                default -> false;
            };
            if (lookup) {
                return Resolution.none();
            }
            Unit owner = unit;
            if (qualifier != null && !qualifier.equals("this")) {
                String path = resolveClass(unit, qualifier);
                owner = path == null ? null : units.get(path);
            } else if (local.containsKey(name)) {
                return Resolution.none();
            }
            if (owner == null) {
                return Resolution.unknown();
            }
            if (qualifier != null && !qualifier.equals("this") && wrappers.getOrDefault(owner.path(), Map.of())
                    .getOrDefault(name, List.of()).stream().anyMatch(w -> w.accepts(arguments))) {
                return Resolution.none();
            }
            Resolution r = null;
            Matcher decl = METHOD.matcher(owner.source());
            while (decl.find()) {
                if (!decl.group(1).equals(name) || !isDeclaration(owner.source(), decl.start())
                        || MessageBundleParityTest.topLevelArguments(decl.group(2)).size() != arguments) {
                    continue;
                }
                String body = owner.source().substring(decl.end(), matchingBrace(owner.source(), decl.end()));
                Matcher ret = Pattern.compile("\\breturn\\s+([^;]+);").matcher(body);
                while (ret.find()) {
                    Resolution value = resolve(owner, ret.group(1), decl.end() + ret.start(1), Map.of(), depth + 1);
                    r = r == null ? value : r.and(value);
                }
            }
            return r == null ? Resolution.unknown() : r;
        }

        private Resolution concatenation(Unit unit, List<String> operands) {
            String fragment = null;
            boolean anyLiteral = false;
            for (String operand : operands) {
                String op = stripParens(operand.trim());
                if (op.startsWith("\"") && op.endsWith("\"") && op.length() >= 2) {
                    anyLiteral = true;
                    String text = op.substring(1, op.length() - 1);
                    if (!KEY_FRAGMENT.matcher(text).matches() || NOT_A_KEY.matcher(text).matches()) {
                        // Prose ("Failed to open " + type), or a "field:" + message conflict body.
                        return Resolution.none();
                    }
                    if (fragment == null) {
                        fragment = text;
                    }
                } else {
                    String value = constantValue(unit, op);
                    if (value != null && !KEY_FRAGMENT.matcher(value).matches()) {
                        // A prose constant ("Invalid UUID value: " + raw): text, not a key.
                        return Resolution.none();
                    }
                    if (fragment == null && value != null && value.endsWith(".")) {
                        fragment = value;
                        anyLiteral = true;
                    }
                }
            }
            if (fragment != null) {
                return new Resolution(Set.of(), fragment, false);
            }
            return anyLiteral ? Resolution.none() : Resolution.unknown();
        }

        /** A constant of this file, a static import, or {@code Class.CONSTANT} of another class. */
        private String constantValue(Unit unit, String expression) {
            if (expression.matches("[A-Z][A-Z0-9_]*")) {
                String own = unit.constants().get(expression);
                if (own != null) {
                    return own;
                }
                String owner = unit.staticImports().get(expression);
                Unit ownerUnit = owner == null ? null : units.get(byQualifiedName.get(owner));
                return ownerUnit == null ? null : ownerUnit.constants().get(expression);
            }
            // "Class.CONSTANT" or "pkg.Class.CONSTANT", split at the last dot: a flat character class,
            // no repeated group, so a long name cannot overflow the stack (Rex on #1096).
            int dot = expression.lastIndexOf('.');
            if (dot <= 0 || !expression.matches("[A-Za-z0-9_.]++")) {
                return null;
            }
            String owner = expression.substring(0, dot);
            String name = expression.substring(dot + 1);
            String simple = owner.substring(owner.lastIndexOf('.') + 1);
            if (!name.matches("[A-Z][A-Z0-9_]*+") || simple.isEmpty() || !Character.isUpperCase(simple.charAt(0))) {
                return null;
            }
            String path = resolveClass(unit, owner);
            Unit ownerUnit = path == null ? null : units.get(path);
            return ownerUnit == null ? null : ownerUnit.constants().get(name);
        }

        /**
         * A local variable or parameter: every value the enclosing method gives it. A parameter
         * that is the key position of this file's wrapper is not a site (its callers are).
         */
        private Resolution variable(Unit unit, String name, int at, Map<String, List<Wrapper>> local, int depth) {
            int method = enclosingMethod(unit, at);
            if (method < 0) {
                return Resolution.unknown();
            }
            int[] span = unit.methodSpans().get(method);
            String body = unit.source().substring(span[0], Math.min(span[1], unit.source().length()));
            List<String> params = MessageBundleParityTest.topLevelArguments(unit.methodParams().get(method));
            boolean isParameter = params.stream().anyMatch(p -> p.matches("(?:final\\s+)?[\\w.<>\\[\\]]+(?:\\.\\.\\.)?\\s+"
                + Pattern.quote(name)));
            // The variable itself, not a field of the same name ("this.messageKey = messageKey").
            Matcher assign = Pattern.compile("(?<![.\\w])" + Pattern.quote(name) + "\\s*=(?!=)\\s*([^;]+);")
                .matcher(body);
            Resolution r = null;
            while (assign.find()) {
                if (assign.group(1).trim().equals(name)) {
                    continue;
                }
                Resolution value = resolve(unit, assign.group(1), at, local, depth + 1);
                r = r == null ? value : r.and(value);
            }
            if (r != null && r.fragment() != null) {
                // "String key = \"x.\" + y" is a computed site where it is built; not twice.
                r = new Resolution(r.keys(), null, r.unresolved());
            }
            if (isParameter) {
                // Its callers are the sites when this method is a wrapper; otherwise the value is unknown here.
                if (isWrapperKeyParameter(unit, method, name, local)) {
                    return r == null ? Resolution.none() : r;
                }
                return r == null ? Resolution.unknown() : r.and(Resolution.unknown());
            }
            return r == null ? Resolution.unknown() : r;
        }

        private boolean isWrapperKeyParameter(Unit unit, int method, String name, Map<String, List<Wrapper>> local) {
            int[] span = unit.methodSpans().get(method);
            Matcher decl = METHOD.matcher(unit.source());
            if (!decl.find(span[2])) {
                return false;
            }
            List<String> params = MessageBundleParityTest.topLevelArguments(decl.group(2));
            for (Wrapper w : local.getOrDefault(decl.group(1), List.of())) {
                if (w.arity() == params.size() && w.index() < params.size()
                        && params.get(w.index()).matches(".*\\b" + Pattern.quote(name))) {
                    return true;
                }
            }
            return false;
        }

        private static int enclosingMethod(Unit unit, int at) {
            int found = -1;
            List<int[]> spans = unit.methodSpans();
            for (int i = 0; i < spans.size(); i++) {
                if (spans.get(i)[0] <= at && at < spans.get(i)[1]) {
                    found = i; // innermost wins: later spans start later
                }
            }
            return found;
        }
    }

    private static boolean isKey(String text) {
        return KEY.matcher(text).matches() && !NOT_A_KEY.matcher(text).matches();
    }

    private static Unit unit(String path, String source) {
        String fileName = path.substring(path.lastIndexOf('/') + 1);
        String simpleName = fileName.endsWith(".java") ? fileName.substring(0, fileName.length() - 5) : fileName;
        Matcher pkg = PACKAGE.matcher(source);
        Map<String, String> constants = new HashMap<>();
        Matcher c = STRING_CONSTANT.matcher(source);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
        }
        // An interface's or annotation's constant is static final without saying so.
        Matcher ic = INTERFACE_CONSTANT.matcher(source);
        while (ic.find()) {
            constants.putIfAbsent(ic.group(1), ic.group(2));
        }
        // A constant built from constants and literals: KEY_PREFIX + "PARTNER_TIMED_OUT".
        Map<String, String> built = new LinkedHashMap<>();
        Matcher ce = CONSTANT_EXPRESSION.matcher(source);
        while (ce.find()) {
            if (!constants.containsKey(ce.group(1))) {
                built.put(ce.group(1), ce.group(2));
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Map.Entry<String, String> e : built.entrySet()) {
                if (constants.containsKey(e.getKey())) {
                    continue;
                }
                StringBuilder value = new StringBuilder();
                boolean known = true;
                for (String operand : topLevelOperands(e.getValue())) {
                    String op = operand.trim();
                    if (op.startsWith("\"") && op.endsWith("\"") && op.length() >= 2 && op.indexOf('"', 1) == op.length() - 1) {
                        value.append(op, 1, op.length() - 1);
                    } else if (constants.containsKey(op)) {
                        value.append(constants.get(op));
                    } else {
                        known = false;
                        break;
                    }
                }
                if (known) {
                    constants.put(e.getKey(), value.toString());
                    grew = true;
                }
            }
        }
        Map<String, String> imports = new HashMap<>();
        Map<String, String> staticImports = new HashMap<>();
        Matcher si = STATIC_IMPORT.matcher(source);
        while (si.find()) {
            staticImports.put(si.group(2), si.group(1));
        }
        Matcher im = IMPORT.matcher(source);
        while (im.find()) {
            if (!source.startsWith("static", im.start() + "import".length() + 1)) {
                imports.put(im.group(2), im.group(1) + "." + im.group(2));
            }
        }
        Set<String> hapi = new HashSet<>();
        Matcher h = HAPI_EXCEPTION_IMPORT.matcher(source);
        while (h.find()) {
            hapi.add(h.group(1));
        }
        List<int[]> spans = new ArrayList<>();
        List<String> params = new ArrayList<>();
        Matcher m = METHOD.matcher(source);
        while (m.find()) {
            if (NOT_A_METHOD.contains(m.group(1)) || !isDeclaration(source, m.start())) {
                continue;
            }
            spans.add(new int[] {m.end(), matchingBrace(source, m.end()), m.start()});
            params.add(m.group(2));
        }
        return new Unit(path, pkg.find() ? pkg.group(1) : "", simpleName, source, constants, imports, staticImports,
            hapi, spans, params);
    }

    /** Method name to its wrappers (one per arity whose String parameter is resolved as a key), to a fixed point. */
    static Map<String, List<Wrapper>> wrappersIn(String source) {
        return wrappersIn(source, Map.of());
    }

    /**
     * As {@link #wrappersIn(String)}, also counting a qualified call of another class's wrapper
     * ({@code guard.requireNotDischargedWithoutVisit(…, messageKey)}): {@code cross} maps each
     * qualifier the file uses for another class to that class's wrappers and their key positions.
     */
    static Map<String, List<Wrapper>> wrappersIn(String source, Map<String, Map<String, Set<Integer>>> cross) {
        Map<String, List<Wrapper>> found = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            Matcher m = METHOD.matcher(source);
            while (m.find()) {
                String name = m.group(1);
                if (NOT_A_METHOD.contains(name) || !isDeclaration(source, m.start())) {
                    continue;
                }
                List<String> params = MessageBundleParityTest.topLevelArguments(m.group(2));
                boolean varargs = !params.isEmpty() && params.get(params.size() - 1).contains("...");
                String body = source.substring(m.end(), matchingBrace(source, m.end()));
                for (int index : keyParameters(params, body, found, cross)) {
                    // One wrapper per declaration and key position, found once.
                    if (seen.add(name + "/" + params.size() + "@" + m.start() + "#" + index)) {
                        found.computeIfAbsent(name, k -> new ArrayList<>()).add(new Wrapper(params.size(), varargs, index));
                        changed = true;
                    }
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

    /** Every String parameter of a method that flows unchanged into a sink. */
    private static List<Integer> keyParameters(List<String> params, String body, Map<String, List<Wrapper>> known,
                                               Map<String, Map<String, Set<Integer>>> cross) {
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < params.size(); i++) {
            String[] tokens = params.get(i).replace("final ", "").trim().split("\\s+");
            if (tokens.length < 2 || !tokens[tokens.length - 2].equals("String")) {
                continue;
            }
            String p = Pattern.quote(tokens[tokens.length - 1]);
            List<String> sinks = new ArrayList<>(List.of(
                "\\bgetMessage\\s*\\(\\s*" + p + "\\s*,",
                "\\bresolveOrRaw\\s*\\(\\s*" + p + "\\s*[,)]",
                // MessageUtil.resolve, not Path.resolve: qualified by MessageUtil, or MessageUtil's own call.
                "(?:\\bMessageUtil\\s*\\.\\s*|(?<![.\\w]))resolve\\s*\\(\\s*(?:[\\w.()]+\\s*,\\s*)?" + p + "\\s*[,)]",
                "\\bresolveOrNull\\s*\\(\\s*[\\w.()]+\\s*,\\s*" + p + "\\s*[,)]",
                "\\bNotificationLocalizer\\s*\\.\\s*key\\s*\\(\\s*" + p + "\\s*[,)]",
                "\\bcreateNotification\\s*\\(\\s*" + p + "\\s*[,)]",
                "\\bBusinessRuleException\\s*\\.\\s*ofKey\\s*\\(\\s*" + p + "\\s*[,)]",
                // A helper that throws a keyed refusal: requireEnd(dto, "staff.endDate.beforeStart").
                "\\bnew\\s+(?:[\\w.]*\\.)?(?:BusinessException|ResourceNotFoundException|ClientSafeAccessDeniedException)"
                    + "\\s*\\(\\s*" + p + "\\s*[,)]",
                "\\bnew\\s+(?:[\\w.]*\\.)?FieldBusinessException\\s*\\(" + ARG + ",\\s*" + p + "\\s*[,)]",
                "\\.\\s*require(?:Value)?\\s*\\(" + ARG + "," + ARG + ",\\s*" + p + "\\s*[,)]"));
            for (Map.Entry<String, List<Wrapper>> w : known.entrySet()) {
                for (Wrapper wrapper : w.getValue()) {
                    // The parameter in the wrapper's own key position.
                    sinks.add("(?<![.\\w])" + Pattern.quote(w.getKey()) + "\\s*\\((?:" + ARG + ",){"
                        + wrapper.index() + "}\\s*" + p + "\\s*[,)]");
                }
            }
            for (Map.Entry<String, Map<String, Set<Integer>>> q : cross.entrySet()) {
                for (Map.Entry<String, Set<Integer>> w : q.getValue().entrySet()) {
                    for (int index : w.getValue()) {
                        sinks.add("(?<![.\\w])" + Pattern.quote(q.getKey()) + "\\s*\\.\\s*" + Pattern.quote(w.getKey())
                            + "\\s*\\((?:" + ARG + ",){" + index + "}\\s*" + p + "\\s*[,)]");
                    }
                }
            }
            for (String sink : sinks) {
                if (Pattern.compile(sink).matcher(body).find()) {
                    out.add(i);
                    break;
                }
            }
        }
        return out;
    }

    /** One call argument: anything but a comma or a parenthesis, or a parenthesised group one level deep. */
    private static final String ARG = "(?:[^,()]|\\((?:[^()]|\\([^()]*\\))*\\))*";

    /** Marker for {@code MessageUtil.resolve}, whose key is first or second. */
    private static final int RESOLVE = -1;

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

    private static String stripParens(String s) {
        String t = s.trim();
        while (t.startsWith("(") && MessageBundleParityTest.matchingParen(t, 1) == t.length() - 1) {
            t = t.substring(1, t.length() - 1).trim();
        }
        return t;
    }

    private static int topLevelIndex(String s, char wanted) {
        int depth = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '"' || ch == '\'') {
                i = endOfLiteral(s, i);
            } else if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == wanted && depth == 0) {
                return i;
            }
        }
        return -1;
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

    /** Index of the '}' closing the '{' before {@code from}, skipping literals. */
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

    /** The literal (or same-file constant) arguments of every call of {@code method} in {@code source}. */
    static List<List<String>> literalCallArguments(String source, String method) {
        String stripped = stripComments(source);
        Map<String, String> constants = new HashMap<>();
        Matcher c = STRING_CONSTANT.matcher(stripped);
        while (c.find()) {
            constants.put(c.group(1), c.group(2));
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
                } else {
                    args.add(constants.get(a));
                }
            }
            calls.add(args);
        }
        return calls;
    }
}
