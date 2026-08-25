-- =============================================================
-- V132: imaging-report signing ceremony columns (Tier 2 item 26).
--
-- WHY
--   Until this migration no imaging report could be created at all:
--   ImagingReportService.createReport and updateReport existed, were
--   unit-tested, and had ZERO production callers — no controller
--   endpoint, no HL7 ingest, no seeder. A radiology order was placed
--   and the radiologist had no path to enter findings, so
--   clinical.imaging_reports could only ever be empty and the portal's
--   Results view was a worklist over rows that could not exist.
--
--   Opening that path means deciding who owns the signature. The
--   entity already carried signed_by_staff_id and signed_at, but the
--   only request DTO that reached them let the CLIENT assert both —
--   the exact pre-V118 anti-pattern that the prescription ceremony
--   (V118) and the encounter-note ceremony (V125) were built to kill.
--   These two columns give a radiology report the same evidence those
--   two hold: a SHA-256 digest over a canonical payload, computed
--   server-side at sign time over server-resolved identity.
--
-- NULLABLE
--   Same stance as V118 and V125, and for the same reason. A row whose
--   signed_at is set while signature_value IS NULL means precisely
--   "signed before this ceremony existed, unverifiable". There is
--   nothing to backfill here in practice — the table cannot contain a
--   row today — but the semantics are pinned so an HL7-ingested
--   external report, which is signed at the sending system and not
--   here, reads honestly rather than looking tampered with.
-- =============================================================

ALTER TABLE clinical.imaging_reports
    ADD COLUMN IF NOT EXISTS signature_algorithm VARCHAR(32);

ALTER TABLE clinical.imaging_reports
    ADD COLUMN IF NOT EXISTS signature_value VARCHAR(128);

-- The radiologist worklist is "unsigned reports at my hospital, oldest
-- first" — the queue a reading room actually works from. Partial index
-- so it stays cheap as the signed archive grows without bound.
CREATE INDEX IF NOT EXISTS idx_imaging_report_unsigned
    ON clinical.imaging_reports (hospital_id, performed_at)
    WHERE signed_at IS NULL;

-- The critical-findings queue is "flagged critical, not yet
-- acknowledged". Item 27 hangs the notification/escalation sweep off
-- exactly this predicate; the index exists here because the columns it
-- reads are already present and the authoring path starts writing
-- critical_result_flagged_at in this PR.
CREATE INDEX IF NOT EXISTS idx_imaging_report_unacked_critical
    ON clinical.imaging_reports (hospital_id, critical_result_flagged_at)
    WHERE critical_result_flagged_at IS NOT NULL
      AND critical_result_acknowledged_at IS NULL;
