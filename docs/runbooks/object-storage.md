# Object storage (Cloudflare R2) for uploaded files

## Why

Railway stops the old backend **before** starting the new one on any service
that has a volume attached. The backend mounted a volume at `/data/uploads`
for uploaded files, so every deploy meant about 70 s of API downtime (seen on
prod 2026-11-18). With the files in a bucket, the volume can be detached and
Railway overlaps the old and new deploys.

## What is stored, and where

Everything a user (or the server) keeps as a file goes through the storage
port `com.example.hms.storage.FileStorage`. The key is the path the file
always had under the upload root, so **no database row changes**:

| Class | Key | Read through |
|---|---|---|
| Profile images (avatars) | `profile-images/<name>` | `GET /api/uploads/profile-images/{name}` (public, the only public path) |
| Patient documents, medical certificates | `patient-documents/<name>` | patient portal and staff download endpoints (authenticated, audited) |
| Chat attachments | `chat-attachments/<name>` | `GET /api/chat/attachments/{id}/download` (participants only) |
| Chart attachments | `chart-attachments/<name>` | stored only |
| Referral attachments | `referral-attachments/<name>` | stored only |
| Patient photos | `patient-photos/<name>` | `GET /api/patients/{id}/photo` (authenticated) |
| FHIR bulk-export output | `fhir-bulk-exports/<jobId>/<Type>.ndjson` | `GET /api/fhir-bulk-status/{jobId}/file/{name}` (admins) |

The bucket is **private**. The service never creates public or presigned
URLs; every byte leaves through the service's own endpoints above.

Not covered (and not on the volume today): tenant purge archives, written to
`hms.tenant-archive.output-dir` (the JVM temp directory by default). They are
an operator artefact of a purge, not a user upload; making them durable is a
separate decision. The FHIR bulk-export runner writes NDJSON to a scratch
directory (`FHIR_BULK_EXPORT_DIR`) while a job runs and publishes the files to
the bucket when it finishes; a deploy mid-run loses only that run (the job
stays IN_PROGRESS, as before; re-kick `$export`).

## Settings (Railway variables, names only)

| Variable | Value |
|---|---|
| `APP_STORAGE_TYPE` | `s3` (default `local`) |
| `S3_ENDPOINT` | `https://<account-id>.r2.cloudflarestorage.com` |
| `S3_BUCKET` | the bucket name |
| `S3_ACCESS_KEY_ID` | access key id of the bucket-scoped token |
| `S3_SECRET_ACCESS_KEY` | its secret |
| `S3_REGION` | optional, default `auto` (what R2 expects) |
| `APP_STORAGE_BACKFILL_ON_STARTUP` | `true` only for the cut-over deploy(s), then remove |

With `APP_STORAGE_TYPE=s3`, a missing `S3_*` value stops the start-up with a
message that names the missing variable (never a value). The start-up log
says which store is in use: `File storage: S3-compatible bucket '<bucket>'
(reads fall back to local directory '/data/uploads')`.

## Cut-over, per environment (dev first, then prod)

1. **Create the bucket** in Cloudflare R2 (one per environment, e.g.
   `ekeneya-dev-files`, `ekeneya-prod-files`). Leave public access **off**
   (no r2.dev subdomain, no custom domain).
2. **Create an API token** in R2 > Manage API tokens: permission *Object Read
   & Write*, **scoped to that one bucket**. Note the access key id, the secret
   and the account's S3 endpoint.
3. **Set the variables** above on the backend service, with
   `APP_STORAGE_BACKFILL_ON_STARTUP=true`. Keep the volume attached and
   `APP_UPLOAD_DIR` unchanged.
4. **Deploy.** New uploads go to the bucket. Reads of files not yet in the
   bucket fall back to the volume, so nothing is missing during the copy.
5. **Backfill.** Once the service is ready, the backfill copies every file of
   the volume that the bucket lacks, on its own thread. Watch the log for
   `Storage backfill finished: N copied, M already in the bucket, F failed
   (of T local files)`. It never deletes or changes a local file, and a rerun
   copies only what is missing (restart the service to rerun).
6. **Verify the counts.**
   - `failed` is 0; `copied + already in the bucket = local files`.
   - Count the bucket's objects per prefix in the R2 dashboard (or
     `aws s3 ls s3://<bucket>/<prefix>/ --recursive --endpoint-url <S3_ENDPOINT> | wc -l`)
     and compare with the volume (`find /data/uploads/<prefix> -type f | wc -l`).
   - Open an avatar, a patient document (portal and staff), a chat
     attachment and a patient photo that existed before the cut-over.
7. **Detach the volume.** Remove `APP_STORAGE_BACKFILL_ON_STARTUP`, detach
   the volume from the backend service, redeploy. The start-up log now shows
   no fallback directory. Keep the volume (do not delete it) for a week as a
   rollback copy, then delete it.

Rollback before step 7: set `APP_STORAGE_TYPE=local` and redeploy. Files
uploaded while on the bucket are then only in the bucket: copy them back
first (`aws s3 sync s3://<bucket> /data/uploads --endpoint-url <S3_ENDPOINT>`).

## Local development and tests

`APP_STORAGE_TYPE` unset means `local`: files under `app.upload.dir`
(`uploads/`), as before. `S3FileStorageIT` runs the S3 adapter against an
S3-compatible container (Adobe S3Mock) wherever Docker is available.

## Rules for code

- Keep a user's file only through `FileStorage`; never `Files.write/copy`
  under the upload tree. `UploadStorageWriteGuardTest` lists the few classes
  allowed to write files themselves, each with its reason.
- Keys are relative paths checked in one place, `StorageKeys.requireValid`
  (no `..`, no leading `/`, no `\`, no `:`), inside every adapter.
- Never log a key or file name (it can carry a patient's words) or any
  credential.
