package com.example.hms.hl7.mllp;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Builds an HL7 v2 General Acknowledgement ({@code ACK}) message that
 * mirrors the routing of an inbound MSH and reports an acknowledgement
 * code on the MSA segment.
 *
 * <p>HL7 v2.5+ also supports {@code ACK^XYZ} (specific ack types per
 * trigger). Most analyzers happily accept the simpler general-ack form.
 */
public final class Hl7AckBuilder {

    private static final DateTimeFormatter HL7_DTM = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    public static final String CR = "\r";

    public enum AckCode { AA, AE, AR }

    private Hl7AckBuilder() {}

    public static String buildAck(Hl7MessageHeader inbound, AckCode code, String textMessage) {
        return buildAck(inbound, code, textMessage, LocalDateTime.now(), generateControlId());
    }

    static String buildAck(
        Hl7MessageHeader inbound,
        AckCode code,
        String textMessage,
        LocalDateTime now,
        String controlId
    ) {
        String fs = (inbound.fieldSeparator() == null || inbound.fieldSeparator().isEmpty())
            ? "|" : inbound.fieldSeparator();
        String enc = (inbound.encodingCharacters() == null || inbound.encodingCharacters().isEmpty())
            ? "^~\\&" : inbound.encodingCharacters();
        String version = (inbound.versionId() == null || inbound.versionId().isBlank())
            ? "2.5" : inbound.versionId();

        // Swap sending↔receiving when responding.
        StringBuilder msh = new StringBuilder("MSH").append(fs).append(enc).append(fs)
            .append(safe(inbound.receivingApplication())).append(fs)
            .append(safe(inbound.receivingFacility())).append(fs)
            .append(safe(inbound.sendingApplication())).append(fs)
            .append(safe(inbound.sendingFacility())).append(fs)
            .append(HL7_DTM.format(now)).append(fs)
            .append(fs)             // MSH-8 security (empty)
            .append("ACK")          // MSH-9 message type
            .append(fs)
            .append(controlId)      // MSH-10
            .append(fs)
            .append("P")            // MSH-11 processing id
            .append(fs)
            .append(version);

        StringBuilder msa = new StringBuilder("MSA").append(fs)
            .append(code.name()).append(fs)
            .append(safe(inbound.messageControlId()));
        if (textMessage != null && !textMessage.isBlank()) {
            msa.append(fs).append(textMessage.replace('\r', ' ').replace('\n', ' '));
        }

        return msh.append(CR).append(msa).append(CR).toString();
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String generateControlId() {
        // Compact, unique enough for in-flight correlation.
        return "ACK" + Long.toString(System.nanoTime(), 36).toUpperCase();
    }
}
