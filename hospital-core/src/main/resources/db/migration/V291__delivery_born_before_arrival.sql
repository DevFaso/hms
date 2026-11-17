-- QA r46 follow-up to D1712 (provisional rule, pending clinical sign-off).
--
-- On an episode that continues a labour from another hospital, a birth timed
-- before her arrival is refused unless the delivery says she was born before
-- arrival ("né avant l'arrivée", in transit), with a short reason. Both are
-- kept on the delivery record, so the exception stays visible and audited.
ALTER TABLE clinical.delivery_records
    ADD COLUMN IF NOT EXISTS born_before_arrival BOOLEAN NOT NULL DEFAULT false;

ALTER TABLE clinical.delivery_records
    ADD COLUMN IF NOT EXISTS born_before_arrival_reason VARCHAR(500);
