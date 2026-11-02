package com.example.hms.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.hms.testsupport.SourceScan;
import com.example.hms.utility.PatientAddressText;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * QA D1074 (class guard): a patient's "Adresse" is the street address, written
 * through {@link PatientAddressText}. The demographic correction rebuilt it
 * from every part joined with pipes ("Secteur 4, rue 12 | Koudougou,
 * Centre-Ouest | 01 BP 3305 | Burkina Faso"), so the city, the region, the
 * postal code and the country, each shown under its own label, appeared a
 * second time inside the address. Scanned: every {@code setAddress(…)} whose
 * argument reads a city, region, postal code or country, and every pipe
 * separator in a method that reads one of them.
 */
class PatientAddressCompositeGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");

    private static final Pattern SET_ADDRESS = Pattern.compile("\\.setAddress\\(");

    private static final Pattern PART_READ = Pattern.compile("\\.get(?:City|State|ZipCode|PostalCode|Country)\\(\\)");

    @Test
    void noAddressIsComposedFromItsOtherFields() throws IOException {
        List<String> sites = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String src = SourceScan.stripComments(Files.readString(f, StandardCharsets.UTF_8));
                String rel = MAIN.relativize(f).toString().replace('\\', '/');
                sites.addAll(compositeSites(rel, src));
            }
        }
        assertThat(sites)
            .as("an address composed from the city, region, postal code or country (QA D1074): store the street "
                + "lines with PatientAddressText.streetLine; " + SourceScan.MANDATE)
            .isEmpty();
    }

    static List<String> compositeSites(String rel, String src) {
        List<String> out = new ArrayList<>();
        Matcher set = SET_ADDRESS.matcher(src);
        while (set.find()) {
            int close = SourceScan.closingParen(src, set.end() - 1);
            String argument = close > 0 ? src.substring(set.end(), close) : "";
            if (PART_READ.matcher(argument).find()) {
                out.add(rel + ":" + SourceScan.lineOf(src, set.start()) + " setAddress(" + argument.strip() + ")");
            }
        }
        for (int at = src.indexOf("\" | \""); at >= 0; at = src.indexOf("\" | \"", at + 1)) {
            int from = Math.max(0, src.lastIndexOf("\n    ", at));
            int methodStart = src.lastIndexOf("\n    p", at);
            String scope = src.substring(methodStart >= 0 ? methodStart : from, Math.min(src.length(), at + 400));
            if (PART_READ.matcher(scope).find()) {
                out.add(rel + ":" + SourceScan.lineOf(src, at) + " pipe-joined address parts");
            }
        }
        return out;
    }

    @Test
    void theScanSeesTheComposite() {
        String src = """
            class X {
                private void patch(Patient p) {
                    p.setAddress(build(p.getAddressLine1(), p.getCity(), p.getCountry()));
                }
            }
            """;
        assertThat(compositeSites("X.java", src)).hasSize(1);
        assertThat(compositeSites("X.java", """
            class X {
                private void patch(Patient p) {
                    p.setAddress(PatientAddressText.streetLine(p.getAddressLine1(), p.getAddressLine2()));
                }
            }
            """)).isEmpty();
    }

    @Test
    void theStreetLineIsWhatAPersonWrites() {
        assertThat(PatientAddressText.streetLine(" Secteur 4, rue 12 ", null)).isEqualTo("Secteur 4, rue 12");
        assertThat(PatientAddressText.streetLine("Secteur 4", "Porte 9")).isEqualTo("Secteur 4, Porte 9");
        assertThat(PatientAddressText.streetLine(" ", null)).isNull();
        // A legacy composite shows its street lines; anything else is shown as stored.
        assertThat(PatientAddressText.display(
            "Secteur 4, rue 12 | Koudougou, Centre-Ouest | 01 BP 3305 | Burkina Faso", null, null))
            .isEqualTo("Secteur 4, rue 12");
        assertThat(PatientAddressText.display("x | Koudougou", "Secteur 4, rue 12", null))
            .isEqualTo("Secteur 4, rue 12");
        assertThat(PatientAddressText.display("Secteur 4, rue 12", null, null)).isEqualTo("Secteur 4, rue 12");
        assertThat(PatientAddressText.display(null, "a", null)).isNull();
    }
}
