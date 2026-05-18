package com.example.hms.imaging.dicom;

import java.util.List;

/**
 * Upstream DICOMweb client contract (roadmap row 42 follow-on).
 *
 * <p>Implementations bridge HMS's tenant + audit surface to a real
 * upstream PACS — currently the Orthanc {@code /dicom-web} and
 * dcm4chee equivalents. Kept as an interface so the
 * {@link DicomProxyService} stays decoupled from the HTTP transport
 * and can be unit-tested without spinning up a real PACS.
 *
 * <p>The two methods cover the row-42 deliverable's pixel-fetch path:
 *
 * <ul>
 *   <li>{@link #qidoListInstances} — QIDO-RS query for the instance
 *       UIDs belonging to a study. Powers the
 *       {@code GET /api/imaging/dicom/{studyUid}/instances}
 *       endpoint.</li>
 *   <li>{@link #wadoFetchInstance} — WADO-RS fetch for an individual
 *       instance's pixel bytes. Returns a byte array so the controller
 *       can forward as {@code application/dicom}. The future
 *       streaming variant ({@code WebClient + DataBuffer}) is the
 *       row-42 stretch follow-on for large multi-frame studies.</li>
 * </ul>
 */
public interface DicomWebClient {

    /**
     * QIDO-RS: {@code GET <baseUrl>/studies/{studyUid}/instances} →
     * list of {@code 00080018} (SOPInstanceUID) values. Empty list
     * when the study has no instances or the upstream returns 204.
     */
    List<String> qidoListInstances(String studyUid);

    /**
     * WADO-RS: {@code GET <baseUrl>/studies/{studyUid}/instances/{instanceUid}}
     * with {@code Accept: application/dicom} → raw DICOM byte payload.
     * Returns {@code null} when the upstream returns 404 so the
     * controller can render the FHIR-equivalent {@code Not Found}
     * shape rather than letting the exception bubble.
     */
    byte[] wadoFetchInstance(String studyUid, String instanceUid);
}
