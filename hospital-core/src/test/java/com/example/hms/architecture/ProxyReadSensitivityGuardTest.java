package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D2730 (r65, High): a caregiver whose grant was « Dossier de santé
 * uniquement » read « Sérologie syphilis (TPHA) : Positif » and the medicine of
 * an HIV-tagged visit. Every proxy read went through the patient's own read,
 * and none asked the sensitivity tag.
 *
 * <p>The class is closed by three rules over the source, so a new proxy read
 * fails here until it goes through {@code ProxyRecordFilter}:
 * <ol>
 *   <li>every endpoint under {@code /proxy-access/{id}/} reads through a
 *       {@code portalService.getProxy*}/{@code countProxy*} method, or passes
 *       {@code Reader.PROXY} to its service;</li>
 *   <li>every method of {@code PatientPortalServiceImpl} that checks a grant
 *       ({@code verifyProxyAccess}) reads through a {@code *ForProxy} service
 *       method or the filter itself, and never through the patient's own
 *       read;</li>
 *   <li>every {@code *ForProxy} method applies {@code proxyRecordFilter}, and
 *       the imaging service filters a {@code Reader.PROXY} read.</li>
 * </ol>
 */
@DisplayName("D2730: every proxy read withholds sensitive records")
class ProxyReadSensitivityGuardTest {

    private static final Path ROOT = Path.of("src/main/java/com/example/hms");
    private static final String PORTAL_SERVICE = "service/impl/PatientPortalServiceImpl.java";
    private static final String FILTER = "proxyRecordFilter";

    /** A class member's declaration: modifiers at member indentation, then the name and its "(". */
    private static final Pattern MEMBER = Pattern.compile(
        "\\n    (?:public |protected |private )(?:static )?(?:final )?[\\w<>\\[\\],.? ]+?\\s(\\w+)\\s*\\(");

    private static final Pattern PROXY_MAPPING = Pattern.compile("Mapping\\(\\s*\"[^\"]*/proxy-access/\\{");

    /** The patient's own reads: a proxy read that reaches one skips the filter. */
    private static final List<String> OWNER_READS = List.of("ForPatientPortal(", "ForPortalPatient(",
        "ForVerifiedPatient(", "safeLabResults(", "safeMedications(", "safeImmunizations(");

    /** Grant checks that read nothing themselves, with why. Every entry names its defect. */
    private static final Map<String, String> RESOLVERS = Map.of(
        "verifyProxyAccess", "the grant check itself",
        "resolveProxyRecordsPatientId",
        "D2730: resolves the grantor only; its callers pass Reader.PROXY (rule 1)");

    /** Reader-based proxy paths outside the *ForProxy naming: "file#method" to the token its body holds. */
    private static final Map<String, String> READER_PATHS = Map.of(
        "service/imaging/PatientImagingPortalService.java#studiesOf", "shownTo(reader",
        "service/imaging/PatientImagingPortalService.java#openImage", "shownTo(reader");

