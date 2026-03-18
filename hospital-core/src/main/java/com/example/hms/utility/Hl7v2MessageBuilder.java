package com.example.hms.utility;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Minimal HL7v2 message builder / parser for instrument integration scaffolding.
 * Produces OML^O21 (order) and ORU^R01 (result observation) message strings and
 * provides a basic ORU^R01 inbound parser.
 *
 * <p>Field separator: {@code |} &nbsp; Component separator: {@code ^}
 * Line endings follow HL7v2 convention: {@code \r}
 */
@Component
public class Hl7v2MessageBuilder {

    private static final DateTimeFormatter HL7_DT  = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final String SENDING_APP = "HMS";
    private static final String SENDING_FAC = "HOSPITAL";
    private static final String RECEIVING_APP = "LAB_ANALYZER";
    private static final String RECEIVING_FAC = "LAB";
    private static final char SEG_TERM = '\r';

    // ── Outbound OML^O21 – New Lab Order sent to instrument ──────────────────

    /**
     * Builds an OML^O21 (laboratory order) message for the given specimen.
     * Triggered when a specimen is received at the lab.
     */
    public String buildOml021(LabSpecimen specimen) {
        LabOrder order = specimen.getLabOrder();
        String now = LocalDateTime.now().format(HL7_DT);
        String msgId = "HMS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        String patientName = order.getPatient() != null
            ? order.getPatient().getLastName() + "^" + order.getPatient().getFirstName()
            : "UNKNOWN^UNKNOWN";
        String patientId = order.getPatient() != null ? order.getPatient().getId().toString() : "";
        String testCode = order.getLabTestDefinition() != null ? order.getLabTestDefinition().getTestCode() : "";
        String testName = order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : "";
        String priority = order.getPriority() != null ? order.getPriority() : "ROUTINE";
        String collectedAt = specimen.getCollectedAt() != null ? specimen.getCollectedAt().format(HL7_DT) : now;
        String accession = specimen.getAccessionNumber();

        return msh("OML^O21^OML_O21", msgId, now) +
            pid(patientId, patientName) +
            "ORC|NW|" + accession + "|||||||" + now + SEG_TERM +
            "OBR|1|" + accession + "||" + testCode + "^" + testName + "|||" +
            collectedAt + "|||||||||||" + priority + SEG_TERM;
    }

    // ── Outbound ORU^R01 – Result observation sent to downstream systems ──────

    /**
     * Builds an ORU^R01 (unsolicited observation result) for the given lab result.
     */
    public String buildOruR01(LabResult result) {
        LabOrder order = result.getLabOrder();
        String now = LocalDateTime.now().format(HL7_DT);
        String msgId = "HMS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        String patientName = order.getPatient() != null
            ? order.getPatient().getLastName() + "^" + order.getPatient().getFirstName()
            : "UNKNOWN^UNKNOWN";
        String patientId = order.getPatient() != null ? order.getPatient().getId().toString() : "";
        String testCode = order.getLabTestDefinition() != null ? order.getLabTestDefinition().getTestCode() : "";
        String testName = order.getLabTestDefinition() != null ? order.getLabTestDefinition().getName() : "";
        String resultDate = result.getResultDate() != null ? result.getResultDate().format(HL7_DT) : now;
        String abnormalFlag = result.getAbnormalFlag() != null ? toHl7AbnormalFlag(result.getAbnormalFlag().name()) : "N";
        String orderId = order.getId() != null ? order.getId().toString() : "";

        return msh("ORU^R01^ORU_R01", msgId, now) +
            pid(patientId, patientName) +
            "OBR|1|" + orderId + "||" + testCode + "^" + testName + "|||" + resultDate + SEG_TERM +
            "OBX|1|ST|" + testCode + "^" + testName + "||" + result.getResultValue() + "|" +
            result.getResultUnit() + "||||" + abnormalFlag + "|||F|||" + resultDate + SEG_TERM;
    }

    // ── Inbound ORU^R01 parser ────────────────────────────────────────────────

    /**
     * Parsed representation of an inbound ORU^R01 observation result.
     */
    public record ParsedObservation(
        String patientId,
        String testCode,
        String resultValue,
        String resultUnit,
        String abnormalFlag,
        LocalDateTime resultDate
    ) {}

    /**
     * Parses the first OBX segment from an inbound HL7v2 message.
     * Returns {@code null} if the message cannot be parsed.
     */
    public ParsedObservation parseOruR01(String hl7Message) {
        if (hl7Message == null || hl7Message.isBlank()) return null;
        try {
            String[] segments = hl7Message.split("[\r\n]+");
            return parseFirstObx(segments, extractPid(segments));
        } catch (Exception ignored) {
            return null;
        }
    }

    private ParsedObservation parseFirstObx(String[] segments, String patientId) {
        for (String seg : segments) {
            if (seg.startsWith("OBX")) {
                return parseObxSegment(seg, patientId);
            }
        }
        return null;
    }

    private ParsedObservation parseObxSegment(String seg, String patientId) {
        String[] f = seg.split("\\|", -1);
        String testCode = f.length > 3  ? firstComponent(f[3]) : "";
        String value    = f.length > 5  ? f[5]                 : "";
        String unit     = f.length > 6  ? f[6]                 : "";
        String abnFlag  = f.length > 8  ? f[8]                 : "N";
        String datePart = f.length > 14 ? f[14]                : "";
        return new ParsedObservation(patientId, testCode, value, unit, abnFlag, parseHl7DateTime(datePart));
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private String msh(String msgType, String msgId, String now) {
        return "MSH|^~\\&|" + SENDING_APP + "|" + SENDING_FAC + "|" +
            RECEIVING_APP + "|" + RECEIVING_FAC + "|" + now + "||" +
            msgType + "|" + msgId + "|P|2.5.1" + SEG_TERM;
    }

    private String pid(String patientId, String patientName) {
        return "PID|1||" + patientId + "|||" + patientName + SEG_TERM;
    }

    private String firstComponent(String field) {
        int idx = field.indexOf('^');
        return idx >= 0 ? field.substring(0, idx) : field;
    }

    private String extractPid(String[] segments) {
        for (String seg : segments) {
            if (seg.startsWith("PID")) {
                String[] f = seg.split("\\|", -1);
                return f.length > 3 ? firstComponent(f[3]) : "";
            }
        }
        return "";
    }

    private LocalDateTime parseHl7DateTime(String raw) {
        if (raw == null || raw.length() < 8) return LocalDateTime.now();
        try {
            String normalized = raw.length() >= 14 ? raw.substring(0, 14) : raw.substring(0, 8) + "000000";
            return LocalDateTime.parse(normalized, HL7_DT);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    /** Maps internal AbnormalFlag name to single-char HL7v2 abnormal flag. */
    private String toHl7AbnormalFlag(String flagName) {
        return switch (flagName) {
            case "NORMAL"   -> "N";
            case "ABNORMAL" -> "A";
            case "CRITICAL" -> "HH";
            default         -> "N";
        };
    }
}
