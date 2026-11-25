# Isolated backup-restore drill

This is an **operator log template**, not a completed production drill or an O3
scorecard pass. Run with the owner against an offline copy of the latest approved
backup and its object files. The command never connects to a source database,
accepts a database URL, mounts a host directory in Docker, publishes a port, pulls
an image, deploys, or touches a pre-existing application database. It creates and removes only
its newly labelled, network-isolated scratch container and its anonymous volumes.

## Ownership boundary

Producing, scheduling and retaining backups, and collecting the independent source-side manifest, are **owner/O3 work**. The owner supplies an existing approved backup to the drill. `restore_drill.py` only reads that bundle, restores it into a new local scratch Postgres and writes the validation report; it never calls the producer or creates a replacement backup. Codex does not produce or approve hospital backups.

`capture_manifest.py` and `capture_bundle` are separate **owner/O3-only engineering utilities**, not drill steps. Their reconstructed scratch evidence is not an independent source baseline and cannot establish production RPO. Running the consumer never invokes them.

Only a locally selected Unix-socket or Windows named-pipe Docker endpoint is allowed. A remote DOCKER_HOST or remote selected context is refused before any daemon request or container creation. TCP/SSH endpoints, including loopback forwarders, are refused. The verified endpoint is pinned via `--host` for every operation; Docker host/context overrides are removed from the subprocess environment so a context switch cannot redirect a running drill. The same restriction applies to the separate owner utility.

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

All non-system physical tables in **every schema**, including empty ones, must appear.
Partitioned parents, views and foreign tables are excluded; partition leaves are
included once. `ONLY` prevents inherited rows being counted twice.
The canonical table digest is SHA-256 over the byte stream returned by PostgreSQL
`COPY (SELECT row_to_json(t)::text FROM ONLY "schema"."table" t ORDER BY
row_to_json(t)::text COLLATE "C") TO STDOUT`, using UTC and
`extra_float_digits=3`, `IntervalStyle=postgres`, `bytea_output=hex`,
`lc_monetary=C` and `DateStyle=ISO, YMD`. Duplicate rows are retained. Use a compatible PostgreSQL
version and the same serialization settings when preparing the trusted manifest.

The canonical producer is `capture_bundle` in `scripts/restore/restore_drill.py`.
It holds a read-only REPEATABLE READ transaction, exports its MVCC snapshot,
imports that same snapshot for every table digest/reference read, and passes
`--snapshot` to `pg_dump` while the holder remains open. The seeded test writes
a new row after snapshot export and proves it is absent from both dump and digest.
The producer and consumer therefore share the actual serialization implementation.

For **owner/O3 local engineering only**, outside the drill, `scripts/restore/capture_manifest.py` reconstructs
an offline dump in a **new** tool-created scratch source and then invokes the
canonical producer. It accepts `--source-dump`, `--postgres-image`, `--bundle`
and `--objects-index`; no caller-chosen container name, ownership token or DSN is
accepted. It removes that source on exit. For example, prepare an empty local
bundle directory containing its object files and run:

```powershell
python scripts/restore/capture_manifest.py --source-dump C:/offline/source.dump --postgres-image postgres:18 --bundle C:/offline/scratch-bundle --objects-index C:/offline/objects-index.json
```

The objects index is the `objects` array above. Existing dump/manifest files are
never overwritten. Interrupted captures must be inspected and a fresh bundle
used. These manifests carry `sourceKind: isolated-scratch-capture`. Their two
timestamps are read from the **same PostgreSQL clock** on the snapshot-holder
connection; the second is observed immediately before rollback. The host clock
is not compared with them. The reported `snapshotObservationLagSeconds` measures
that local capture interval; `rpoSeconds` is **null** for scratch captures.

A manifest reconstructed from a dump is not an independent production baseline
and must never substitute for the backup owner's source-side evidence. The backup
owner must integrate/approve the same snapshot contract in the trusted source
process. Production backup integration and its operational drill remain manual.

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
Both literal and resolved UNC paths are rejected. This is not detection of mapped
Windows drives or Linux remote mounts: the operator must ensure the folder is a
local offline copy. Archive bytes are hashed again while streaming to `pg_restore`,
so a replacement after the initial checksum cannot yield a PASS. Reports include
the immutable local image ID, both supplied timestamps and bundle directory name.
If cleanup inspection fails, the original drill error is retained with a generic
cleanup note; no unverified container is deleted.

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
- Owner-verified backup exposure/RPO and source-clock/cutoff evidence:
- Scratch snapshot observation lag (`snapshotObservationLagSeconds`, not production RPO):
- Agreed RTO/RPO targets / met or not met:
- Application startup, login and representative reads after restore:
- Extension, role/grant, sequence and object-store checks by operator:
- Container/volume cleanup confirmed:
- Problems, owner and follow-up date:
- Owner/observer acceptance and evidence links:

`rtoSeconds` measures scratch startup, restore, reconciliation and cleanup. It is
**not** production service restoration time; measure application readiness and
operational steps separately. For an independently supplied backup manifest,
`rpoSeconds` is its supplied source-observed cutoff minus its supplied backup time;
the owner must verify both clocks and provenance. Scratch manifests return null.
Neither value queries live production or proves the last durable clinical
transaction; record the backup process’s cutoff evidence.
No clinical acceptance, go-live decision or production rollback is automated.
