-- V279 (CR-01, provisional): when a prescriber signed a child's order as an urgent exception to the
-- second-person dose review. The review stays due after the fact; this column tells the dispense
-- guard that the order was signed that way, instead of a review that was never asked for. An edit
-- of the order clears it. No data. Plain statement, no DO block. Idempotent.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS pediatric_dose_urgent_exception_at TIMESTAMP WITHOUT TIME ZONE;
