# PACS (Orthanc) and the in-app image viewer

**Status:** built in `feat/imaging-pacs-orthanc` (migration V317). Off until the
platform flag is on AND a PACS is connected. Nothing in this document has been
provisioned in any environment: every cloud step below is for the owner to
decide and do.

---

## 1. What it does

Imaging reports used to show no images: the DICOM proxy existed but nothing was
configured, and its only link to pixels was the external « Voir dans le PACS »
button. Now:

```
modality ──DICOM C-STORE (AE ORTHANC, port 4242)──▶ Orthanc (DICOMweb plugin)
                                                        ▲
                                     QIDO-RS / WADO-RS /rendered (basic auth, https)
                                                        │
browser ──/api/imaging/dicom/reports/{id}/...──▶ e-Keneya backend (auth, hospital scope, audit)
```

- The browser never talks to the PACS. It asks e-Keneya for **a report's**
  images; e-Keneya loads the report under the caller's hospital scope (another
  hospital's report is a 404), takes the study from the report, and fetches
  frames already **rendered to JPEG/PNG by the PACS** (DICOMweb
  `.../frames/{n}/rendered`). No DICOM parser or viewer bundle ships in the
  portal; the viewer is a plain image with paging, frames, brightness,
  contrast, invert and zoom. It is a viewing aid, not a diagnostic workstation.
- Who sees images: a clinical role of the caller's own (DOCTOR, NURSE, MIDWIFE,
  RADIOLOGIST). The admin roles read reports but not pixels (user decision
  2026-11-14).
- Opening the viewer writes one `DATA_ACCESS` audit row (user, hospital,
  patient, report, study UID). Frames are served under the same checks without
  a row each. Log lines carry the report id and a fixed reason only; never a
  UID, an accession number, a PACS body or a credential.
- The « Voir dans le PACS » link stays when the hospital sets a viewer URL
  template (OHIF, Orthanc Explorer 2, Stone...).

### Which PACS answers

1. `app.imaging.dicom-proxy.enabled` (`DICOM_PROXY_ENABLED`) is the **master
   switch**. Off: every report says « L'affichage des images n'est pas encore
   activé sur e-Keneya ».
2. The **hospital's own PACS** when its admin switched it on in
   *Paramètres → Connexion PACS* (`/imaging-pacs-settings`) with a DICOMweb URL.
3. Otherwise the **platform PACS** (`DICOM_PROXY_BASE_URL`, `DICOM_PROXY_USERNAME`,
   `DICOM_PROXY_PASSWORD`).
4. Neither: « Aucun PACS n'est connecté pour cet hôpital ».

A platform PACS shared by several hospitals is for dev/test. In production each
hospital should have its own PACS: the study is reached through the report's
UID, and accession-number matching (below) is **only** done on a hospital's own
PACS, because accession numbers are not unique across hospitals.

### How a report finds its study

