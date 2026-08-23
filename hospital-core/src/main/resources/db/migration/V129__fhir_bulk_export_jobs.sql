-- V129: persistent FHIR bulk-export jobs (P3 item 24, roadmap row 21 follow-on).
--
-- WHY: the foundation pass (V-none — in-memory ConcurrentHashMap in
-- FhirBulkExportService) queued jobs that could never finish: no runner,
-- no output, and a restart wiped every job. These tables are the durable
-- job store the @Scheduled runner sweeps: one row per $export invocation,
-- one child row per emitted NDJSON file. platform schema — infra state,
-- the V103 adt_intake_provider_config / V127 platform_downtime_state
-- precedent. docs/fhir-bulk.md said the table lands "V103 in the next
-- free slot" — that doc was stale; it lands here.
--
-- Output NDJSON lives on local disk under
-- app.fhir.operations.bulk-export.storage-dir, a SIBLING of the public
-- upload tree (the V126 patient-photo precedent) — never under
-- /uploads/**, which is served permitAll. Files stream only through the
-- authenticated download endpoint, so requiresAccessToken=true in the
-- manifest is literally true.

CREATE TABLE IF NOT EXISTS platform.fhir_bulk_export_jobs (
    id                    UUID          NOT NULL DEFAULT gen_random_uuid(),
    hospital_id           UUID          NOT NULL,
    scope                 VARCHAR(20)   NOT NULL,
    since_instant         TIMESTAMPTZ,
    -- Comma-joined _type filter; empty/null = every supported type.
    types                 VARCHAR(500),
    group_id              VARCHAR(100),
    status                VARCHAR(20)   NOT NULL DEFAULT 'QUEUED',
    -- The kickoff URL, echoed back as "request" in the completion manifest.
    request_url           VARCHAR(500),
    requested_by_username VARCHAR(255),
    requested_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at            TIMESTAMPTZ,
    completed_at          TIMESTAMPTZ,
    error_message         VARCHAR(1000),
    processed_patients    INT           NOT NULL DEFAULT 0,
    total_patients        INT,
    created_at            TIMESTAMP     NOT NULL DEFAULT now(),
    updated_at            TIMESTAMP     NOT NULL DEFAULT now(),

    CONSTRAINT pk_fhir_bulk_export_jobs PRIMARY KEY (id),
    CONSTRAINT fk_bulk_export_hospital  FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals(id)
);

-- The runner's sweep: "which jobs still need work", cheap even as
-- terminal rows accumulate (cancel/complete never deletes).
CREATE INDEX IF NOT EXISTS idx_bulk_export_open
    ON platform.fhir_bulk_export_jobs (requested_at)
    WHERE status IN ('QUEUED', 'IN_PROGRESS');

CREATE TABLE IF NOT EXISTS platform.fhir_bulk_export_files (
    id             UUID         NOT NULL DEFAULT gen_random_uuid(),
    job_id         UUID         NOT NULL,
    resource_type  VARCHAR(60)  NOT NULL,
    file_name      VARCHAR(255) NOT NULL,
    resource_count INT          NOT NULL DEFAULT 0,
    created_at     TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMP    NOT NULL DEFAULT now(),

    CONSTRAINT pk_fhir_bulk_export_files PRIMARY KEY (id),
    CONSTRAINT fk_bulk_export_file_job   FOREIGN KEY (job_id)
        REFERENCES platform.fhir_bulk_export_jobs(id) ON DELETE CASCADE,
    -- One file per resource type per job — the manifest shape.
    CONSTRAINT uq_bulk_export_file       UNIQUE (job_id, resource_type)
);
