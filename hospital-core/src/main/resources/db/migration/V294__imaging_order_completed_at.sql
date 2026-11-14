-- V294 (r44 imaging review): the order's completion time.
-- The order drawer showed "Terminée le —" on a RESULTS_AVAILABLE order whose
-- report was signed: imaging_orders had no completion column, and the
-- status_updated_at stamp is overwritten when a COMPLETED order is promoted to
-- RESULTS_AVAILABLE at signing, so the completion fact was lost.
-- ImagingOrderServiceImpl (status endpoint) and ImagingReportServiceImpl
-- (signing) now fill completed_at on the first entry into a resulted state.
-- Backfill from recorded facts only:
--   1. a resulted order with a signed report: that report's completed_at,
--      else performed_at, else signed_at (the earliest signed report);
--   2. a COMPLETED order without one: status_updated_at, which is the stamp
--      of its last status change (a resulted order is locked, so that change
--      was the move to COMPLETED).
-- Anything else stays NULL: no time is invented. Idempotent.

ALTER TABLE clinical.imaging_orders
    ADD COLUMN IF NOT EXISTS completed_at TIMESTAMP;

UPDATE clinical.imaging_orders o
   SET completed_at = r.done_at
  FROM (SELECT imaging_order_id,
               MIN(COALESCE(completed_at, performed_at, signed_at)) AS done_at
          FROM clinical.imaging_reports
         WHERE signed_at IS NOT NULL
         GROUP BY imaging_order_id) r
 WHERE r.imaging_order_id = o.id
   AND o.completed_at IS NULL
   AND o.status IN ('COMPLETED', 'RESULTS_AVAILABLE');

UPDATE clinical.imaging_orders
   SET completed_at = status_updated_at
 WHERE completed_at IS NULL
   AND status = 'COMPLETED'
   AND status_updated_at IS NOT NULL;
