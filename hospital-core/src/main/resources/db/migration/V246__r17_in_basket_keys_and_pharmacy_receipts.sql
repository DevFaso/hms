-- V246 (QA D1053, the D665 class): an in-basket item's title and body were
-- resolved once, in the staff language (French), and stored as prose, so a
-- clinician reading in Spanish or English saw "Correction : ... Ancienne
-- valeur ..., nouvelle valeur ...". The row now also keeps the message keys and
-- their arguments, and the text is rendered in the reader's language when it
-- is read. The French text stays in title/body for old rows and old clients.
-- Rows written before this have no key and keep showing their stored text.
-- Plain statements, no DO block. Idempotent.

ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS title_key VARCHAR(150);
ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS title_args JSONB;
ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS body_key VARCHAR(150);
ALTER TABLE clinical.in_basket_items
    ADD COLUMN IF NOT EXISTS body_args JSONB;

-- QA D1032: a pharmacy receipt printed a slice of the payment UUID ("Nº
-- 38D0FCFE16"). A payment now stores a readable receipt number, CODE-PH-YYYY-
-- 000001, allocated once from a per-hospital, per-year counter (the invoice
-- counter's shape, V190), so a reprint shows the number first printed.
CREATE TABLE IF NOT EXISTS billing.pharmacy_receipt_sequences (
    id            UUID         NOT NULL,
    hospital_id   UUID         NOT NULL,
    sequence_year INTEGER      NOT NULL,
    last_value    BIGINT       NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_pharmacy_receipt_sequences PRIMARY KEY (id),
    CONSTRAINT uk_prs_hospital_year UNIQUE (hospital_id, sequence_year),
    CONSTRAINT fk_prs_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id) ON DELETE CASCADE
);

ALTER TABLE billing.pharmacy_payments
    ADD COLUMN IF NOT EXISTS receipt_number VARCHAR(40);

-- Payments recorded before this are numbered in the order they were taken,
-- per hospital and year; the counters start after them.
UPDATE billing.pharmacy_payments pp
   SET receipt_number = n.prefix || '-PH-' || n.yr || '-' || LPAD(CAST(n.rn AS VARCHAR), 6, '0')
  FROM (
        SELECT p.id,
               CAST(EXTRACT(YEAR FROM p.created_at) AS INTEGER) AS yr,
               COALESCE(NULLIF(LEFT(UPPER(REGEXP_REPLACE(COALESCE(h.code, ''), '[^A-Za-z0-9]', '', 'g')), 20), ''),
                        'INV') AS prefix,
               ROW_NUMBER() OVER (PARTITION BY p.hospital_id, EXTRACT(YEAR FROM p.created_at)
                                  ORDER BY p.created_at, p.id) AS rn
          FROM billing.pharmacy_payments p
          JOIN hospital.hospitals h ON h.id = p.hospital_id
       ) n
 WHERE pp.id = n.id
   AND pp.receipt_number IS NULL;

INSERT INTO billing.pharmacy_receipt_sequences (id, hospital_id, sequence_year, last_value, created_at, updated_at)
SELECT gen_random_uuid(), p.hospital_id, CAST(EXTRACT(YEAR FROM p.created_at) AS INTEGER), COUNT(*), now(), now()
  FROM billing.pharmacy_payments p
 GROUP BY p.hospital_id, CAST(EXTRACT(YEAR FROM p.created_at) AS INTEGER)
ON CONFLICT (hospital_id, sequence_year) DO NOTHING;
