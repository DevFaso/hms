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
    public MllpFieldWidthException(String message) {
        super(message);
    }
}
