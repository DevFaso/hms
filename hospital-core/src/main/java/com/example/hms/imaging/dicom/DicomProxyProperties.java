package com.example.hms.imaging.dicom;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Feature flag for the DICOM proxy (roadmap row 42, v2.0 / Clinical
 * Depth).
 *
 * <p>The deliverable target is "Extend existing imaging/ module with
 * Orthanc or dcm4chee adapter for actual image viewing." Today HMS
 * stores imaging metadata + reports but does NOT proxy DICOM
 * instances — clinicians click the existing
 * `Hospital.pacs_viewer_url_template` (V75) and the PACS handles the
 * actual viewing. The proxy lets HMS-tenant-scoped pixel-data fetches
 * flow through HMS's audit + auth surface instead of bypassing it.
 *
 * <p>Default {@code false}. When off:
 * <ul>
 *   <li>{@code GET /api/imaging/dicom/{studyUid}/instances} returns
 *       {@code 404 Not Found}.</li>
 *   <li>The existing {@code pacs_viewer_url_template} path remains
 *       the only way clinicians reach pixel data.</li>
 * </ul>
 *
 * <p>The foundation pass ships the flag + a service skeleton + the
 * audit-emission contract; the actual Orthanc / dcm4chee HTTP client
 * + DICOMweb (QIDO-RS / WADO-RS) bridging is the named row-42
 * follow-on.
 */
@ConfigurationProperties(prefix = "app.imaging.dicom-proxy")
public class DicomProxyProperties {

    private boolean enabled = false;

    /**
     * Adapter back-end. One of {@code orthanc}, {@code dcm4chee}. The
     * row-42 follow-on adds the per-adapter HTTP-client wiring.
     */
    private String adapter = "orthanc";

    /**
     * Base URL of the upstream DICOMweb endpoint (e.g.
     * {@code https://orthanc.example.com/dicom-web}). Resolved per
     * hospital via the V75 {@code pacs_viewer_url_template} pattern
     * when this is blank.
     */
    private String baseUrl = "";

    /**
     * Platform-wide HTTP basic user for {@link #baseUrl} (the fallback PACS).
     * A hospital's own PACS carries its own credentials (V317).
     */
    private String username = "";

    /** Platform-wide PACS password; env only, never logged or returned. */
    private String password = "";

    /**
     * Whether a PACS URL may name a private, loopback or link-local host.
     * Off in production: a hospital admin types the URL, and the backend
     * would otherwise call into the platform's own network on their behalf
     * (SSRF). Turned on for local development against a docker Orthanc.
     */
    private boolean allowPrivateHosts = false;

    /** The most instances one viewer manifest lists. */
    private int maxInstances = 500;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isAllowPrivateHosts() {
        return allowPrivateHosts;
    }

    public void setAllowPrivateHosts(boolean allowPrivateHosts) {
        this.allowPrivateHosts = allowPrivateHosts;
    }

    public int getMaxInstances() {
        return maxInstances;
    }

    public void setMaxInstances(int maxInstances) {
        this.maxInstances = maxInstances;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdapter() {
        return adapter;
    }

    public void setAdapter(String adapter) {
        this.adapter = adapter;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
