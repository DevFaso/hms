-- V229 (QA D783): invoices issued with a total of 0 before the D767 guard
-- (an issued invoice now needs a positive total) are still SENT, so the
-- patient sees them as outstanding forever: nothing can ever be paid on them.
--
-- Reviewed, conservative clean-up. An invoice is cancelled only when ALL hold:
--   * it is issued (any status other than DRAFT or CANCELLED);
--   * its total is 0 and nothing was paid on it (amount_paid = 0);
--   * no payment row of any status (pending, confirmed, rejected, reversed)
--     points at it, so no money trail is touched.
-- Cancelling (never deleting, never going back to DRAFT) keeps its number in
-- the hospital's sequence (QA D613) and its patient (QA D614). Its lines and
-- dates are left as they were.
--
-- Audit trail: one support.audit_event_logs row per cancelled invoice, as a
-- SYSTEM actor (V9), event INVOICE_UPDATED, target BillingInvoice + id. Ids and
-- statuses only: no patient name, no amount (no PHI in audit descriptions).
--
-- Idempotent: a second run finds no issued zero-total invoice and writes no
-- row. The count is raised as a NOTICE (counts only).
-- One DO block, registered with splitStatements="false".
DO $$
DECLARE
    cancelled_count INTEGER;
BEGIN
    WITH cancelled AS (
        UPDATE billing.billing_invoices bi
           SET status = 'CANCELLED',
               updated_at = now()
         WHERE bi.status NOT IN ('DRAFT', 'CANCELLED')
           AND bi.total_amount = 0
           AND COALESCE(bi.amount_paid, 0) = 0
           AND NOT EXISTS (SELECT 1
                             FROM billing.payment_transactions pt
                            WHERE pt.invoice_id = bi.id)
        RETURNING bi.id, bi.hospital_id
    ), audited AS (
        INSERT INTO support.audit_event_logs (
            id, created_at, updated_at, event_timestamp,
            user_id, actor_type, actor_label,
            event_type, status, event_description, details,
            target_entity_type, target_resource_id, hospital_name)
        SELECT gen_random_uuid(), now(), now(), now(),
               NULL, 'SYSTEM', 'Migration V229',
               'INVOICE_UPDATED', 'SUCCESS',
               'Zero-total invoice cancelled by migration V229 (QA D783): issued at 0 before issuing a zero total was refused',
               'status -> CANCELLED; total 0, no payment row',
               'BillingInvoice', c.id::text, h.name
          FROM cancelled c
          LEFT JOIN hospital.hospitals h ON h.id = c.hospital_id
        RETURNING 1
    )
    SELECT count(*) INTO cancelled_count FROM audited;

    RAISE NOTICE 'V229: % issued zero-total invoice(s) cancelled', cancelled_count;
END
$$;
