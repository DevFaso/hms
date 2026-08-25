-- V138: dispense-time verification (Tier 2 item 34).
--
-- WHY: the bedside eMAR five-rights loop (P1 #8) is server-authoritative and
-- fail-closed — FiveRightsVerificationService decides, the UI only mirrors
-- the checklist. Dispensing, one step earlier in the same medication chain,
-- had no verification of any kind. Three holes, all verified by search
-- before this migration was written:
--
--   1. StockLot.expiry_date is WRITTEN at goods-in and READ BY NOTHING.
--      Not the dispense path, not a scheduler, not a report query. The
--      guard actually exists — StockLotRepository.findAvailableLotsByFEFO
--      filters `expiry_date >= CURRENT_DATE` — but the dispense path calls
--      findById(dto.getStockLotId()) directly and goes around it. So a lot
--      that expired two years ago decrements and dispenses normally. This
--      is the built-but-bypassed defect class again: the safety query is
--      there, and the one caller that must honour it does not.
--
--   2. The lot's drug was never compared to the prescription's. The only
--      check on a stock lot was that it belonged to the target pharmacy, so
--      a lot of amoxicillin could be consumed against a prescription for
--      methotrexate and the ledger would balance.
--
--   3. medication_name arrived as free-text on the request DTO and flowed
--      straight into the dispense row, the audit entry and the patient's
--      ready-for-pickup SMS with nothing comparing it to what was
--      prescribed.
--
-- WHAT THIS TABLE-LESS MIGRATION ADDS:
--
-- barcode_value ON THE LOT, NOT ON THE CATALOG ITEM. The scan target has to
-- physically exist before a scan can be required of anyone, and the tasklist
-- claim that #475 already created it is only half right: the WRISTBAND
-- exists (patient scan target, bare UUID, solved) but there is no medication
-- barcode anywhere — MedicationCatalogItem has code, atc_code and
-- rxnorm_code, none of which is a scannable product identifier, and no
-- medication label is printed. A manufacturer GTIN would be the textbook
-- answer and is the wrong one here: this pharmacy receives stock from
-- government allocation, donation and local purchase, so barcodes are
-- inconsistent where they are present at all and absent on repackaged
-- stock. So the system MINTS the identifier it later verifies, exactly as
-- lab.lab_specimens.barcode_value does ("LAB-" + accession) — the precedent
-- is already in the codebase and already prints.
--
-- PER LOT rather than per catalog item, because the lot is what the
-- pharmacist physically picks up, and it is the lot that carries the expiry
-- being checked. Two boxes of the same drug from different consignments are
-- different objects to this workflow.
--
-- THE SCAN IS OPTIONAL, THE SERVER CHECKS ARE NOT. Making the scan
-- mandatory would break every site without a scanner and contradict the
-- paper-fallback dispensing model this deployment explicitly keeps. So:
-- when scan values are supplied they must match, and INDEPENDENTLY of any
-- scan the server always refuses an expired lot and always refuses a lot
-- whose drug is not the prescribed drug. Holes 1 and 2 above close for
-- every site today, scanner or not.
--
-- NO DO BLOCK, so no splitStatements attribute.

-- ── The product scan target ─────────────────────────────────────────────
ALTER TABLE clinical.stock_lots
    ADD COLUMN IF NOT EXISTS barcode_value VARCHAR(64);

COMMENT ON COLUMN clinical.stock_lots.barcode_value IS
    'Server-minted scannable identifier for this lot ("LOT-" + 12 hex). '
    'Printed as a QR on the lot label and compared to the product scan at '
    'dispense time. Null on lots received before V138 — those simply cannot '
    'be product-scanned until a label is printed, which backfills it.';

-- Unique where present: two lots must never answer to the same scan, but
-- pre-V138 rows are legitimately null and a plain UNIQUE would treat every
-- one of them as distinct anyway. Partial keeps the intent explicit.
CREATE UNIQUE INDEX IF NOT EXISTS uk_stock_lot_barcode
    ON clinical.stock_lots (barcode_value)
    WHERE barcode_value IS NOT NULL;

-- ── The verification record on the dispense ─────────────────────────────
ALTER TABLE clinical.dispenses
    ADD COLUMN IF NOT EXISTS patient_scan_value       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS product_scan_value       VARCHAR(255),
    ADD COLUMN IF NOT EXISTS scan_verified_at         TIMESTAMP,
    ADD COLUMN IF NOT EXISTS verification_status      VARCHAR(20)
        NOT NULL DEFAULT 'NOT_VERIFIED',
    ADD COLUMN IF NOT EXISTS verification_overrides   JSONB,
    ADD COLUMN IF NOT EXISTS verification_override_reason VARCHAR(1024);

COMMENT ON COLUMN clinical.dispenses.verification_status IS
    'NOT_VERIFIED (no scan performed — the paper-fallback path), VERIFIED '
    '(every supplied scan matched), or OVERRIDDEN (a check failed and the '
    'pharmacist proceeded with a recorded reason). Mirrors '
    'MedicationAdministrationRecord.five_rights_status deliberately, so the '
    'two steps of the same medication chain read identically.';

COMMENT ON COLUMN clinical.dispenses.verification_overrides IS
    'JSON array of DispenseCheck names that failed and were overridden. '
    'Null when VERIFIED or NOT_VERIFIED.';

-- Only PATIENT and DRUG are overridable and only under a substitution; the
-- constraint below is the storage-level half of that rule. EXPIRY never
-- reaches OVERRIDDEN — an expired lot is refused in the service and no row
-- is written at all — because unlike the isolation override in V137 there
-- is no clinical circumstance in which giving out-of-date medication is the
-- better of two options.
ALTER TABLE clinical.dispenses
    DROP CONSTRAINT IF EXISTS ck_dispense_verification_status;
ALTER TABLE clinical.dispenses
    ADD CONSTRAINT ck_dispense_verification_status
        CHECK (verification_status IN ('NOT_VERIFIED', 'VERIFIED', 'OVERRIDDEN'));

ALTER TABLE clinical.dispenses
    DROP CONSTRAINT IF EXISTS ck_dispense_override_reason;
ALTER TABLE clinical.dispenses
    ADD CONSTRAINT ck_dispense_override_reason
        CHECK (verification_status <> 'OVERRIDDEN'
               OR (verification_override_reason IS NOT NULL
                   AND length(trim(verification_override_reason)) > 0));
