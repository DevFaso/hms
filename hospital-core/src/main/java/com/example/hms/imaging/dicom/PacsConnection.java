package com.example.hms.imaging.dicom;

/**
 * Where one DICOMweb call goes and how it authenticates. Built per request by
 * {@link PacsConnectionResolver}; never persisted, never serialised.
 *
 * @param baseUrl  the DICOMweb root, without a trailing slash
 * @param username HTTP basic user, blank for none
 * @param password HTTP basic password, blank for none
 * @param source   whose PACS this is
 */
public record PacsConnection(String baseUrl, String username, String password, Source source) {

    /** Whose PACS answers: the hospital's own (V317) or the platform fallback. */
    public enum Source { HOSPITAL, PLATFORM }

    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }

    /** A hospital's own PACS holds only its studies; the platform one may hold several hospitals'. */
    public boolean dedicated() {
        return source == Source.HOSPITAL;
    }

    /** Never prints the password (a record's default toString would). */
    @Override
    public String toString() {
        return "PacsConnection{source=" + source + ", baseUrl=" + baseUrl
            + ", credentials=" + (hasCredentials() ? "set" : "none") + "}";
    }
}
