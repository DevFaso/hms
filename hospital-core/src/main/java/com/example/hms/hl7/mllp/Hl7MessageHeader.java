package com.example.hms.hl7.mllp;

/**
 * Structural view of the MSH (Message Header) segment used to route the
 * inbound message and to build a matching ACK without a full HL7 parser.
 *
 * <p>HL7 v2 uses {@code |} as the field separator and {@code ^} as the
 * component separator. The first character after {@code MSH} is the
 * field separator itself, the next four are the encoding characters
 * ({@code ^~\&}). After that, fields 3..12 carry routing data.
 *
 * <pre>
 * MSH|^~\&|SendingApp|SendingFacility|ReceivingApp|ReceivingFacility|...|MessageType|MessageControlId|...|VersionId
 *  0    1       2            3                4                5            8                9                  11
 * </pre>
 */
public record Hl7MessageHeader(
    String fieldSeparator,
    String encodingCharacters,
    String sendingApplication,
    String sendingFacility,
    String receivingApplication,
    String receivingFacility,
    String messageDateTime,
    String messageType,           // e.g. "ORU^R01" or "ADT^A01"
    String messageControlId,
    String processingId,
    String versionId
) {

    public String messageCode() {
        if (messageType == null) return null;
        int caret = messageType.indexOf('^');
        return caret < 0 ? messageType : messageType.substring(0, caret);
    }

    public String triggerEvent() {
        if (messageType == null) return null;
        int caret = messageType.indexOf('^');
        return caret < 0 ? null : messageType.substring(caret + 1);
    }
}
