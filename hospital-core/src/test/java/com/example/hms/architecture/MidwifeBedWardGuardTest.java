package com.example.hms.architecture;

import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Policy rule 2 (owner 2026-11-20, provisional): a midwife assigns the beds and cots of maternity
 * wards only (and a neonatal cot for a newborn).
 *
 * <p>QA D2917 (r66): the rule was asked on {@code /admissions/{id}/assign-bed} only, so a midwife
 * moved a patient between general-ward beds through {@code /transfers} (the order, then its
 * completion). Every method that gives a stay a bed, or holds one for it, through
 * {@code BedAssignmentService} (the one writer of the bed invariant) asks
 * {@code MidwifeNewbornCare.requireBedAMidwifeMayAssign} first.
 * Behaviour: MidwifeNewbornCareTest, AdmissionServiceImplTest, TransferServiceImplTest.
 */
class MidwifeBedWardGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern GIVES_A_BED = Pattern.compile("bedAssignmentService\\s*\\.\\s*(assignBed|reserveBed)\\s*\\(");
    private static final Pattern METHOD = Pattern.compile(
        "(?:public|protected|private)\\s+(?!class\\b|record\\b|enum\\b|interface\\b)[\\w<>\\[\\], .?]+?\\s+(\\w+)\\s*\\(");
    private static final String RULE = "requireBedAMidwifeMayAssign(";

    @Test
    void everyPathThatGivesAStayABedAsksTheMidwifesWardRule() throws IOException {
        List<String> unasked = new ArrayList<>();
        int paths = 0;
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String code = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!GIVES_A_BED.matcher(code).find()) {
                    continue;
                }
                Matcher method = METHOD.matcher(code);
                while (method.find()) {
                    String body = SourceScan.methodBody(code.substring(method.start()), method.group(1));
                    if (body == null || !GIVES_A_BED.matcher(body).find()) {
                        continue;
                    }
                    paths++;
                    if (!body.contains(RULE)) {
                        unasked.add(file.getFileName() + "." + method.group(1));
                    }
                }
            }
        }
        assertThat(paths).as("the paths that give a stay a bed were found (assign-bed, the transfer order and its"
            + " completion)").isGreaterThanOrEqualTo(3);
        assertThat(unasked).as("a path that gives a stay a bed without asking the midwife's ward rule (QA D2917: a"
            + " midwife moved a patient between general-ward beds through /transfers)" + SourceScan.MANDATE).isEmpty();
    }
}
