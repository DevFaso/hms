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
