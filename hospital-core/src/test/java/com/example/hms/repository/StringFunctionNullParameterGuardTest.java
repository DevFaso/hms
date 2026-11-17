package com.example.hms.repository;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1253 (round 26, High regression): a query parameter handed straight to a
 * string function ({@code LENGTH(:p)}, {@code SUBSTRING(:p, 2)},
 * {@code LOWER(:p)}, {@code CONCAT('%', :p, '%')}, {@code TRIM(:p)},
 * {@code COALESCE(:p, 'x')}, {@code LOCATE(:p, col)}, {@code REPLACE(:p, …)},
 * {@code '%' || :p}) must be cast: {@code LENGTH(CAST(:p AS string))}. When
 * the value is null, Hibernate 7 has no type to send and pgjdbc binds it as
 * {@code bytea}; PostgreSQL then refuses the whole statement ("function
 * character_length(bytea) does not exist", "operator does not exist:
 * character varying ~~ bytea") - even when an {@code :p IS NULL OR} guard
 * means the function would never be evaluated. H2 accepts every form, so the
 * unit tests stayed green while every patient search by phone, MRN, birth
 * date or e-mail answered 500 on dev. A parameter compared straight to a
 * column ({@code col LIKE :p}, {@code col = :p}) is typed from the column and
 * is fine. {@code StringFunctionNullParameterPostgresIT} proves each form on
 * the real engine; this keeps the next query from reintroducing the bare one.
 *
 * <p>It reads every {@code @Query} (value and countQuery, JPQL and native) as
 * Spring Data sees it - the concatenated constants assembled - and, in every
 * other main source file, any query text written in a string literal.
 */
class StringFunctionNullParameterGuardTest {

    /** Functions PostgreSQL resolves by argument type: a bytea argument fails. */
    private static final Set<String> STRING_FUNCTIONS = Set.of(
        "LOWER", "UPPER", "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH", "OCTET_LENGTH", "SUBSTRING", "SUBSTR",
        "CONCAT", "TRIM", "LTRIM", "RTRIM", "BTRIM", "COALESCE", "LOCATE", "POSITION", "REPLACE", "TRANSLATE",
        "LEFT", "RIGHT", "LPAD", "RPAD", "REVERSE", "UNACCENT", "INITCAP", "SPLIT_PART", "STRPOS");

    private static final Pattern FUNCTION_OPEN = Pattern.compile("\\b([A-Za-z_]+)\\s*\\(");
    private static final Pattern BARE_PARAMETER = Pattern.compile(":[A-Za-z_]\\w*|\\?\\d*");
    private static final Pattern CONCAT_OPERATOR =
        Pattern.compile("\\|\\|\\s*(?::[A-Za-z_]\\w*|\\?\\d*)(?![\\w(])|(?::[A-Za-z_]\\w*|\\?\\d*)\\s*\\|\\|");
    private static final Pattern TRIM_FROM = Pattern.compile("\\bFROM\\s+(\\S+)$", Pattern.CASE_INSENSITIVE);
    private static final Path MAIN = Path.of("src", "main", "java");
    private static final Path REPOSITORIES = MAIN.resolve(Path.of("com", "example", "hms", "repository"));

    @Test
    void noRepositoryQueryHandsABareParameterToAStringFunction() {
        List<String> offenders = new ArrayList<>();
        int queries = 0;
        for (Class<?> repository : repositoryInterfaces()) {
            for (Method method : repository.getDeclaredMethods()) {
                Query query = method.getAnnotation(Query.class);
                if (query == null) {
                    continue;
                }
                queries++;
                String site = repository.getSimpleName() + "#" + method.getName();
                barePositions(query.value()).forEach(hit -> offenders.add(site + ": " + hit));
                barePositions(query.countQuery()).forEach(hit -> offenders.add(site + " (count): " + hit));
            }
        }
        assertThat(queries).as("the scan must reach the repositories' @Query methods").isGreaterThan(300);
        assertThat(offenders)
            .as("a parameter inside a string function must be cast, e.g. LENGTH(CAST(:p AS string)) "
                + "(native: CAST(:p AS text)); a null reaches PostgreSQL as bytea (QA D1253)")
            .isEmpty();
    }

    @Test
    void noOtherQueryTextHandsABareParameterToAStringFunction() throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java") && !p.startsWith(REPOSITORIES)).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                if (!source.contains("createQuery") && !source.contains("createNativeQuery") && !source.contains("@Query")) {
                    continue;
                }
                barePositions(stringLiterals(source)).forEach(hit -> offenders.add(MAIN.relativize(file) + ": " + hit));
            }
        }
        assertThat(offenders).as("query text outside the repositories (QA D1253)").isEmpty();
    }

    @Test
    void theScanSeesEveryShapeItClaimsTo() {
        assertThat(barePositions("WHERE LENGTH(:p) > 2")).hasSize(1);
        assertThat(barePositions("WHERE x LIKE SUBSTRING(:p, 2, LENGTH(:p) - 2)")).hasSize(2);
        assertThat(barePositions("WHERE x LIKE LOWER(CONCAT('%', :p, '%'))")).hasSize(1);
        assertThat(barePositions("WHERE x = TRIM(BOTH FROM :p)")).hasSize(1);
        assertThat(barePositions("WHERE x = COALESCE(:p, 'x')")).hasSize(1);
        assertThat(barePositions("WHERE x LIKE '%' || :p || '%'")).hasSize(1);
        assertThat(barePositions("WHERE lower(x) = lower(?1)")).hasSize(1);
        assertThat(barePositions("WHERE LENGTH(CAST(:p AS string)) > 2 AND x LIKE :p AND x = :p")).isEmpty();
        assertThat(barePositions("WHERE x LIKE CONCAT('%', CAST(:p AS string), '%') AND LOWER(x) = 'a'")).isEmpty();
        assertThat(barePositions("WHERE x LIKE CONCAT('a', ':b')")).isEmpty();
    }

    /** Each bare parameter given to a string function, or to {@code ||}, in {@code text}. */
    static List<String> barePositions(String text) {
        List<String> hits = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return hits;
        }
        String unquoted = blankQuotedLiterals(text);
        Matcher open = FUNCTION_OPEN.matcher(unquoted);
        while (open.find()) {
            String function = open.group(1).toUpperCase(Locale.ROOT);
            if (!STRING_FUNCTIONS.contains(function)) {
                continue;
            }
            int close = matchingClose(unquoted, open.end() - 1);
            if (close < 0) {
                continue;
            }
            for (String argument : topLevelArguments(unquoted.substring(open.end(), close))) {
                String arg = argument.trim();
                Matcher from = TRIM_FROM.matcher(arg);
                if (from.find()) {
                    arg = from.group(1);
                }
                if (BARE_PARAMETER.matcher(arg).matches()) {
                    hits.add(function + "(" + arg + ")");
                }
            }
        }
        Matcher operator = CONCAT_OPERATOR.matcher(unquoted);
        while (operator.find()) {
            hits.add("|| " + operator.group().replace("||", "").trim());
        }
        return hits;
    }

    /** The text of every string literal and text block of a Java source, joined as one query text. */
    static String stringLiterals(String source) {
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '\'') {
                i = skipCharLiteral(source, i);
            } else if (source.startsWith("//", i)) {
                i = endOf(source, "\n", i + 2);
            } else if (source.startsWith("/*", i)) {
                i = endOf(source, "*/", i + 2);
            } else if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                int stop = end < 0 ? source.length() : end;
                out.append(source, i + 3, stop).append(' ');
                i = stop + 3;
            } else if (c == '"') {
                int j = i + 1;
                while (j < source.length() && source.charAt(j) != '"') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                out.append(source, i + 1, Math.min(j, source.length())).append(' ');
                i = j + 1;
            } else {
                i++;
            }
        }
        return out.toString();
    }

    private static int skipCharLiteral(String source, int quote) {
        boolean escaped = quote + 1 < source.length() && source.charAt(quote + 1) == '\\';
        int close = source.indexOf('\'', quote + (escaped ? 3 : 2));
        return close < 0 ? source.length() : close + 1;
    }

    private static int endOf(String source, String marker, int from) {
        int end = source.indexOf(marker, from);
        return end < 0 ? source.length() : end + marker.length();
    }

    /** Replaces the inside of every '…' literal with spaces, so a ':' or ',' in it is not read as SQL. */
    private static String blankQuotedLiterals(String text) {
        StringBuilder out = new StringBuilder(text.length());
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
                out.append(c);
            } else {
                out.append(quoted ? ' ' : c);
            }
        }
        return out.toString();
    }

    private static int matchingClose(String text, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && --depth == 0) {
                return i;
            }
        }
        return -1;
    }

    private static List<String> topLevelArguments(String inside) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int start = 0;
        for (int i = 0; i < inside.length(); i++) {
            char c = inside.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (c == ',' && depth == 0) {
                arguments.add(inside.substring(start, i));
                start = i + 1;
            }
        }
        arguments.add(inside.substring(start));
        return arguments;
    }

    /** Every Spring Data repository interface of the application. */
    static List<Class<?>> repositoryInterfaces() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false) {
            @Override
            protected boolean isCandidateComponent(AnnotatedBeanDefinition definition) {
                return definition.getMetadata().isInterface();
            }
        };
        scanner.addIncludeFilter(new AssignableTypeFilter(Repository.class));
        List<Class<?>> found = new ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents("com.example.hms")) {
            try {
                found.add(Class.forName(definition.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }
        return found;
    }
}