1. **Study Instance UID on the report** — typed by the technician or radiologist
   in the report form (field « UID de l'étude »), validated as a DICOM UID.
2. **Accession number** — when the report has no UID but an accession number
   and the hospital uses its own PACS, e-Keneya asks the PACS for the studies
   with that accession number (QIDO-RS). Exactly one: linked (« Étude retrouvée
   dans le PACS par son numéro d'accession »). Several: « Plusieurs études... »,
   enter the UID.
3. **HL7 / modality worklist** — not available today: e-Keneya has no imaging
   ORM/ORU or MWL feed (the HL7 inbound handles lab ORU and ADT only). When a
   worklist is added, it should write the accession number on the order so step
   2 links automatically.

---

## 2. Running Orthanc

### Local development (docker compose profile `pacs`)

```bash
docker compose --profile pacs up -d orthanc
# Orthanc Explorer: http://localhost:8042  (user hms / hms-dev-only)
# DICOMweb root:    http://localhost:8042/dicom-web
# DICOM listener:   AE title ORTHANC, port 4242
```

Backend environment for local dev:

```bash
DICOM_PROXY_ENABLED=true
DICOM_PROXY_ALLOW_PRIVATE_HOSTS=true   # dev ONLY: lets the URL be localhost / http
# either a platform PACS...
DICOM_PROXY_BASE_URL=http://localhost:8042/dicom-web
DICOM_PROXY_USERNAME=hms
DICOM_PROXY_PASSWORD=hms-dev-only
# ...or leave those blank and set the hospital's own PACS in the portal.
```

Load a test study (any anonymised sample DICOM file):

```bash
# DCMTK
storescu -aec ORTHANC localhost 4242 sample.dcm
# or the Orthanc REST API
curl -u hms:hms-dev-only -X POST http://localhost:8042/instances --data-binary @sample.dcm
```

Then copy the study's `StudyInstanceUID` (Orthanc Explorer → study → tags) into
an imaging report's « UID de l'étude », or give the report the study's
accession number.

### Production (to be provisioned by the owner)

- **Image:** `orthancteam/orthanc` pinned to a release tag (the compose file uses
  `24.12.1`; check the current release before deploying). It bundles the
  DICOMweb plugin (`DICOM_WEB_PLUGIN_ENABLED=true`).
- **Auth:** `ORTHANC__AUTHENTICATION_ENABLED=true`, one dedicated user for
  e-Keneya in `ORTHANC__REGISTERED_USERS` (a long random password, kept in the
  secret store, never in git). Disable remote access to the Orthanc Explorer UI
  or put it behind the hospital VPN.
- **TLS:** e-Keneya calls only **public https** URLs (`allow-private-hosts`
  stays false in production; see « Security » below). Put Orthanc behind a
  reverse proxy (Caddy, nginx, Cloudflare Tunnel) that terminates TLS and
  forwards only `/dicom-web/` to Orthanc.
- **Storage:**
  - simplest: a persistent volume on `/var/lib/orthanc/db` (back it up);
  - S3-compatible object storage (AWS S3, Cloudflare R2, MinIO): the
    `orthancteam/orthanc` image ships the S3 plugin. Set
    `S3_PLUGIN_ENABLED=true` and the `ORTHANC__AWS_S3_STORAGE__*` keys
    (`BUCKET_NAME`, `REGION`, `ENDPOINT` for R2/MinIO, `ACCESS_KEY`,
    `SECRET_KEY`, `VIRTUAL_HOST_STYLE=false` for R2). Keep the index database
    on PostgreSQL (`POSTGRESQL_PLUGIN_ENABLED=true`, `ORTHANC__POSTGRESQL__*`)
    so it survives restarts. Bucket private, encryption at rest on.
- **Where:** in the hospital (on-premise box next to the modalities, exposed
  through a tunnel) or in the cloud next to e-Keneya. Images are patient data:
  same retention and access rules as the rest of the record.

### How a modality sends to Orthanc

On the modality (X-ray console, CT, ultrasound) add a **DICOM destination
(Storage SCP)**:

| Setting   | Value                                        |
|-----------|----------------------------------------------|
| AE title  | `ORTHANC` (Orthanc's `DicomAet`)             |
| Host / IP | the Orthanc box on the hospital network      |
| Port      | `4242`                                       |

In Orthanc, list the modality (`ORTHANC__DICOM_MODALITIES`, or *Orthanc
Explorer 2 → Modalities*) and turn `DicomAlwaysAllowStore` off in production, so
only known modalities can store. Port 4242 stays **inside** the hospital
network; it is never exposed to the internet. Test with « Echo » from the
modality (C-ECHO).

Ask the technicians to fill the **accession number** on the modality worklist
or console with the e-Keneya accession number written on the report: that is
what links a study automatically.

---

## 3. Configuring a hospital in e-Keneya

1. Platform (owner, once): `DICOM_PROXY_ENABLED=true` on the backend service.
2. Hospital admin (or super-admin): *Paramètres → Connexion PACS*:
   - « Adresse DICOMweb du PACS »: `https://pacs.<hospital>.example/dicom-web`
   - user name / password of the e-Keneya user in Orthanc (the password is
     stored AES-GCM-encrypted, never shown again; « laissez vide pour le
     conserver »)
   - optional « Lien vers la visionneuse externe »:
     `https://pacs.<hospital>.example/ui/app/#/filtered-studies?StudyInstanceUID={studyInstanceUid}`
     or an OHIF URL with `{studyInstanceUid}` / `{accessionNumber}`
   - tick « Utiliser le PACS de cet hôpital pour la visionneuse », **Enregistrer**,
     then **Tester la connexion** (OK / injoignable / identifiants refusés /
     adresse refusée).
3. Radiologist / technician: put the study UID (or the accession number) on the
   report. Clinicians open the report and click « Voir les images ».

API: `GET|PUT /api/hospitals/{hospitalId}/pacs-settings`,
`POST /api/hospitals/{hospitalId}/pacs-settings/test` (HOSPITAL_ADMIN of that
hospital, SUPER_ADMIN). Each change is a `CONFIGURATION_CHANGED` audit row
naming the fields changed, never the password.

---

## 4. Security notes

- **SSRF fence** (`PacsUrlPolicy`): the DICOMweb URL must be https, without
  user-info, query or fragment, and must not resolve to a loopback, private,
  link-local (cloud metadata), CGNAT, unique-local or multicast address. Checked
  when saved and before every call; redirects are never followed; answers are
  size-capped (8 MB JSON, 32 MB per frame) and a non-image answer to `/rendered`
  is refused. `DICOM_PROXY_ALLOW_PRIVATE_HOSTS=true` lifts the address check for
  local development only — never set it in production.
- **UIDs** reaching a PACS path are checked as DICOM UIDs first, so no value can
  add `../`, `?` or `#` to the upstream URL (the legacy
  `/imaging/dicom/{studyUid}/instances` client got the same check).
- **Secrets:** the hospital PACS password is `@JsonIgnore`, encrypted by
  `EncryptedStringConverter`, write-only in the API, absent from `toString` and
  from the audit row (guard: `PacsSecretNeverSerializedGuardTest`). The
  platform password lives only in the environment.
- **Caching:** frames are sent `Cache-Control: no-store` and `nosniff`.

---

## 5. Testing without a real PACS

- Backend: `PacsDicomWebGatewayTest` runs the DICOMweb calls against a real
  local HTTP server standing in for Orthanc; `ImagingStudyViewerServiceTest`,
  `PacsSettingsServiceTest`, `PacsViewerControllersTest`, `PacsUrlPolicyTest`.
- Dev environment (flag off): every report shows « L'affichage des images
  n'est pas encore activé... »; the PACS settings page loads, saves and says the
  platform switch is off; the connection test answers « désactivé ».
- With `docker compose --profile pacs` locally: the full path end to end.

## Reference

- `hospital-core/src/main/java/com/example/hms/imaging/dicom/` (`PacsDicomWebGateway`,
  `PacsConnectionResolver`, `PacsUrlPolicy`, `DicomUid`)
- `hospital-core/src/main/java/com/example/hms/service/imaging/ImagingStudyViewerService.java`
- `hospital-core/src/main/java/com/example/hms/service/imaging/PacsSettingsService.java`
- `hospital-core/src/main/resources/db/migration/V317__hospital_pacs_dicomweb_settings.sql`
- `hospital-portal/src/app/imaging/study-viewer/`, `hospital-portal/src/app/imaging/pacs-settings/`
- `docs/runbooks/dicom-proxy.md` (the foundation pass)
