package com.example.hms.service.pharmacy;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D966 (class guard): "Imprimer pour le patient" only recorded a routing
 * decision and said "printed", and "Imprimer l'étiquette du lot" opened the
 * bare API URL in a tab (no /api prefix, no token), which landed on the login
 * page. A print button on a pharmacy page must end in a document.
 *
 * <ul>
 *   <li>Every handler a pharmacy template binds to a click whose name says
 *       print ("print", "imprim") reaches a document: its body, or the body of
 *       a method of the same component it calls, opens a fetched PDF
 *       ({@code openForPrint(}) or prints a rendered view
 *       ({@code window.print(}).</li>
 *   <li>No pharmacy page opens an API path with {@code window.open('/...')}:
 *       the request would skip the interceptors that add /api and the token.</li>
 * </ul>
 */
class PharmacyPrintActionsGuardTest {

    private static final Path PHARMACY = Path.of("..", "hospital-portal", "src", "app", "pharmacy");
    private static final Pattern CLICK = Pattern.compile("\\(click\\)=\"(\\w+)\\(");
    private static final Pattern PRINTS = Pattern.compile("(?i)print|imprim");
    private static final List<String> DOCUMENT_MARKERS = List.of("openForPrint(", "window.print(");
    private static final Pattern BARE_API_OPEN = Pattern.compile("window\\.open\\(\\s*[`'\"]/");
    private static final Pattern CALLS = Pattern.compile("this\\.(\\w+)\\(");

    @Test
    void everyPrintButtonOnAPharmacyPageEndsInADocument() throws IOException {
        List<String> silent = new ArrayList<>();
        int printButtons = 0;
        for (Path html : pages()) {
            Path ts = Path.of(html.toString().replace(".html", ".ts"));
            if (!Files.exists(ts)) {
                continue;
            }
            String template = Files.readString(html, StandardCharsets.UTF_8);
            String source = Files.readString(ts, StandardCharsets.UTF_8);
            Set<String> handlers = new TreeSet<>();
            Matcher click = CLICK.matcher(template);
            while (click.find()) {
                if (PRINTS.matcher(click.group(1)).find()) {
                    handlers.add(click.group(1));
                }
            }
            for (String handler : handlers) {
                printButtons++;
                if (!reachesADocument(source, handler)) {
                    silent.add(html.getFileName() + " -> " + handler + "()");
                }
            }
        }
        assertThat(printButtons).as("print buttons found on the pharmacy pages").isGreaterThanOrEqualTo(3);
        assertThat(silent).as("pharmacy print buttons that print nothing (QA D966)").isEmpty();
    }

    @Test
    void noPharmacyPageOpensABareApiPathInANewTab() throws IOException {
        List<String> bare = new ArrayList<>();
        try (Stream<Path> files = Files.list(PHARMACY)) {
            for (Path ts : files.filter(p -> p.toString().endsWith(".ts") && !p.toString().endsWith(".spec.ts"))
                    .toList()) {
                if (BARE_API_OPEN.matcher(Files.readString(ts, StandardCharsets.UTF_8)).find()) {
                    bare.add(ts.getFileName().toString());
                }
            }
        }
        assertThat(bare).as("window.open of an API path skips /api and the token (QA D966)").isEmpty();
    }

    @Test
    void theScanSeesASilentPrint() {
        String source = "  printForPatient(): void {\n    this.svc.printForPatient(id).subscribe();\n  }\n";
        assertThat(reachesADocument(source, "printForPatient")).isFalse();
        String fixed = """
                  printForPatient(): void {
                    this.openCopy(id);
                  }
                  private openCopy(id: string): void {
                    this.printService.openForPrint(blob);
                  }
                """;
        assertThat(reachesADocument(fixed, "printForPatient")).isTrue();
    }

    private static List<Path> pages() throws IOException {
        try (Stream<Path> files = Files.list(PHARMACY)) {
            return files.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }

    /** The method's body, or that of a method of the same class it calls, holds a document marker. */
    static boolean reachesADocument(String source, String method) {
        String body = bodyOf(source, method);
        if (body == null) {
            return false;
        }
        if (DOCUMENT_MARKERS.stream().anyMatch(body::contains)) {
            return true;
        }
        Matcher call = CALLS.matcher(body);
        while (call.find()) {
            String callee = bodyOf(source, call.group(1));
            if (callee != null && DOCUMENT_MARKERS.stream().anyMatch(callee::contains)) {
                return true;
            }
        }
        return false;
    }

    /** The braces-balanced body of a class method declared as {@code name(...)...{}. */
    static String bodyOf(String source, String name) {
        Matcher declaration = Pattern.compile("(?m)^\\s+(?:private |protected |public )?(?:readonly )?"
                + Pattern.quote(name) + "\\([^)]*\\)[^{;]*\\{").matcher(source);
        if (!declaration.find()) {
            return null;
        }
        int depth = 1;
        int i = declaration.end();
        while (i < source.length() && depth > 0) {
            char c = source.charAt(i++);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
            }
        }
        return source.substring(declaration.end(), i);
    }
}
