-- V190: billing invoices - server-generated per-hospital numbering and
-- idempotent create (QA run 2026-10-18, defect D94).
--
-- Before this change the invoice number was typed by hand and nothing in the
-- database held it unique (the entity's uk_bi_invoice_number was never
-- created by a migration), so two invoices could carry the same number, and a
-- double submit created two invoices.
--
-- 1. Rows already written with a duplicate number inside one hospital keep the
--    oldest row's number; every later duplicate gets a suffix made from its own
--    id, so the unique index below can be created. Idempotent: once the
--    numbers are unique no row matches.
-- 2. (hospital_id, invoice_number) is unique.
-- 3. client_request_id: the key the portal sends with a create. A retry or a
--    double submit with the same key returns the invoice already created
--    instead of creating a second one. Unique per hospital when present.
-- 4. billing.invoice_number_sequences: one counter per hospital and year, read
--    under a row lock by the service to allocate the next number.
--
-- No DO block.

UPDATE billing.billing_invoices bi
   SET invoice_number = LEFT(bi.invoice_number, 41) || '-' || LEFT(CAST(bi.id AS VARCHAR), 8)
  FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY hospital_id, invoice_number
                                  ORDER BY created_at, id) AS rn
          FROM billing.billing_invoices
       ) d
 WHERE d.id = bi.id
   AND d.rn > 1;

CREATE UNIQUE INDEX IF NOT EXISTS uk_bi_hospital_invoice_number
    ON billing.billing_invoices (hospital_id, invoice_number);

ALTER TABLE billing.billing_invoices
    ADD COLUMN IF NOT EXISTS client_request_id UUID;

CREATE UNIQUE INDEX IF NOT EXISTS uk_bi_hospital_client_request
    ON billing.billing_invoices (hospital_id, client_request_id)
    WHERE client_request_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS billing.invoice_number_sequences (
    id            UUID         NOT NULL,
    hospital_id   UUID         NOT NULL,
    sequence_year INTEGER      NOT NULL,
    last_value    BIGINT       NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    CONSTRAINT pk_invoice_number_sequences PRIMARY KEY (id),
    CONSTRAINT uk_ins_hospital_year UNIQUE (hospital_id, sequence_year),
    CONSTRAINT fk_ins_hospital FOREIGN KEY (hospital_id) REFERENCES hospital.hospitals (id) ON DELETE CASCADE
);
