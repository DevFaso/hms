package com.example.hms.imaging.dicom;

/**
 * The PACS could not answer: unreachable, timed out, refused our credentials,
 * or answered outside DICOMweb. Carries a fixed reason, never the upstream
 * body (which can name the patient).
 */
public class PacsUnavailableException extends RuntimeException {

    /** Why the PACS did not answer. */
    public enum Reason { UNREACHABLE, UNAUTHORIZED, REJECTED, URL_REFUSED }

    private final transient Reason reason;

    public PacsUnavailableException(Reason reason) {
        super("PACS unavailable: " + reason, null, false, false);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
