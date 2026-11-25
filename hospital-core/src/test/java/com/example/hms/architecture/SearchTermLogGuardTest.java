package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D3750: a search box's typed term (a name, a phone number, an MRN…) left the URL for the
 * request body; it must not land in the application log instead. The assignments listing logged
 * {@code search='{}'} at INFO.
 *
 * <p>Every log call of the application is read: no argument after the message may be a
 * search-term variable ({@code search}, {@code term}, {@code query}, {@code keyword}, …) or its
 * getter. Logging whether a term was given ({@code hasText(search) ? "given" : "none"}) is fine.
 */
@DisplayName("D3750: no log statement prints a search term")
class SearchTermLogGuardTest {

    private static final Path MAIN = Path.of("src/main/java");

    private static final Pattern LOG_CALL =
        Pattern.compile("\\b(?:log|LOG|logger|LOGGER)\\s*\\.\\s*(?:trace|debug|info|warn|error)\\s*\\(");

    private static final Set<String> TERM_NAMES = Set.of(
        "search", "term", "query", "keyword", "keywords", "searchTerm", "q", "searchText", "searchQuery",
        "normalizedSearch", "trimmedSearch");

    /** A bare term variable, the term trimmed, or a term getter ({@code criteria.getSearch()}, {@code body.term()}). */
    private static final Pattern TERM_ARGUMENT = Pattern.compile(
        "^(?:(?<name>[A-Za-z_]\\w*)(?:\\.(?:trim|strip)\\(\\))?"
            + "|[\\w.()]*\\.(?:getSearch|getQuery|getKeyword|getKeywords|getTerm|getSearchTerm|term|search)\\(\\))$");

    /** The log calls of {@code source} that print a search term, as "line: call". */
    static List<String> offenders(String source) {
        String code = SourceScan.stripComments(source);
        List<String> found = new ArrayList<>();
        Matcher call = LOG_CALL.matcher(code);
        while (call.find()) {
            int open = call.end() - 1;
            int close = SourceScan.closingParen(code, open);
            List<String> arguments = arguments(code, open + 1, close);
            for (String argument : arguments.subList(Math.min(1, arguments.size()), arguments.size())) {
                Matcher term = TERM_ARGUMENT.matcher(argument);
                if (term.matches() && (term.group("name") == null || TERM_NAMES.contains(term.group("name")))) {
                    found.add(SourceScan.lineOf(code, call.start()) + ": " + argument);
                }
            }
        }
        return found;
    }

    /** The top-level arguments between {@code from} and {@code to}, trimmed. */
    private static List<String> arguments(String code, int from, int to) {
        List<String> out = new ArrayList<>();
        int depth = 0;
        int start = from;
        for (int i = from; i < to; i++) {
            int skipped = SourceScan.endOfLiteralOrComment(code, i);
            if (skipped >= 0) {
                i = skipped;
                continue;
            }
            char ch = code.charAt(i);
            if (ch == '(' || ch == '[' || ch == '{') {
                depth++;
            } else if (ch == ')' || ch == ']' || ch == '}') {
                depth--;
            } else if (ch == ',' && depth == 0) {
                out.add(code.substring(start, i).trim());
                start = i + 1;
            }
        }
        out.add(code.substring(start, to).trim());
        return out;
    }

    @Test
    @DisplayName("no application log call prints a search box's term")
    void noLogCallPrintsASearchTerm() throws IOException {
        List<String> offending = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (String hit : offenders(source)) {
                    offending.add(MAIN.relativize(file).toString().replace('\\', '/') + ":" + hit);
                }
            }
        }
        assertThat(offending)
            .as("log whether a term was given (hasText(search) ? \"given\" : \"none\"), never the term (QA D3750)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("the scan sees the shapes it forbids, and lets a presence check through")
    void theScanSeesTheShapes() {
        assertThat(offenders("""
            log.info("Fetching (search='{}')", hospitalId, search, page);
            log.debug("term {}", body.term());
            LOGGER.warn("q {}", criteria.getSearch());
            log.info("kw {}",
                keyword.trim());
            """)).hasSize(4);
        assertThat(offenders("""
            log.info("Fetching (term={})", StringUtils.hasText(search) ? "given" : "none");
            log.info("search {} rows", rows);
            log.error("query failed: {}", ex.getMessage());
            // log.info("x {}", search);
            """)).isEmpty();
    }
}
