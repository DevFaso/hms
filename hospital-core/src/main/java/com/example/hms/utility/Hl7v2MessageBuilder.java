package com.example.hms.utility;

import com.example.hms.enums.AbnormalFlag;
import com.example.hms.model.LabOrder;
import com.example.hms.model.LabResult;
import com.example.hms.model.LabSpecimen;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    /** OBX-11 (HL7 table 0085): the laboratory has released this result. */
    private static final String OBX_STATUS_FINAL = "F";
    /** OBX-11: recorded but not released — the bench is not finished with it. */
    private static final String OBX_STATUS_PRELIMINARY = "P";

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
            ? encodeEscapes(order.getPatient().getLastName()) + "^" + encodeEscapes(order.getPatient().getFirstName())
            : "UNKNOWN^UNKNOWN";
        String patientId = order.getPatient() != null ? encodeEscapes(order.getPatient().getId().toString()) : "";
        String testCode = order.getLabTestDefinition() != null
            ? encodeEscapes(order.getLabTestDefinition().getTestCode()) : "";
        String testName = order.getLabTestDefinition() != null
            ? encodeEscapes(order.getLabTestDefinition().getName()) : "";
        String priority = encodeEscapes(order.getPriority() != null ? order.getPriority() : "ROUTINE");
        String collectedAt = specimen.getCollectedAt() != null ? specimen.getCollectedAt().format(HL7_DT) : now;
        String accession = encodeEscapes(specimen.getAccessionNumber());

        return msh("OML^O21^OML_O21", msgId, now) +
            pid(patientId, patientName) +
            "ORC|NW|" + accession + "|||||||" + now + SEG_TERM +
            "OBR|1|" + accession + "||" + testCode + "^" + testName + "|||" +
            collectedAt + "|||||||||||" + priority + SEG_TERM;
    }

    // ── Outbound ORU^R01 – Result observation sent to downstream systems ──────

    /**
     * Builds an ORU^R01 (unsolicited observation result) for the given lab result.
     *
     * <p>OBX field numbering is the HL7 v2.5 one, the same
     * {@link #parseOruR01} reads: OBX-7 reference range, OBX-8 abnormal
     * flags, OBX-11 observation result status, OBX-14 date/time of the
     * observation. Until this was fixed the flag went out at OBX-10, the
     * status at OBX-13 and the date at OBX-16 — two fields late each —
     * so a receiver (our own parser included) read no flag at all and
     * graded every result normal.
     *
     * <p>OBX-11 reports what this result actually is: {@code F} once the
     * laboratory has released it, {@code P} (preliminary) before that.
     * {@code LabResultServiceImpl.createLabResult} enqueues an outbound
     * ORU for every result, released or not, so a hard-coded {@code F}
     * published unverified values as final — the outbound mirror of the
     * ingest bug fixed alongside it. {@code C} (corrected) is not emitted
     * because the model has no correction concept: nothing on
     * {@code LabResult} distinguishes an amended result from a first one.
     * A release enqueues a second ORU, so a receiver that saw the {@code P}
     * gets the {@code F}.
     */
    public String buildOruR01(LabResult result) {
        LabOrder order = result.getLabOrder();
        String now = LocalDateTime.now().format(HL7_DT);
        String msgId = "HMS-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        // Every stored text is escaped on its own (a CE field's parts one by
        // one, never the joined field), so a delimiter inside it - the caret
        // in 10^9/L, a pipe in a value - is data to the receiver, not a split.
        String patientName = order.getPatient() != null
            ? encodeEscapes(order.getPatient().getLastName()) + "^" + encodeEscapes(order.getPatient().getFirstName())
            : "UNKNOWN^UNKNOWN";
        String patientId = order.getPatient() != null ? encodeEscapes(order.getPatient().getId().toString()) : "";
        String testCode = order.getLabTestDefinition() != null
            ? encodeEscapes(order.getLabTestDefinition().getTestCode()) : "";
        String testName = order.getLabTestDefinition() != null
            ? encodeEscapes(order.getLabTestDefinition().getName()) : "";
        String resultDate = result.getResultDate() != null ? result.getResultDate().format(HL7_DT) : now;
        String abnormalFlag = toHl7AbnormalFlag(result.getAbnormalFlag());
        String resultStatus = result.isReleased() ? OBX_STATUS_FINAL : OBX_STATUS_PRELIMINARY;
        String orderId = order.getId() != null ? encodeEscapes(order.getId().toString()) : "";

        return msh("ORU^R01^ORU_R01", msgId, now) +
            pid(patientId, patientName) +
            "OBR|1|" + orderId + "||" + testCode + "^" + testName + "|||" + resultDate + SEG_TERM +
            "OBX|1|" + valueType(result.getResultValue()) + "|" + testCode + "^" + testName + "||"
            + encodeEscapes(result.getResultValue()) + "|" +
            encodeEscapes(result.getResultUnit()) + "||" + abnormalFlag + "|||" + resultStatus
            + "|||" + resultDate + SEG_TERM;
    }

    /**
     * OBX-2 for a stored value: FT (formatted text, where the line-break
     * escape is defined) when the value spans lines, ST otherwise.
     */
    private static String valueType(String value) {
        return value != null && (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) ? "FT" : "ST";
    }

    // ── Inbound ORU^R01 parser ────────────────────────────────────────────────

    /**
     * Parsed representation of one inbound ORU^R01 observation (one OBX
     * segment).
     *
     * <p>{@code placerOrderNumber} (OBR-2) is the id we assigned when the
     * order was sent — that is the field we use to resolve the inbound
     * result back to a {@link LabOrder}. {@code fillerOrderNumber} (OBR-3)
     * is the analyzer's own id and is captured for traceability only.
     * Both come from the OBR group the OBX belongs to, so a multi-OBR
     * message resolves each observation against its own order.
     *
     * <p>{@code setId} (OBX-1) discriminates sibling observations of one
     * message — every OBX shares the MSH-level dedup triple, so this is
     * what keeps them distinct rows under the V131 unique index.
     *
     * <p>{@code resultStatus} (OBX-11, HL7 table 0085) says whether the
     * value is final ({@code F}), corrected ({@code C}), preliminary
     * ({@code P}), pending ({@code I}), partial ({@code S}) ...; the
     * ingest stores every one but only a final or corrected result moves
     * the order on.
     */
    public record ParsedObservation(
        String patientId,
        String placerOrderNumber,
        String fillerOrderNumber,
        String setId,
        String testCode,
        String resultValue,
        String resultUnit,
        String referenceRange,
        String abnormalFlag,
        LocalDateTime resultDate,
        String resultStatus
    ) {}

    /**
     * Parses EVERY OBX segment from an inbound HL7v2 ORU^R01, grouping
     * each under the most recent OBR so multi-order messages resolve
     * each observation against its own placer/filler numbers.
     *
     * <p>Returns {@code null} if the message cannot be parsed at all;
     * an empty list if it parsed but contains no OBX segments (the
     * caller decides whether that is an error).
     */
    public List<ParsedObservation> parseOruR01(String hl7Message) {
        if (hl7Message == null || hl7Message.isBlank()) return null;
        try {
            String[] segments = hl7Message.split("[\r\n]+");
            String patientId = extractPid(segments);
            List<ParsedObservation> observations = new ArrayList<>();
            String placer = "";
            String filler = "";
            for (String seg : segments) {
                if (seg.startsWith("OBR")) {
                    String[] f = seg.split("\\|", -1);
                    placer = f.length > 2 ? firstComponent(f[2]) : "";
                    filler = f.length > 3 ? firstComponent(f[3]) : "";
                } else if (seg.startsWith("OBX")) {
                    observations.add(parseObxSegment(seg, patientId, placer, filler));
                }
            }
            return observations;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * OBX-2 value types whose OBX-5 is one piece of text, so its escapes are
     * decoded as a whole. A coded value (CE, CWE, CNE, ...) has components of
     * its own, and decoding the whole field would turn an escaped caret inside
     * a component into a component separator; it is stored as received.
     */
    private static final Set<String> TEXT_VALUE_TYPES = Set.of("ST", "TX", "FT", "NM", "SN");

    /**
     * OBX-5 as stored: decoded for a text value type, as received otherwise.
     * Rows written from now on hold decoded text; no stored value carried an
     * escape before this, in any environment, as of 2026-10-06.
     */
    private static String observationValue(String obx2, String obx5) {
        String type = obx2 == null ? "" : obx2.trim().toUpperCase(Locale.ROOT);
        return TEXT_VALUE_TYPES.contains(type) ? decodeEscapes(obx5) : obx5;
    }

    private ParsedObservation parseObxSegment(String seg, String patientId,
                                              String placer, String filler) {
        String[] f = seg.split("\\|", -1);
        String setId    = f.length > 1  ? f[1].trim()          : "";
        String testCode = f.length > 3  ? firstComponent(f[3]) : "";
        String value    = f.length > 5  ? observationValue(f[2], f[5]) : "";
        String unit     = f.length > 6  ? unitIdentifier(f[6]) : "";
        String refRange = f.length > 7  ? f[7]                 : "";
        String abnFlag  = f.length > 8  ? f[8]                 : "N";
        String status   = f.length > 11 ? f[11].trim()         : "";
        String datePart = f.length > 14 ? f[14]                : "";
        return new ParsedObservation(patientId, placer, filler, setId, testCode,
            value, unit, refRange, abnFlag, parseHl7DateTime(datePart), status);
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
     * Parsed representation of an inbound {@code ADT^A40} — patient merge
     * (Tier 2 item 41).
     *
     * <p><b>The direction is the part that is easy to get backwards, so it is
     * named rather than positional.</b> In an A40 the <b>PID</b> segment
     * carries the identifier that SURVIVES and the <b>MRG</b> segment
     * (MRG-1) carries the identifier being retired. Reading it the other way
     * round merges the wrong patient away, and the operation is not
     * meaningfully reversible from the receiving side.
     */
    public record ParsedMergeMessage(
        /** PID-3 — the identifier that survives the merge. */
        String survivingMrn,
        String survivingMrnAssigningAuthority,
        /** MRG-1 — the identifier being retired into the survivor. */
        String priorMrn,
        String priorMrnAssigningAuthority
    ) {}

    /**
     * Parses an inbound {@code ADT^A40} patient-merge message.
     *
     * <p>Returns {@code null} when either identifier is absent — a merge
     * message that names only one side is not a merge, and guessing the
     * other half is not something a parser gets to do.
     */
    public ParsedMergeMessage parseAdtA40(String hl7Message) {
        if (hl7Message == null || hl7Message.isBlank()) return null;
        try {
            String[] segments = hl7Message.split("[\r\n]+");

            String[] pid = findSegment(segments, "PID");
            if (pid.length == 0) return null;
            String[] surviving = parseIdentifierList(field(pid, 3));
            if (surviving[0] == null || surviving[0].isBlank()) return null;

            String[] mrg = findSegment(segments, "MRG");
            if (mrg.length == 0) return null;
            String[] prior = parseIdentifierList(field(mrg, 1));
            if (prior[0] == null || prior[0].isBlank()) return null;

            // Held to the EMPI alias width here, so nothing downstream has to
            // make either identifier safe. Rejected, not truncated: a cut MRN
            // could resolve to a different patient. See Hl7FieldBounds.
            // Trimmed, as the merge service trims them before resolving.
            if (!Hl7FieldBounds.fits(surviving[0].trim(), Hl7FieldBounds.MRN_MAX)
                    || !Hl7FieldBounds.fits(prior[0].trim(), Hl7FieldBounds.MRN_MAX)) {
                return null;
            }

            return new ParsedMergeMessage(
                surviving[0], surviving[1],
                prior[0], prior[1]);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Parses an inbound ADT message into the fields needed to project
     * a {@code Patient} demographic record. Required: a PID segment
     * with a non-blank PID-3 (MRN). PV1 is optional — when absent,
     * the PV1-derived fields ({@code patientClass}, {@code assignedLocation},
     * {@code visitNumber}, admit / discharge timestamps) are returned
     * as empty strings or {@code null}. Returns {@code null} only when
     * the body is unparseable at this minimum level (no PID, blank
     * MRN, or hard parse failure).
     */
    public ParsedAdtMessage parseAdtMessage(String hl7Message, String triggerEvent) {
        if (hl7Message == null || hl7Message.isBlank()) return null;
        try {
            String[] segments = hl7Message.split("[\r\n]+");
            String[] pid = findSegment(segments, "PID");
            if (pid.length == 0) return null;
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

            // PID-3 is what the whole message is resolved on, held to the EMPI
            // alias width. Rejected, not truncated: a cut MRN could resolve to
            // a different patient. PV1-19 and PV1-3 are NOT bounded here: only
            // the visit projection reads them, it is off by default, and
            // refusing the message for them would drop the demographic update
            // it also carries. The projection bounds them. See Hl7FieldBounds.
            if (!Hl7FieldBounds.fits(mrnParts[0].trim(), Hl7FieldBounds.MRN_MAX)) {
                return null;
            }

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

    private static final String[] EMPTY_SEGMENT = new String[0];

    private String[] findSegment(String[] segments, String prefix) {
        if (segments == null) return EMPTY_SEGMENT;
        for (String seg : segments) {
            if (seg.startsWith(prefix + "|")) {
                return seg.split("\\|", -1);
            }
        }
        return EMPTY_SEGMENT;
    }

    private String field(String[] segment, int idx) {
        if (idx < 0 || idx >= segment.length) return "";
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

    /**
     * OBX-6 as a unit: its identifier (first component) with HL7 escapes
     * decoded. Analysers send it coded, {@code mmol/L^millimole per liter^UCUM},
     * and a unit that itself contains a caret travels escaped,
     * {@code 10\S\9/L^...^UCUM}; split first, then decode, so the caret
     * inside the unit survives and {@code 10^9/L} stays distinct from
     * {@code 10^12/L}.
     */
    static String unitIdentifier(String field) {
        return firstComponent(field);
    }

    /**
     * HL7 escapes to plain text. Stored values are plain text, so this is the
     * inverse of {@link #encodeEscapes}, for the default encoding characters
     * this parser assumes:
     * <ul>
     *   <li>\F\ \S\ \T\ \R\ \E\ become | ^ &amp; ~ and the escape character;</li>
     *   <li>\.br\ becomes a line feed, and \Xhh..\ made only of 0D and 0A
     *       becomes those carriage returns and line feeds;</li>
     *   <li>any other escape sequence (\H\, \.sp\, \Z..\, other hex, ...) is
     *       kept whole as literal text, and an unterminated escape character
     *       is a literal backslash.</li>
     * </ul>
     */
    static String decodeEscapes(String text) {
        if (text == null || text.indexOf(ESC) < 0) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            int close = text.charAt(i) == ESC ? text.indexOf(ESC, i + 1) : -1;
            if (close < 0) {
                out.append(text.charAt(i));
                i++;
            } else {
                String sequence = text.substring(i + 1, close);
                String decoded = decodedSequence(sequence);
                out.append(decoded != null ? decoded : text.substring(i, close + 1));
                i = close + 1;
            }
        }
        return out.toString();
    }

    /** What one escape sequence stands for, or null when it is kept as literal text. */
    private static String decodedSequence(String sequence) {
        if (".br".equals(sequence)) {
            return "\n";
        }
        if (sequence.length() == 1) {
            return delimiterFor(sequence.charAt(0));
        }
        return sequence.startsWith("X") ? lineBreaksFromHex(sequence.substring(1)) : null;
    }

    /** Hex pairs that are all 0D or 0A as the characters they encode, else null. */
    private static String lineBreaksFromHex(String hex) {
        if (hex.isEmpty() || hex.length() % 2 != 0) {
            return null;
        }
        StringBuilder out = new StringBuilder(hex.length() / 2);
        for (int k = 0; k < hex.length(); k += 2) {
            String pair = hex.substring(k, k + 2).toUpperCase(Locale.ROOT);
            if ("0D".equals(pair)) {
                out.append('\r');
            } else if ("0A".equals(pair)) {
                out.append('\n');
            } else {
                return null;
            }
        }
        return out.toString();
    }

    /**
     * Plain text into a field. Every escape character is escaped (\E\) -
     * nothing passes through - and so is every delimiter (\F\ \S\ \T\
     * \R\). A line feed goes out as \.br\ and a carriage return as
     * \X0D\, so no stored value can end a segment on the wire, and
     * {@code decodeEscapes(encodeEscapes(x))} is {@code x} for every string.
     * Null is an empty field.
     */
    static String encodeEscapes(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String code = escapeCodeFor(c);
            if (code != null) {
                out.append(ESC).append(code).append(ESC);
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeCodeFor(char c) {
        return switch (c) {
            case ESC -> "E";
            case '|' -> "F";
            case '^' -> "S";
            case '&' -> "T";
            case '~' -> "R";
            case '\n' -> ".br";
            case '\r' -> "X0D";
            default -> null;
        };
    }

    /** The HL7 v2 default escape character. */
    private static final char ESC = '\\';

    private static String delimiterFor(char code) {
        return switch (code) {
            case 'F' -> "|";
            case 'S' -> "^";
            case 'R' -> "~";
            case 'T' -> "&";
            case 'E' -> String.valueOf(ESC);
            default -> null;
        };
    }

    /**
     * A field's first component as plain text: split on the component
     * separator first, then decode, so an escaped caret inside the component
     * ({@code WBC\S\1}) is data and not a split. Every identifier the
     * outbound builders escape (PID-3, OBR-2/OBR-3 order and accession
     * numbers, OBX-3 test code) is read back through this.
     */
    private static String firstComponent(String field) {
        if (field == null) {
            return "";
        }
        int idx = field.indexOf('^');
        return decodeEscapes(idx >= 0 ? field.substring(0, idx) : field);
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

    /**
     * Maps the internal flag to the HL7 v2 OBX-8 code, and an absent flag
     * to an ABSENT field.
     *
     * <p>A blank OBX-8 and an {@code N} are different statements — "nobody
     * graded this" against "the analyzer says it is normal" — and our own
     * ingest depends on the difference: {@code isExplicitlyNormal} releases
     * on {@code N} and holds a blank back for a person to read. Sending
     * {@code N} for an ungraded value (which this did for a null flag, and
     * for any unrecognised name through its {@code default}) would have a
     * peer HMS with auto-verification on publish an ungraded value to a
     * patient — the very thing the receiving half of this PR prevents. So
     * the distinction has to survive egress too.
     *
     * <p>The switch is exhaustive over the enum on purpose: a new
     * {@link AbnormalFlag} constant becomes a compile error here rather
     * than silently going out as normal.
     */
    private String toHl7AbnormalFlag(AbnormalFlag flag) {
        if (flag == null) {
            return "";
        }
        return switch (flag) {
            case NORMAL -> "N";
            case ABNORMAL -> "A";
            case ABNORMAL_LOW -> "L";
            case ABNORMAL_HIGH -> "H";
            case CRITICAL -> "HH";
        };
    }
}
