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

    public MllpFieldWidthException(String message) {
        this(message, null);
    }

    public MllpFieldWidthException(String message, Hl7MessageHeader replyHeader) {
        super(message);
        this.replyHeader = replyHeader;
    }

    /**
     * The parsed header with each refused field replaced by {@code ?}, so the
     * refusal can echo the message's own MSH-10 in MSA-2 and the sender can
     * match it; null when MSH-10 is the field refused, which cannot be echoed.
     */
    public Hl7MessageHeader replyHeader() {
        return replyHeader;
    }
}
