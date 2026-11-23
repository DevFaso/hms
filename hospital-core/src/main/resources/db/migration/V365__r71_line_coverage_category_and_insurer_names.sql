-- V365 (QA r71, D3603 and D3600). Additive, plain statements, no DO block, idempotent.

-- 1. QA D3603: what an invoice line bills, as a payer plan's category rule sees it (IMAGING, PROCEDURE, BED_DAY, ...).
--    The rule read the category from the line's price-list item only, so an imaging order posted unpriced and then
--    priced by hand at the till (no item) was billed at the plan's general rate (75 %) instead of its IMAGING rule
--    (50 %), overstating the insurer's claim. The category is now stamped when the line is posted and never changed
--    by a price edit. Null for a line typed at the till.
ALTER TABLE billing.invoice_items ADD COLUMN IF NOT EXISTS coverage_category VARCHAR(30);

ALTER TABLE billing.invoice_items
    DROP CONSTRAINT IF EXISTS ck_invoice_item_coverage_category;
ALTER TABLE billing.invoice_items
    ADD CONSTRAINT ck_invoice_item_coverage_category
    CHECK (coverage_category IS NULL OR coverage_category IN ('CONSULTATION', 'LAB_TEST', 'IMAGING', 'PROCEDURE',
        'BED_DAY', 'TRANSFUSION', 'DELIVERY', 'OTHER_SERVICE'));

--    The lines of invoices still in DRAFT (not issued: no payer share is fixed yet) that no price-list item names
--    and that are not stamped yet, by what the checkout or the delivery charge posted them for (review of #1301, L1):
--    the server's own wording (default consultation, bed days, transfusion, delivery), a lab line, and an order's
--    line named as its order (an imaging study or a procedure, compared trimmed on both sides). A line typed at the
--    till matches none and stays NULL. An issued invoice is never touched. Each statement fills NULLs only.
--    A default consultation or a bed-day line named after the hospital's own service (no wording code, no order)
--    cannot be told from a typed line here: it keeps the default rate until it is posted again.
UPDATE billing.invoice_items ii
   SET coverage_category = CASE
           WHEN ii.text_code = 'billing.checkout.consultation' THEN 'CONSULTATION'
           WHEN ii.text_code = 'billing.checkout.bedDays' THEN 'BED_DAY'
           WHEN ii.text_code = 'billing.checkout.transfusion' THEN 'TRANSFUSION'
           WHEN ii.text_code IN ('maternity.delivery.charge.caesarean', 'maternity.delivery.charge.vaginal')
               OR bi.notes_code = 'maternity.delivery.charge.note' THEN 'DELIVERY'
           ELSE 'LAB_TEST'
       END
  FROM billing.billing_invoices bi
 WHERE ii.billing_invoice_id = bi.id
   AND bi.status = 'DRAFT'
   AND ii.coverage_category IS NULL
   AND ii.price_list_item_id IS NULL
   AND (ii.text_code IN ('billing.checkout.consultation', 'billing.checkout.bedDays', 'billing.checkout.transfusion',
                         'maternity.delivery.charge.caesarean', 'maternity.delivery.charge.vaginal')
        OR bi.notes_code = 'maternity.delivery.charge.note'
        OR ii.item_category = 'LAB_TEST');

UPDATE billing.invoice_items ii
   SET coverage_category = 'IMAGING'
  FROM billing.billing_invoices bi
 WHERE ii.billing_invoice_id = bi.id
   AND bi.status = 'DRAFT'
   AND ii.coverage_category IS NULL
   AND ii.price_list_item_id IS NULL
   AND ii.text_code IS NULL
   AND bi.encounter_id IS NOT NULL
   AND EXISTS (SELECT 1
                 FROM clinical.imaging_orders io
                WHERE io.encounter_id = bi.encounter_id
                  AND btrim(io.study_type) = btrim(ii.item_description));

UPDATE billing.invoice_items ii
   SET coverage_category = 'PROCEDURE'
  FROM billing.billing_invoices bi
 WHERE ii.billing_invoice_id = bi.id
   AND bi.status = 'DRAFT'
   AND ii.coverage_category IS NULL
   AND ii.price_list_item_id IS NULL
   AND ii.text_code IS NULL
   AND bi.encounter_id IS NOT NULL
   AND EXISTS (SELECT 1
                 FROM clinical.procedure_orders po
                WHERE po.encounter_id = bi.encounter_id
                  AND btrim(po.procedure_name) = btrim(ii.item_description));

-- 2. QA D3600: the desk's link form sends a payer code and no insurer name, so each coverage row it made was named by
--    its code (provider_name = payer_code, « MUT-TEST-A »). A row of a hospital that has a payer of that code is
--    named after that payer, as a new link now is. A row whose name is a real name, or whose hospital has no payer
--    of that code, is left as it is (the agreement match also reads a name that is only a code as no name).
UPDATE clinical.patient_insurances pi
   SET provider_name = p.name
  FROM security.user_role_hospital_assignment a,
       billing.payers p
 WHERE pi.assignment_id = a.id
   AND p.hospital_id = a.hospital_id
   AND pi.payer_code IS NOT NULL
   AND p.code = upper(btrim(pi.payer_code))
   AND lower(btrim(pi.provider_name)) = lower(btrim(pi.payer_code))
   AND btrim(p.name) <> '';
