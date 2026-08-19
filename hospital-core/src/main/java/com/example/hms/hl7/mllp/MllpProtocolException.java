package com.example.hms.hl7.mllp;

/** Thrown when an MLLP frame is malformed or violates the framing rules. */
public class MllpProtocolException extends RuntimeException {
    public MllpProtocolException(String message) {
        super(message);
    }
}
