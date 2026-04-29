package com.example.hms.utility;

import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
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
     *
     * <p>{@code placerOrderNumber} (OBR-2) is the id we assigned when the
     * order was sent — that is the field we use to resolve the inbound
     * result back to a {@link LabOrder}. {@code fillerOrderNumber} (OBR-3)
     * is the analyzer's own id and is captured for traceability only.
     */
    public record ParsedObservation(
        String patientId,
        String placerOrderNumber,
        String fillerOrderNumber,
        String testCode,
        String resultValue,
        String resultUnit,
        String abnormalFlag,
        LocalDateTime resultDate
    ) {}

    /**
     * Parses the first OBX segment from an inbound HL7v2 message and
     * captures the OBR placer/filler order numbers so the result can be
     * routed back to a known {@link LabOrder}.
     *
     * <p>Returns {@code null} if the message cannot be parsed.
     */
    public ParsedObservation parseOruR01(String hl7Message) {
        if (hl7Message == null || hl7Message.isBlank()) return null;
        try {
            String[] segments = hl7Message.split("[\r\n]+");
            String patientId = extractPid(segments);
            String[] obrIds = extractObrOrderNumbers(segments);
            return parseFirstObx(segments, patientId, obrIds[0], obrIds[1]);
        } catch (Exception ignored) {
            return null;
        }
    }

    private ParsedObservation parseFirstObx(String[] segments, String patientId,
                                            String placer, String filler) {
        for (String seg : segments) {
            if (seg.startsWith("OBX")) {
                return parseObxSegment(seg, patientId, placer, filler);
            }
        }
        return null;
    }

    private ParsedObservation parseObxSegment(String seg, String patientId,
                                              String placer, String filler) {
        String[] f = seg.split("\\|", -1);
        String testCode = f.length > 3  ? firstComponent(f[3]) : "";
        String value    = f.length > 5  ? f[5]                 : "";
        String unit     = f.length > 6  ? f[6]                 : "";
        String abnFlag  = f.length > 8  ? f[8]                 : "N";
        String datePart = f.length > 14 ? f[14]                : "";
        return new ParsedObservation(patientId, placer, filler, testCode, value, unit, abnFlag, parseHl7DateTime(datePart));
    }

    private String[] extractObrOrderNumbers(String[] segments) {
        for (String seg : segments) {
            if (seg.startsWith("OBR")) {
                String[] f = seg.split("\\|", -1);
                String placer = f.length > 2 ? firstComponent(f[2]) : "";
                String filler = f.length > 3 ? firstComponent(f[3]) : "";
                return new String[] { placer, filler };
            }
        }
        return new String[] { "", "" };
    }

    // ── Inbound ADT parser ────────────────────────────────────────────────────

    /**
     * Parsed representation of an inbound ADT^A01/A04/A08 message — the
     * subset of PID + PV1 fields the EMPI / Encounter projection needs.
     *
     * <p>Empty strings (and {@code null} dates) indicate a field was not
     * present in the message; the projection layer decides whether that
     * is a hard fail (e.g. missing MRN) or a soft default.
     */
    public record ParsedAdtMessage(
        String triggerEvent,
        String mrn,
        String mrnAssigningAuthority,
        String lastName,
        String firstName,
        String middleName,
        LocalDate dateOfBirth,
        String sex,
        String addressLine1,
        String city,
        String state,
        String zipCode,
        String country,
        String patientClass,
        String assignedLocation,
        String visitNumber,
        LocalDateTime admitDateTime,
        LocalDateTime dischargeDateTime
    ) {}

    /**
     * Parses an inbound ADT message into the fields needed to project
     * a {@code Patient} + {@code Encounter}. Returns {@code null} if the
     * message has no PID-3 (MRN) or no PID/PV1 segments at all.
     */
    public ParsedAdtMessage parseAdtMessage(String hl7Message, String triggerEvent) {
        if (hl7Message == null || hl7Message.isBlank()) return null;
        try {
            String[] segments = hl7Message.split("[\r\n]+");
            String[] pid = findSegment(segments, "PID");
            if (pid == null) return null;
            String[] mrnParts = parseIdentifierList(field(pid, 3));
            if (mrnParts[0] == null || mrnParts[0].isBlank()) {
                // No MRN — refuse: we cannot resolve identity.
                return null;
            }
            String[] name = parseName(field(pid, 5));
            LocalDate dob = parseHl7Date(field(pid, 7));
            String sex = field(pid, 8);
            String[] address = parseAddress(field(pid, 11));

            String[] pv1 = findSegment(segments, "PV1");
            String patientClass = field(pv1, 2);
            String assignedLocation = field(pv1, 3);
            String visitNumber = firstComponent(field(pv1, 19));
            LocalDateTime admit = parseHl7DateTimeOrNull(field(pv1, 44));
            LocalDateTime discharge = parseHl7DateTimeOrNull(field(pv1, 45));

            return new ParsedAdtMessage(
                triggerEvent,
                mrnParts[0],
                mrnParts[1],
                name[0], name[1], name[2],
                dob,
                sex,
                address[0], address[1], address[2], address[3], address[4],
                patientClass,
                assignedLocation,
                visitNumber,
                admit,
                discharge
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    private String[] findSegment(String[] segments, String prefix) {
        if (segments == null) return null;
        for (String seg : segments) {
            if (seg.startsWith(prefix + "|")) {
                return seg.split("\\|", -1);
            }
        }
        return null;
    }

    private String field(String[] segment, int idx) {
        if (segment == null || idx < 0 || idx >= segment.length) return "";
        return segment[idx] == null ? "" : segment[idx];
    }

    private String[] parseIdentifierList(String raw) {
        // PID-3 may be a repeating field separated by ~, with components ID^^^Authority^Type.
        if (raw == null || raw.isBlank()) return new String[] { "", "" };
        String first = raw.split("~", -1)[0];
        String[] comps = first.split("\\^", -1);
        String id = comps.length > 0 ? comps[0] : "";
        String authority = comps.length > 3 ? comps[3] : "";
        return new String[] { id, authority };
    }

    private String[] parseName(String raw) {
        if (raw == null || raw.isBlank()) return new String[] { "", "", "" };
        String[] comps = raw.split("\\^", -1);
        return new String[] {
            comps.length > 0 ? comps[0] : "",
            comps.length > 1 ? comps[1] : "",
            comps.length > 2 ? comps[2] : ""
        };
    }

    private String[] parseAddress(String raw) {
        if (raw == null || raw.isBlank()) return new String[] { "", "", "", "", "" };
        String[] comps = raw.split("\\^", -1);
        return new String[] {
            comps.length > 0 ? comps[0] : "",
            comps.length > 2 ? comps[2] : "",
            comps.length > 3 ? comps[3] : "",
            comps.length > 4 ? comps[4] : "",
            comps.length > 5 ? comps[5] : ""
        };
    }

    private LocalDate parseHl7Date(String raw) {
        if (raw == null || raw.length() < 8) return null;
        try {
            return LocalDate.parse(raw.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parseHl7DateTimeOrNull(String raw) {
        if (raw == null || raw.length() < 8) return null;
        try {
            String normalized = raw.length() >= 14 ? raw.substring(0, 14) : raw.substring(0, 8) + "000000";
            return LocalDateTime.parse(normalized, HL7_DT);
        } catch (Exception e) {
            return null;
        }
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
