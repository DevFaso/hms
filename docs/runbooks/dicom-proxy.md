# DICOM proxy — foundation pass

**Status:** foundation pass shipped on `feat/v2.0-foundation-batch` (roadmap row 42).
**Scope today:** flag + service-skeleton + audit-emission contract. The actual upstream HTTP client (Orthanc or dcm4chee DICOMweb) lands in the row-42 follow-on.

---

## Feature flag

```
app.imaging.dicom-proxy.enabled=${DICOM_PROXY_ENABLED:false}
app.imaging.dicom-proxy.adapter=orthanc
app.imaging.dicom-proxy.base-url=
```

Default OFF. When off:

- `GET /api/imaging/dicom/{studyUid}/instances` → `404 Not Found`
- The existing `Hospital.pacs_viewer_url_template` (V75) remains the only path clinicians reach pixel data — viewing happens directly against the PACS, outside HMS's audit + auth surface.

When on (foundation pass): the endpoint reaches `DicomProxyService.listInstancesForStudy`, which emits an `IMAGING_RESULT_UPDATED` audit event and returns an empty list. The follow-on plugs in the QIDO-RS / WADO-RS bridge.

---

## Surface

### `GET /api/imaging/dicom/{studyUid}/instances`

Allowlist: `SUPER_ADMIN`, `HOSPITAL_ADMIN`, `DOCTOR`, `NURSE`, `RADIOLOGIST`. Returns a JSON array of DICOM instance UIDs for the study.

Foundation pass: empty array. Audit emission fires on every flag-on call so the trail accumulates real-world usage data.

---

## Why proxy instead of direct PACS link?

The existing `Hospital.pacs_viewer_url_template` (row 75) renders a "View in PACS" link in the imaging-report detail panel — clinicians click and land in the PACS UI. That works for the current single-tenant deployment but has three gaps the proxy closes:

1. **Audit gap.** Pixel-data access happens outside HMS — the audit trail stops at the report row, not the actual image fetch. The proxy emits `IMAGING_RESULT_UPDATED` on every fetch so the trail is complete.
2. **Auth gap.** The PACS auth model is independent of HMS RBAC. A clinician logged into HMS but suspended in the PACS sees a confusing 403; a clinician suspended in HMS but still active in the PACS keeps seeing images. The proxy mediates auth + RBAC consistently.
3. **Tenant-isolation gap.** With multi-tenant deployments, the PACS URL template binds the WHOLE hospital to one PACS instance. The proxy lets per-study routing into different PACS back-ends when row 33 (schema-per-tenant) starts shipping for high-isolation customers.

---

## Row-42 follow-on (in priority order)

- **Adapter implementations.**
  - `OrthancDicomProxyAdapter` calling `GET /dicom-web/studies/{uid}/instances` against the env-configured Orthanc.
  - `Dcm4cheeDicomProxyAdapter` calling the equivalent DICOMweb path on dcm4chee.
  - Factory pattern keyed off `app.imaging.dicom-proxy.adapter`.
- **WADO-RS bridging.** `GET /api/imaging/dicom/{studyUid}/series/{seriesUid}/instances/{instanceUid}/frames/{n}` streaming pixel data with `multipart/related; type="application/octet-stream"`. This is the actual deliverable target — instance listing alone is insufficient for clinical viewing.
- **Cross-tenant gate.** Resolve `ImagingOrder.hospital.id` from the study UID + verify against `HospitalContextHolder.getActiveHospitalId()`. Cross-tenant access → 403.
- **Per-hospital PACS routing.** Read `Hospital.pacs_viewer_url_template` (V75) to derive the upstream base URL when `app.imaging.dicom-proxy.base-url` is blank.
- **Caching layer** for instance metadata (Redis, 5-min TTL) since clinicians frequently re-open the same study.
- **Frontend `<app-dicom-viewer>`** component embedded inside the imaging-report detail panel, calling the proxy instead of opening the external PACS in a new tab.

---

## Reference

- `hospital-core/src/main/java/com/example/hms/imaging/dicom/DicomProxyProperties.java`
- `hospital-core/src/main/java/com/example/hms/imaging/dicom/DicomProxyService.java`
- `hospital-core/src/main/java/com/example/hms/controller/DicomProxyController.java`
- `hospital-core/src/test/java/com/example/hms/imaging/dicom/DicomProxyServiceTest.java`
- `hospital-core/src/test/java/com/example/hms/imaging/dicom/DicomProxyControllerIT.java`
- `hospital-core/src/main/resources/db/migration/V75__pacs_viewer_url_template.sql` (the per-hospital URL template the follow-on routing layer keys off)
- `hospital-core/src/main/java/com/example/hms/service/impl/ImagingReportServiceImpl.java` (current consumer of the V75 template)
