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
 *
 * <p>Review of #1251 (provisional): carrying out a transfer asks
 * {@code requireTransferAMidwifeMayComplete} instead. Its destination is held to her wards all the
 * same; only the bed the patient leaves may be of another ward, and only when a doctor or a nurse
 * ordered the move. Ordering a transfer, and assigning a bed, keep the rule on both beds: the
 * second test pins which path asks which.
 * Behaviour: MidwifeNewbornCareTest, AdmissionServiceImplTest, TransferServiceImplTest.
 */
class MidwifeBedWardGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Pattern GIVES_A_BED =
        Pattern.compile("bedAssignmentService\\s*+\\.\\s*+(?:assignBed|reserveBed)\\s*+\\(");
    /** A declaration up to its opening parenthesis; the method's name is its last word. */
    private static final Pattern DECLARATION = Pattern.compile("(?:public|protected|private)\\s([^(){};=]*+)\\(");
    /** The rule on a bed she gives, or on a transfer she carries out (the destination is hers either way). */
    private static final List<String> RULES =
        List.of("requireBedAMidwifeMayAssign(", "requireTransferAMidwifeMayComplete(");

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
                Matcher method = DECLARATION.matcher(code);
                while (method.find()) {
                    String signature = method.group(1).trim();
                    String name = signature.substring(signature.lastIndexOf(' ') + 1);
                    String body = SourceScan.methodBody(code.substring(method.start()), name);
                    if (body == null || !GIVES_A_BED.matcher(body).find()) {
                        continue;
                    }
                    paths++;
                    if (RULES.stream().noneMatch(body::contains)) {
                        unasked.add(file.getFileName() + "." + name);
                    }
                }
            }
        }
        assertThat(paths).as("the paths that give a stay a bed were found (assign-bed, the transfer order and its"
            + " completion)").isGreaterThanOrEqualTo(3);
        assertThat(unasked).as("a path that gives a stay a bed without asking the midwife's ward rule (QA D2917: a"
            + " midwife moved a patient between general-ward beds through /transfers)" + SourceScan.MANDATE).isEmpty();
    }

    /**
     * The wider rule (the bed she frees may be of another ward) is for carrying out a transfer a
     * doctor or a nurse ordered, and for nothing else: ordering a transfer and assigning a bed ask
     * the rule that holds both beds to her wards.
     */
    @Test
    void onlyCarryingOutATransferAsksTheRuleThatLetsThePatientComeFromAnotherWard() throws IOException {
        String transfers = SourceScan.stripComments(
            Files.readString(MAIN.resolve("service/impl/TransferServiceImpl.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(transfers, "requestTransfer")).as("ordering a transfer" + SourceScan.MANDATE)
            .contains("requireBedAMidwifeMayAssign(").doesNotContain("requireTransferAMidwifeMayComplete(");
        assertThat(SourceScan.methodBody(transfers, "completeTransfer")).as("carrying it out" + SourceScan.MANDATE)
            .contains("requireTransferAMidwifeMayComplete(").contains("getRequestedBy()");
        List<String> elsewhere = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = file.getFileName().toString();
                if (!name.equals("TransferServiceImpl.java") && !name.equals("MidwifeNewbornCare.java")
                        && Files.readString(file, StandardCharsets.UTF_8).contains("requireTransferAMidwifeMayComplete(")) {
                    elsewhere.add(name);
                }
            }
        }
        assertThat(elsewhere).as("the transfer-completion rule asked on another path" + SourceScan.MANDATE).isEmpty();
        // The rule itself: the destination is always checked; only the freed bed depends on who ordered.
        String care = SourceScan.stripComments(
            Files.readString(MAIN.resolve("service/maternity/MidwifeNewbornCare.java"), StandardCharsets.UTF_8));
        assertThat(SourceScan.methodBody(care, "requireTransferAMidwifeMayComplete"))
            .contains("nurseOrPhysicianAt(orderedByUserId, hospitalId)").contains("requireHerWards(");
    }
}
