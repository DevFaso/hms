package com.example.hms.hl7.mllp;

/**
 * An MSH field wider than the column it is matched against or stored in.
 *
 * <p>A subtype so a caller can tell a refused header from an unreadable one:
 * the dispatcher answers both as an invalid MSH, but the HTTP ORU ingest,
 * which reads the header defensively, logs only this one at WARN. The message
 * names the field and the limit, never the value.
 */
public class MllpFieldWidthException extends MllpProtocolException {

    /** The header to answer on, or null when MSH-10 itself was refused. */
    private final transient Hl7MessageHeader replyHeader;
    /** Structured display arguments; protocol/ACK diagnostic text remains unchanged. */
    private final String fieldName;
    private final Integer maximum;

    public MllpFieldWidthException(String message) {
        this(message, null);
    }

    public MllpFieldWidthException(String message, Hl7MessageHeader replyHeader) {
        this(message, replyHeader, null, null);
    }

    public MllpFieldWidthException(String message, Hl7MessageHeader replyHeader, String fieldName, Integer maximum) {
        super(message);
        this.replyHeader = replyHeader;
        this.fieldName = fieldName;
        this.maximum = maximum;
    }

    public String fieldName() { return fieldName; }
    public Integer maximum() { return maximum; }

    /**
     * The parsed header with each refused field replaced by {@code ?}, so the
     * refusal can echo the message's own MSH-10 in MSA-2 and the sender can
     * match it; null when MSH-10 is the field refused, which cannot be echoed.
     */
    public Hl7MessageHeader replyHeader() {
        return replyHeader;
    }
}
