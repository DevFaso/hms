# Isolated backup-restore drill

This is an **operator log template**, not a completed production drill or an O3
scorecard pass. Run with the owner against an offline copy of the latest approved
backup and its object files. The command never connects to a source database,
accepts a database URL, mounts a host directory in Docker, publishes a port, pulls
an image, deploys, or touches an existing database. It creates and removes only
its newly labelled, network-isolated scratch container and its anonymous volumes.

## Preparation

- [ ] Owner/operator and independent observer identified.
- [ ] Approved offline backup root and recovery target time recorded.
- [ ] Latest backup snapshot confirmed; object snapshot belongs to that backup.
- [ ] PostgreSQL major version and required extensions match the source backup.
- [ ] A reviewed local PostgreSQL image is installed; record its immutable digest.
- [ ] Target RTO/RPO and the meaning of the recovery cutoff are agreed.
- [ ] Backup manifests come from the trusted backup process, independently of
  this restore. Counts, hashes and timestamps must be captured from the same
  consistent source snapshot. Do not manufacture a baseline from the restored DB.

Use a local, access-controlled folder. The backup is sensitive even though the
JSON report contains only aggregate counts and hashes. Do not commit backups,
object bytes or identifiers. Protect report access and retention too.

## Offline bundle contract

Each backup is one directory containing `database.dump` (a PostgreSQL custom-format
archive), `manifest.json`, and every referenced document/image file. The manifest:

```json
{
  "snapshotAt": "2026-11-23T08:00:00Z",
  "sourceObservedAt": "2026-11-23T08:01:00Z",
  "archiveSha256": "<SHA-256 of database.dump>",
  "tables": {
    "clinical.example": {"rows": 0, "sha256": "<canonical table SHA-256>"}
  },
  "objects": [
    {"reference": "<reference SHA-256>", "path": "objects/example.bin", "sha256": "<file SHA-256>"}
  ]
}
```

All non-system base tables in **every schema**, including empty ones, must appear.
The canonical table digest is SHA-256 over the byte stream returned by PostgreSQL
`COPY (SELECT row_to_json(t)::text FROM "schema"."table" t ORDER BY
row_to_json(t)::text COLLATE "C") TO STDOUT`, using UTC and
`extra_float_digits=3`. Duplicate rows are retained. Use a compatible PostgreSQL
version and the same serialization settings when preparing the trusted manifest.

File references are discovered from `storage_key`, `*_storage_key`, `file_path`,
`photo_file_path` and `letter_storage_path` columns in every non-system schema.
For each distinct reference, compute SHA-256 of the UTF-8 fields joined by NUL:
`schema`, `table`, `column`, `storage_bucket` (empty when absent), stored key/path.
This avoids writing patient file names or storage keys in the report. Each
reference must map to a reachable local file with its expected checksum. Paths
must stay inside the bundle, including after resolving symlinks. New storage
column conventions require extending this inventory before claiming coverage.
This proves the uploaded objects referenced by these managed storage columns. It
does not prove recovery of an external PACS archive or URL-only external records;
record and drill those stores separately with the owner. A URL alone is not a
backup of its bytes. The engineering test is not a claim that all external
hospital image systems have been restored.

## Commands

```powershell
python scripts/restore/restore_drill.py --backup-root C:/offline/hms-backups --local-postgres-image postgres:18 --report C:/offline/drills/drill-2026-11-23.json
```

`--backup-root` selects the greatest manifest snapshot timestamp, not the folder
name or modification time. Use `--bundle <directory>` to select a specific backup
instead. No report is overwritten. A checksum, table, missing file or restore
failure exits nonzero and produces **no PASS report**. An unowned container is
never removed. A Docker outage may require an operator to remove a leftover
scratch container after independently verifying its ownership label and name.

The local engineering proof uses synthetic data only:

```powershell
python -m unittest discover -s scripts/restore -p "test_*.py" -v
```

It creates a source scratch DB, seeds multiple schemas, duplicate rows, an empty
table and document/image references, makes a backup, removes that source, and
restores into another fresh scratch DB. Negative tests change the expected count,
remove/change files and attempt path escape or cleanup of an unowned container.
No dev or production DB is involved.

## Drill log — fill in during the owner's drill

- Date, operator, observer:
- Backup ID, snapshot timestamp and immutable archive hash:
- Recovery target/source-observed timestamp and its evidence:
- PostgreSQL version and local image digest:
- Command and report location (access controlled):
- Per-schema tables checked / rows / checksum differences:
- Document/image references checked / unreachable / mismatched:
- Restore-and-validation elapsed seconds (reported `rtoSeconds`):
- Data exposure window seconds (reported `rpoSeconds`):
- Agreed RTO/RPO targets / met or not met:
- Application startup, login and representative reads after restore:
- Extension, role/grant, sequence and object-store checks by operator:
- Container/volume cleanup confirmed:
- Problems, owner and follow-up date:
- Owner/observer acceptance and evidence links:

`rtoSeconds` measures scratch startup, restore, reconciliation and cleanup. It is
**not** production service restoration time; measure application readiness and
operational steps separately. `rpoSeconds` is the supplied source-observed cutoff
minus the backup snapshot time. It does not query live production or prove the
last durable clinical transaction; record the backup process's cutoff evidence.
No clinical acceptance, go-live decision or production rollback is automated.