    @Test
    @DisplayName("rule 1: every /proxy-access/{id}/ endpoint reads through a proxy read")
    void everyProxyEndpointReadsThroughAProxyRead() throws IOException {
        List<String> problems = new ArrayList<>();
        int endpoints = 0;
        for (Path file : sources("controller")) {
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher mapping = PROXY_MAPPING.matcher(code);
            while (mapping.find()) {
                Matcher member = MEMBER.matcher(code);
                if (!member.find(mapping.end())) {
                    continue;
                }
                endpoints++;
                String body = bodyAt(code, member);
                String key = rel(file) + "#" + member.group(1);
                boolean viaPortal = body.contains("portalService.getProxy") || body.contains("portalService.countProxy");
                boolean viaReader = body.contains("Reader.PROXY");
                if (!viaPortal && !viaReader) {
                    problems.add(key + " serves a grantor's data to a proxy without a proxy read: call a"
                        + " portalService.getProxy* method, or pass Reader.PROXY to a service that filters it");
                }
                if (body.contains("resolveProxyRecordsPatientId(") && !viaReader) {
                    problems.add(key + " resolves the grantor and reads with the patient's own reader");
                }
            }
        }
        // Appointments, medications x2, lab results x2, billing, records, imaging x2.
        assertThat(endpoints).as("the proxy endpoints were found").isGreaterThanOrEqualTo(9);
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("rule 2: every grant-checked read of the portal service goes through the proxy filter")
    void everyGrantCheckedReadGoesThroughTheFilter() throws IOException {
        String code = SourceScan.stripComments(Files.readString(ROOT.resolve(PORTAL_SERVICE), StandardCharsets.UTF_8));
        Map<String, String> reads = new LinkedHashMap<>();
        Matcher member = MEMBER.matcher(code);
        while (member.find()) {
            String body = bodyAt(code, member);
            if (body != null && body.contains("verifyProxyAccess(") && !RESOLVERS.containsKey(member.group(1))) {
                reads.merge(member.group(1), body, String::concat);
            }
        }
        List<String> problems = new ArrayList<>();
        reads.forEach((name, body) -> {
            if (!body.contains("ForProxy(") && !body.contains(FILTER)) {
                problems.add(PORTAL_SERVICE + "#" + name + " checks a proxy grant and reads with no *ForProxy"
                    + " method: a sensitive (HIV, behavioural, substance-use, reproductive) row reaches the proxy");
            }
            OWNER_READS.stream().filter(body::contains).forEach(owner -> problems.add(PORTAL_SERVICE + "#" + name
                + " checks a proxy grant and reads through the patient's own " + owner + ", which skips the filter"));
        });
        assertThat(reads.keySet()).as("the proxy reads were found").contains("getProxyRecords", "getProxyLabResults",
            "getProxyMedications", "getProxyAppointments", "getProxyBilling");
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    @Test
    @DisplayName("rule 3: every *ForProxy read, and every Reader.PROXY path, applies the filter")
    void everyProxyReadAppliesTheFilter() throws IOException {
        List<String> problems = new ArrayList<>();
        int proxyReads = 0;
        for (Path file : sources("")) {
            String rel = rel(file);
            if (rel.startsWith("repository/") || rel.startsWith("controller/")) {
                continue;
            }
            String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
            Matcher member = MEMBER.matcher(code);
            while (member.find()) {
                if (!member.group(1).endsWith("ForProxy")) {
                    continue;
                }
                String body = bodyAt(code, member);
                if (body == null) {
                    continue;
                }
                proxyReads++;
                if (!body.contains(FILTER)) {
                    problems.add(rel + "#" + member.group(1) + " is a proxy read and does not apply " + FILTER);
                }
            }
        }
        for (Map.Entry<String, String> path : READER_PATHS.entrySet()) {
            String[] parts = path.getKey().split("#");
            String code = SourceScan.stripComments(Files.readString(ROOT.resolve(parts[0]), StandardCharsets.UTF_8));
            String body = SourceScan.methodBody(code, parts[1]);
            if (body == null || !body.contains(path.getValue())) {
                problems.add(path.getKey() + " no longer holds `" + path.getValue() + "`: a Reader.PROXY read"
                    + " must drop a study of a sensitive visit");
            }
        }
        String imaging = SourceScan.stripComments(Files.readString(
            ROOT.resolve("service/imaging/PatientImagingPortalService.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(imaging, "shownTo")).as("the imaging filter applies " + FILTER)
            .contains(FILTER);
        // Labs x3, medications x3, immunizations, invoices, appointments.
        assertThat(proxyReads).as("the *ForProxy reads were found").isGreaterThanOrEqualTo(9);
        assertThat(problems).as(SourceScan.MANDATE).isEmpty();
    }

    private static String bodyAt(String code, Matcher member) {
        int open = code.indexOf('(', member.end() - 1);
        int close = SourceScan.closingParen(code, open);
        int brace = SourceScan.indexOfOutsideLiterals(code, '{', close);
        int semicolon = SourceScan.indexOfOutsideLiterals(code, ';', close);
        if (brace < 0 || (semicolon >= 0 && semicolon < brace)) {
            return null;
        }
        return code.substring(brace, SourceScan.closingBrace(code, brace) + 1);
    }

    private static List<Path> sources(String dir) throws IOException {
        try (Stream<Path> files = Files.walk(ROOT.resolve(dir))) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
    }

    private static String rel(Path file) {
        return ROOT.relativize(file).toString().replace('\\', '/');
    }
}
