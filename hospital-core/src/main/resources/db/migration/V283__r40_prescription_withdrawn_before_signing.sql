-- V283: QA D1537 (round 40). A draft cancelled or discontinued before anyone signed it was shown to the patient as
-- a past medicine: its status (CANCELLED, DISCONTINUED) is the word a signed order takes when it is stopped, and a
-- signed order from before V118 carries no signature either, so neither the status nor the signature columns can
-- tell them apart afterwards. The fact is now recorded when the order is withdrawn (Prescription.setStatus: the
-- status before was a proposal, DRAFT or PENDING_SIGNATURE).
--
-- Backfill: TRUE only where the evidence is unambiguous. The row is withdrawn, carries no signature (no signed_at,
-- no signer, no digest), was never dispatched, has no dispense, fill, routing decision, transmission, administration
-- or refill request, and was created after V118 reached production (2026-08-21, promoted to main that afternoon;
-- every order signed since then carries its signature). Every other row stays FALSE, which means shown: an order
-- signed before V118 and later stopped stays a past medicine. Idempotent.

ALTER TABLE clinical.prescriptions
    ADD COLUMN IF NOT EXISTS withdrawn_before_signing BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE clinical.prescriptions p
   SET withdrawn_before_signing = TRUE
 WHERE p.status IN ('CANCELLED', 'DISCONTINUED')
   AND p.withdrawn_before_signing = FALSE
   AND p.signed_at IS NULL
   AND p.signed_by_staff_id IS NULL
   AND p.signature_value IS NULL
   AND p.dispatched_at IS NULL
   AND p.created_at >= TIMESTAMP '2026-08-22 00:00:00'
   AND NOT EXISTS (SELECT 1 FROM clinical.dispenses d WHERE d.prescription_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM clinical.pharmacy_fills f WHERE f.prescription_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM clinical.prescription_routing_decisions r WHERE r.prescription_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM clinical.prescription_transmissions t WHERE t.prescription_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM clinical.medication_administration_records m WHERE m.prescription_id = p.id)
   AND NOT EXISTS (SELECT 1 FROM clinical.refill_requests q WHERE q.prescription_id = p.id);
