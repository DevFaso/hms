package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1310 (round 28; the D1253 / D1274 class on another screen): the
 * invoice list's search box found a patient by name, MRN and invoice number,
 * but not by the e-mail shown in the row, the phone or the birth date. The
 * round-27 guard ({@code PatientMatchKeysGuardTest}) holds every PATIENT
 * search to the router; it could not see a search over OTHER records that
 * compares the patient's name itself.
 *
 * <p>Rule: every repository query (with the text constants it concatenates)
 * and every JPA specification that matches a free-text term against a
 * patient's first name also matches the patient's e-mail, date of birth and
 * phone. {@code PatientRepository} is the router's own repository: its name,
 * birth-date, phone and e-mail filters are separate parameters the router
 * fills (PatientSearchPostgresIT).</p>
 */
@DisplayName("D1310: a free-text search that finds a patient by name finds them by e-mail, birth date and phone too")
class PatientFreeTextSearchGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern QUERY = Pattern.compile("@Query\\(");
    private static final Pattern PATIENT_NAME = Pattern.compile("(?:\\bp|patient)\\.firstName\\b");
    private static final Pattern CONSTANT_USE = Pattern.compile("\\b([A-Z][A-Z0-9_]++)\\b");

    @Test
    @DisplayName("every repository query matching a term against a patient's name matches e-mail, birth date and phone")
    void everyPatientNameSearchQueryReadsTheOtherIdentifiers() throws IOException {
        List<String> nameOnly = new ArrayList<>();
        int searches = 0;
        for (Path file : files(MAIN.resolve("repository"))) {
            if (file.getFileName().toString().equals("PatientRepository.java")) {
                continue;
            }
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher query = QUERY.matcher(source);
            while (query.find()) {
                int end = source.indexOf(';', SourceScan.closingParen(source, query.end() - 1));
                String region = withConstants(source, source.substring(query.start(), end));
                if (!PATIENT_NAME.matcher(region).find() || !region.toUpperCase(Locale.ROOT).contains(" LIKE ")) {
                    continue;
                }
                searches++;
                String lower = region.toLowerCase(Locale.ROOT);
                if (!lower.contains("email") || !lower.contains("dateofbirth") || !(lower.contains("phonenumberprimary") || lower.contains("primary_phone_digits"))) {
                    nameOnly.add(file.getFileName() + ":" + SourceScan.lineOf(source, query.start()));
                }
            }
        }
        assertThat(searches).as("repository searches over a patient's name").isPositive();
        assertThat(nameOnly)
            .as("a search box finds the patient by name only; match p.email, CAST(p.dateOfBirth AS string) = :dob"
                + " and the phone digits (PatientRepository.PRIMARY_PHONE_DIGITS) as the invoice list does (QA D1310)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    @Test
    @DisplayName("every specification matching a term against a patient's name reads a birth date and a phone")
    void everyPatientNameSearchSpecificationReadsTheOtherIdentifiers() throws IOException {
        List<String> nameOnly = new ArrayList<>();
        int specifications = 0;
        for (Path file : files(MAIN.resolve("specification"))) {
            String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!Pattern.compile("patient\\w*+\\(\\)\\.get\\(FIRST_NAME\\)|patient\\w*+\\.get\\(\"firstName\"\\)")
                    .matcher(source).find()) {
                continue;
            }
            specifications++;
            if (!source.contains("PatientMatchKeys.birthDate(") || !source.contains("PatientMatchKeys.looksLikePhone(")
                    || !source.contains("phoneNumberPrimary")) {
                nameOnly.add(file.getFileName().toString());
            }
        }
        assertThat(specifications).as("specifications searching a patient's name").isPositive();
        assertThat(nameOnly)
            .as("a search box finds the patient by name only; read the term through PatientMatchKeys (QA D1310)"
                + SourceScan.MANDATE)
            .isEmpty();
    }

    /** The region with the text of every {@code String} constant of the file it names appended. */
    private static String withConstants(String source, String region) {
        StringBuilder out = new StringBuilder(region);
        Matcher use = CONSTANT_USE.matcher(region);
        while (use.find()) {
            Matcher declared = Pattern.compile("\\bString\\s++" + use.group(1) + "\\s*+=").matcher(source);
            if (declared.find()) {
                out.append('\n').append(source, declared.end(), source.indexOf(';', declared.end()));
            }
        }
        return out.toString();
    }

    private static List<Path> files(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }
}
