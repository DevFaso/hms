package com.example.hms.architecture;

import com.example.hms.testsupport.GuardAllowlist;
import com.example.hms.testsupport.GuardAllowlist.Allowance;
import com.example.hms.testsupport.SourceScan;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QA D1225 (round 24) class guard: a reason the system stores on a record
 * must reach a READ model of that record.
 *
 * <p>The administer call required an early-dose, hold or refusal reason and
 * stored it on the MAR row; no response carried it, so no screen could show
 * it, and the nurse who came next read "Dernière dose : Administrée" with no
 * hint that it was given early, or why. A reason that is worth requiring is
 * worth reading back where the record is read.
 *
 * <p>The scan: every {@code String} field of an {@code @Entity} whose name
 * says it is a reason ({@code reason}, {@code *Reason}, {@code reasonFor*})
 * must be declared, under the same name, by a read DTO of that entity: a
 * class under {@code payload/} whose name starts with the entity's name, or
 * one listed in {@link #READ_MODELS}, and which is not a request/update/create
 * payload. The fields that reach no read model today are standing debt
 * (tasklist.md), listed with why; the list may only shrink.
 *
 * <p>This proves the backend half (the reason is on the wire). The portal
 * half for the MAR is pinned by the nurse-station and eMAR specs (they render
 * {@code doseHistory} / {@code lastReason}).
 */
@DisplayName("Every stored reason reaches a read model of its record")
class StoredReasonExposureGuardTest {

    private static final Path MAIN = Paths.get("src/main/java/com/example/hms");
    private static final Pattern ENTITY = Pattern.compile("@Entity\\b");
    private static final Pattern REASON_FIELD = Pattern.compile(
        "private\\s+String\\s+(reason|\\w+Reason|reasonFor\\w+)\\s*;");
    private static final Pattern ANY_FIELD = Pattern.compile(
        "private\\s++(?:(?:static|final|transient|volatile)\\s++)*+"
            + "(?:[\\w.<>?]|,\\s*+|\\s++(?:extends|super)\\s++)++\\s++(\\w++)\\s*+[;=]");
    private static final Pattern WRITE_PAYLOAD = Pattern.compile("(Request|Update|Create|Command)(DTO|Dto)?$");

    private static final String NEVER_READ = "QA D1225 hunt (round 24): stored, reaches no read model — standing debt";

    /** Read DTOs whose name does not start with the entity's. */
    static final Map<String, List<String>> READ_MODELS = Map.ofEntries(
        Map.entry("MedicationAdministrationRecord", List.of("MarDoseHistoryDTO")),
        Map.entry("AppointmentWaitlist", List.of("WaitlistEntryResponseDTO")),
        Map.entry("ImagingReportStatusHistory", List.of("ImagingReportStatusDTO")),
        Map.entry("MicroCultureResult", List.of("MicroCultureResponseDTO")),
        Map.entry("PatientImmunization", List.of("ImmunizationResponseDTO")),
        Map.entry("PatientTreatmentConsent", List.of("TreatmentConsentResponseDTO")),
        Map.entry("TransfusionCrossmatch", List.of("CrossmatchResponseDTO")),
        Map.entry("MedicationReconciliationEntry", List.of("MedicationReconciliationDTO")),
        Map.entry("PrescriptionRoutingDecision", List.of("RoutingDecisionResponseDTO")),
        Map.entry("PatientRecall", List.of("RecallResponseDTO")),
        Map.entry("PatientGuarantor", List.of("GuarantorResponseDTO")),
        Map.entry("DigitalSignature", List.of("SignatureResponseDTO")));

    /** Entity.field reaching no read model today. */
    static final Map<String, Allowance> ALLOWED = GuardAllowlist.allow(
        "InvoiceAdjustment.reason", 1, NEVER_READ + " (adjustment list shows the amount only)",
        "InvoiceAdjustment.reversalReason", 1, NEVER_READ,
        "Organization.archiveReason", 1, NEVER_READ + " (organisation lifecycle)",
        "Organization.purgeReason", 1, NEVER_READ + " (organisation lifecycle)",
        "Organization.suspensionReason", 1, NEVER_READ + " (organisation lifecycle)",
        "PatientProblemHistory.reason", 1, NEVER_READ + " (problem status-change history; D1224 branch owns diagnoses)",
        "PatientRecordSharingOptOut.reason", 1, NEVER_READ,
        "PaymentTransaction.reversalReason", 1, NEVER_READ,
        "PostpartumCarePlan.escalationReason", 1, NEVER_READ,
        "PrescriptionTransmission.statusReason", 1, NEVER_READ + " (e-prescription transmission status)",
        "SecurityRevocation.lastRevokedReason", 1, "internal session-revocation bookkeeping, not a clinical record",
        "StaffLeaveRequest.reason", 1, NEVER_READ,
        "StaffShift.cancellationReason", 1, NEVER_READ,
        "AppointmentSlot.blockedReason", 1, NEVER_READ + " (slot admin shows 'blocked' without why)");

    @Test
    @DisplayName("QA D1225: a stored reason is on a read DTO of its entity")
    void everyStoredReasonIsReadBack() throws IOException {
        Map<String, Set<String>> dtoFields = new HashMap<>();
        Map<String, Set<String>> entityReasons = new HashMap<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = MAIN.relativize(file).toString().replace('\\', '/');
                String name = file.getFileName().toString().replace(".java", "");
                String source = SourceScan.stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (rel.startsWith("payload/") && !WRITE_PAYLOAD.matcher(name).find()) {
                    dtoFields.put(name, fieldsOf(source));
                } else if (rel.startsWith("model/") && ENTITY.matcher(source).find()) {
                    Matcher reason = REASON_FIELD.matcher(source);
                    while (reason.find()) {
                        entityReasons.computeIfAbsent(name, k -> new HashSet<>()).add(reason.group(1));
                    }
                }
            }
        }
        assertThat(entityReasons).as("the scan found the entities' reason fields").containsKey("MedicationAdministrationRecord");

        List<String> unread = new ArrayList<>();
        entityReasons.forEach((entity, reasons) -> {
            List<String> readModels = new ArrayList<>(READ_MODELS.getOrDefault(entity, List.of()));
            dtoFields.keySet().stream().filter(dto -> dto.startsWith(entity)).forEach(readModels::add);
            for (String reason : reasons) {
                boolean read = readModels.stream()
                    .anyMatch(dto -> dtoFields.getOrDefault(dto, Set.of()).contains(reason));
                if (!read) {
                    unread.add(entity + "." + reason);
                }
            }
        });
        Map<String, Integer> actual = GuardAllowlist.counts(unread);
        assertThat(GuardAllowlist.overAllowance(actual, ALLOWED))
            .as("a reason the system stores reaches no read DTO of its record, so no screen can show it"
                + " (QA D1225: the MAR's early-dose, hold and refusal reasons). Put it on the record's"
                + " response DTO and show it where the record is read" + SourceScan.MANDATE)
            .isEmpty();
        assertThat(GuardAllowlist.stale(actual, ALLOWED)).as("now read back: delete the allowance").isEmpty();
    }

    private static Set<String> fieldsOf(String source) {
        Set<String> out = new HashSet<>();
        Matcher field = ANY_FIELD.matcher(source);
        while (field.find()) {
            out.add(field.group(1));
        }
        return out;
    }
}
