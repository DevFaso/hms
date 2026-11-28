-- V465: an imaging request placed by a nurse or a midwife waits for a physician (QA D4140, r81).
--
-- Rule set IMAGING_ORDER_PHYSICIAN_AUTHORISATION (provisional). The exam is prescribed by a physician: a
-- request placed by a nurse or a midwife is kept as a pending request until a physician of the hospital
-- authorises it (signs it) or declines it. Signing makes the physician the ordering provider, so the order
-- keeps who asked for it in columns of its own.
--
--   * requested_by_user_id / requested_by_name: who placed the request, never rewritten by a signature.
--   * physician_authorisation_required: the request was placed by somebody who is not a signing physician
--     at the order's hospital. It stays true after the signature (the order's history).
--
-- Additive and idempotent. Rows written before this migration keep NULL: the service reads an unsigned
-- order whose ordering provider is a nurse or a midwife as waiting, with or without the flag.
-- No automated rollback (forward-only).

ALTER TABLE clinical.imaging_orders ADD COLUMN IF NOT EXISTS requested_by_user_id uuid;
ALTER TABLE clinical.imaging_orders ADD COLUMN IF NOT EXISTS requested_by_name varchar(200);
ALTER TABLE clinical.imaging_orders ADD COLUMN IF NOT EXISTS physician_authorisation_required boolean;
