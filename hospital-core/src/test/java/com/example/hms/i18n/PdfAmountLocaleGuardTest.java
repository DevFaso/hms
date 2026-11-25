package com.example.hms.i18n;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static org.assertj.core.api.Assertions.assertThat;

class PdfAmountLocaleGuardTest {
    private static final Path MAIN = Path.of("src/main/java/com/example/hms");

    @Test void everyInvoiceAmountCarriesTheDocumentOrPatientLocale() throws Exception {
        var failures = new ArrayList<String>();
        int checked = 0;
        try (var files = Files.walk(MAIN)) {
            for (var file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = SourceScan.stripComments(Files.readString(file));
                boolean own = file.endsWith("PdfInvoiceService.java");
                var matcher = amountCalls(source, own).matcher(source);
                while (matcher.find()) {
                    if (own && source.substring(Math.max(0, matcher.start()-30), matcher.start()).stripTrailing().endsWith("String")) continue;
                    int open = source.indexOf('(', matcher.start());
                    int close = SourceScan.closingParen(source, open);
                    checked++;
                    if (arguments(source, open, close).size() != 2) failures.add(file + ":" + SourceScan.lineOf(source, open));
                }
            }
        }
        assertThat(checked).isGreaterThanOrEqualTo(50);
        assertThat(failures).as("A PDF/e-mail amount must carry its explicit locale, never the compatibility French default").isEmpty();
    }

    @Test void theFormatterUsesItsRequestedLocaleAndTheScannerHandlesNestedCalls() throws Exception {
        String source = SourceScan.stripComments(Files.readString(MAIN.resolve("service/PdfInvoiceService.java")));
        var formatter = Pattern.compile("getIntegerInstance\\s*\\(([^)]+)\\)").matcher(source);
        assertThat(formatter.find()).isTrue();
        var signature = Pattern.compile("money\\s*\\(\\s*BigDecimal\\s+\\w+\\s*,\\s*Locale\\s+(\\w+)").matcher(source);
        assertThat(signature.find()).as("explicit locale parameter").isTrue();
        String parameter = signature.group(1);
        String argument = formatter.group(1).strip();
        String dependency = argument;
        if (!argument.equals(parameter) && argument.matches("[a-zA-Z]+")) {
            var declaration = Pattern.compile("(?:Locale|var)\\s+" + Pattern.quote(argument) + "\\s*=([^;]+);").matcher(source);
            assertThat(declaration.find()).as("formatter locale origin").isTrue();
            dependency = declaration.group(1);
        }
        assertThat(Pattern.compile("\\b" + Pattern.quote(parameter) + "\\b").matcher(dependency).find()).as("the passed locale reaches NumberFormat").isTrue();
        String nested = "money(totalOf(item, price), labels.locale())";
        assertThat(arguments(nested, nested.indexOf('('), SourceScan.closingParen(nested, nested.indexOf('(')))).hasSize(2);
        String missing = "money(totalOf(item, price))";
        assertThat(arguments(missing, missing.indexOf('('), SourceScan.closingParen(missing, missing.indexOf('(')))).hasSize(1);
    }

    @Test void staticImportsCannotHideAnAmountCaller() {
        String imported = "import static com.example.hms.service.PdfInvoiceService.money; return money(total);";
        assertThat(amountCalls(imported, false).matcher(imported).find()).isTrue();
        String wildcard = "import static com.example.hms.service.PdfInvoiceService.*; return money(total);";
        assertThat(amountCalls(wildcard, false).matcher(wildcard).find()).isTrue();
        String unrelated = "import static com.example.hms.service.PdfInvoiceService.money; return other.money(total);";
        assertThat(amountCalls(unrelated, false).matcher(unrelated).find()).isFalse();
    }

    private static Pattern amountCalls(String source, boolean own) {
        boolean imported = Pattern.compile("import\\s+static\\s+com\\.example\\.hms\\.service\\.PdfInvoiceService\\.(?:money|\\*)\\s*;").matcher(source).find();
        return Pattern.compile(own || imported ? "(?:\\bPdfInvoiceService\\.|(?<![\\w.]))money\\s*\\(" : "\\bPdfInvoiceService\\.money\\s*\\(");
    }

    private static List<String> arguments(String source, int open, int close) {
        var result = new ArrayList<String>();
        int depth = 0, start = open+1;
        for (int i=start; i<close; i++) {
            int end = SourceScan.endOfLiteralOrComment(source, i);
            if (end >= i) { i=end; continue; }
            char c = source.charAt(i);
            if (c=='(') depth++;
            else if (c==')') depth--;
            else if (c==',' && depth==0) { result.add(source.substring(start,i).strip()); start=i+1; }
        }
        result.add(source.substring(start,close).strip());
        return result;
    }
}
