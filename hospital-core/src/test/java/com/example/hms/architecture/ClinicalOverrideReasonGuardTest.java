package com.example.hms.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA r81 D4108 class: a clinical override or justification reason holds at least 10
 * non-whitespace characters ({@code ClinicalOverrideReason}, provisional rule
 * CLINICAL_OVERRIDE_REASON_MINIMUM). Every request field that carries such a reason is listed
 * here with the file that applies the rule; a new override field fails this test until it is
 * either enforced or listed as not clinical, with the reason.
 */
class ClinicalOverrideReasonGuardTest {

    private static final Path MAIN = Path.of("src/main/java/com/example/hms");
    private static final Path DTOS = MAIN.resolve("payload/dto");

    /** A request field named like an override reason, note or rationale. */
    private static final Pattern OVERRIDE_FIELD = Pattern.compile(
        "private\\s+String\\s+(\\w*[Oo]verride\\w*(?:Reason|Note)|rationale|incompatibilityReason)\\s*;");

    private static final String RULE_CALL = "ClinicalOverrideReason.isSufficient(";
    private static final String RULE_ANNOTATION = "@com.example.hms.validation.ClinicalOverrideReasonRequired";

    /** DTO file name + "." + field → the main-source file that applies the rule to it. */
    private static final Map<String, String> ENFORCED = new LinkedHashMap<>();
    static {
        ENFORCED.put("PrescriptionRequestDTO.overrideReason", "service/PrescriptionServiceImpl.java");
        ENFORCED.put("SignPrescriptionRequestDTO.allergyOverrideReason", "service/PrescriptionServiceImpl.java");
        ENFORCED.put("AllergyConflictReviewRequestDTO.rationale", "service/PrescriptionServiceImpl.java");
        ENFORCED.put("LabResultReleaseRequestDTO.qcOverrideReason", "service/LabResultServiceImpl.java");
        ENFORCED.put("DeliveryRecordRequestDTO.recentDeliveryOverrideReason", "service/impl/LaborServiceImpl.java");
        ENFORCED.put("LaborEpisodeRequestDTO.recentDeliveryOverrideReason", "service/impl/LaborServiceImpl.java");
        ENFORCED.put("NurseMedicationAdministrationRequestDTO.overrideReason", "service/impl/NurseTaskServiceImpl.java");
        ENFORCED.put("TransferOrderRequestDTO.isolationOverrideReason", "service/impl/TransferServiceImpl.java");
        ENFORCED.put("ImagingOrderRequestDTO.duplicateOverrideNote", "service/imaging/ImagingDuplicateOverride.java");
        ENFORCED.put("DispenseRequestDTO.cdsOverrideReason", "service/pharmacy/DispenseServiceImpl.java");
        ENFORCED.put("CrossmatchRequestDTO.incompatibilityReason", "service/impl/TransfusionServiceImpl.java");
        // Reasons named plainly « reason » that are clinical overrides all the same.
        ENFORCED.put("CdsAcknowledgementRequestDTO.reason", "service/impl/CdsAcknowledgementServiceImpl.java");
        ENFORCED.put("BreakGlassDeclareRequestDTO.reason", "service/BreakGlassServiceImpl.java");
    }

    /** Override fields that are not a clinical decision, with why. */
    private static final Map<String, String> NOT_CLINICAL = Map.of(
        "InvoiceItemRequestDTO.overrideReason", "a price override on an invoice line (billing)",
        "DispenseRequestDTO.fefoOverrideReason", "stock rotation order (first-expiry-first-out), not a clinical decision",
        "SaleLineRequestDTO.fefoOverrideReason", "stock rotation order on a counter sale",
        "StockTransactionRequestDTO.fefoOverrideReason", "stock rotation order on a stock movement");

    @Test
    void everyClinicalOverrideReasonFieldIsListed() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        try (Stream<Path> files = Files.walk(DTOS)) {
            for (Path file : files.filter(f -> f.getFileName().toString().matches(".*Request\\w*\\.java")).toList()) {
                Matcher m = OVERRIDE_FIELD.matcher(Files.readString(file, StandardCharsets.UTF_8));
                while (m.find()) {
                    found.add(dtoName(file) + "." + m.group(1));
                }
            }
        }
        assertThat(found).as("override fields found in request DTOs").hasSizeGreaterThanOrEqualTo(14);
        List<String> unlisted = found.stream()
            .filter(k -> !ENFORCED.containsKey(k) && !NOT_CLINICAL.containsKey(k)).toList();
        assertThat(unlisted)
            .as("request fields carrying an override reason that neither apply ClinicalOverrideReason nor say why not")
            .isEmpty();
    }

    @Test
    void everyListedFieldAppliesTheMinimum() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Map.Entry<String, String> e : ENFORCED.entrySet()) {
            String dto = e.getKey().substring(0, e.getKey().indexOf('.'));
            String field = e.getKey().substring(e.getKey().indexOf('.') + 1);
            Path dtoFile = findDto(dto);
            assertThat(dtoFile).as("DTO of " + e.getKey()).isNotNull();
            String dtoSource = Files.readString(dtoFile, StandardCharsets.UTF_8);
            assertThat(dtoSource).as(e.getKey() + " still declared").containsPattern("private\\s+String\\s+" + field + "\\s*;");
            if (annotated(dtoSource, field)) {
                continue;
            }
            Path service = MAIN.resolve(e.getValue());
            String source = Files.exists(service) ? Files.readString(service, StandardCharsets.UTF_8) : "";
            String getter = "get" + Character.toUpperCase(field.charAt(0)) + field.substring(1) + "()";
            if (!source.contains(RULE_CALL) || !source.contains(getter)) {
                offenders.add(e.getKey() + " -> " + e.getValue());
            }
        }
        assertThat(offenders).as("override reasons accepted without the 10-character minimum (QA r81 D4108)").isEmpty();
    }

    private static boolean annotated(String source, String field) {
        return Pattern.compile(Pattern.quote(RULE_ANNOTATION) + "\\s+(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*private\\s+String\\s+" + field + "\\s*;")
            .matcher(source).find();
    }

    private static String dtoName(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.length() - ".java".length());
    }

    private static Path findDto(String name) throws IOException {
        try (Stream<Path> files = Files.walk(DTOS)) {
            return files.filter(f -> f.getFileName().toString().equals(name + ".java")).findFirst().orElse(null);
        }
    }
}
