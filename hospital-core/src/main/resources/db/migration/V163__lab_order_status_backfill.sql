-- V163: lab orders left behind by the old always-on auto-verification.
--
-- Until #716 nothing ever advanced lab.lab_orders.status: the transition
-- endpoint had no caller, and results were released (auto-verification
-- released every non-abnormal result the moment it was saved) without the
-- order moving. So prod carries orders sitting at ORDERED with every result
-- released, and the ordering doctor's review queue — which keys on the
-- order's status — has never shown them.
--
-- completeOrderIfAllReleased only runs from a new release or a new result, so
-- those orders would stay behind forever. This is the one-off catch-up; from
-- here the service keeps the status current.
--
-- Strictly an UPDATE: no DDL, no INSERT, no DELETE. Idempotent — re-running
-- it recomputes the same verdict from the same rows and changes nothing
-- (every UPDATE excludes rows already in its target state).
--
-- Left alone on purpose:
--   CANCELLED — a decision somebody made; results may exist anyway.
--   VERIFIED  — a state only the transition endpoint sets, i.e. a human
--               attested to it; the service never demotes it either.
--   orders with no results at all — nothing has come back yet, so whatever
--               stage the specimen reached is still the truth.

-- (a) Every result released -> COMPLETED. This is the review-queue fix: these
--     are finished orders whose results the doctor has never been shown.
UPDATE lab.lab_orders o
   SET status = 'COMPLETED',
       updated_at = NOW()
 WHERE o.status IN ('ORDERED', 'PENDING', 'COLLECTED', 'RECEIVED', 'IN_PROGRESS', 'RESULTED')
   AND EXISTS (SELECT 1 FROM lab.lab_results r WHERE r.lab_order_id = o.id)
   AND NOT EXISTS (SELECT 1 FROM lab.lab_results r
                    WHERE r.lab_order_id = o.id
                      AND r.released = FALSE);

-- (b) Results exist but at least one is unreleased -> RESULTED. The lab has
--     entered something; the order is past collection whatever the specimen
--     rows say, and the release path will take it to COMPLETED from here.
UPDATE lab.lab_orders o
   SET status = 'RESULTED',
       updated_at = NOW()
 WHERE o.status IN ('ORDERED', 'PENDING', 'COLLECTED', 'RECEIVED', 'IN_PROGRESS')
   AND EXISTS (SELECT 1 FROM lab.lab_results r
                WHERE r.lab_order_id = o.id
                  AND r.released = FALSE);
