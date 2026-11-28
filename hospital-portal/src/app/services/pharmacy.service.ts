import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import type { AllergyReviewState } from '../shared/allergy-acknowledgement/allergy-acknowledgement';
import { CdsCard } from '../shared/cds-card/cds-card.model';
import { MobileMoneyOperator } from '../shared/mobile-money-operator';
import { SensitivityCategory } from '../shared/sensitivity/sensitivity-category';

/* ───────────────────────────── Medication Catalog ───────────────────────────── */

export interface MedicationCatalogItemRequest {
  nameFr: string;
  genericName: string;
  brandName?: string;
  atcCode?: string;
  form?: string;
  strength?: string;
  strengthUnit?: string;
  rxnormCode?: string;
  route?: string;
  category?: string;
  essentialList?: boolean;
  controlled?: boolean;
  description?: string;
  /** Policy 2026-11-20: the medicine's sensitive category. Left out, the server keeps the current one. */
  sensitivityCategory?: SensitivityCategory;
  /** True clears the sensitive category. */
  clearSensitivityCategory?: boolean;
  /**
   * Go-live D2 (V385): why the entry is created, or why what is prescribed and dispensed changes.
   * Required whenever the request proposes a version awaiting approval.
   */
  changeReason?: string;
}

/** Round 18 (QA D935): one weight band; min inclusive, max exclusive (null: no upper bound). */
export interface PediatricWeightBand {
  minWeightKg: number | null;
  maxWeightKg: number | null;
  dosePerAdministration: number | null;
  doseUnit: string;
  maxDosesPerDay: number | null;
}

export type PediatricDoseLimitStatus = 'DEFAULT_PENDING_APPROVAL' | 'APPROVED';

/**
 * Round 18 (QA D935): a catalogue item's paediatric dose limits, the
 * pharmacy's to set and approve (PUT /medication-catalog/{id}/pediatric-dosing).
 */
export interface PediatricDosingRequest {
  pediatricMaxDoseMgPerKg: number | null;
  pediatricMaxDailyMgPerKg: number | null;
  pediatricMaxSingleDoseMg: number | null;
  pediatricMaxDailyMg: number | null;
  pediatricManualReviewUnderMonths: number | null;
  pediatricWeightBands: PediatricWeightBand[];
  pediatricDoseSource: string | null;
  /** Go-live D2 (V385): why these limits are proposed; the approver reads it. */
  changeReason: string;
}

export interface MedicationCatalogItemResponse {
  id: string;
  nameFr: string;
  genericName: string;
  brandName?: string;
  atcCode?: string;
  form?: string;
  strength?: string;
  strengthUnit?: string;
  rxnormCode?: string;
  route?: string;
  category?: string;
  essentialList: boolean;
  controlled: boolean;
  /** Policy 2026-11-20: the sensitive category this medicine reveals; null for none. */
  sensitivityCategory?: SensitivityCategory | null;
  /** QA D935: mg per kg per dose, or null when the catalogue holds no ceiling. */
  pediatricMaxDoseMgPerKg?: number | null;
  /** Round 18 (QA D935): the rest of the paediatric limits, their source and approval. */
  pediatricMaxDailyMgPerKg?: number | null;
  pediatricMaxSingleDoseMg?: number | null;
  pediatricMaxDailyMg?: number | null;
  pediatricManualReviewUnderMonths?: number | null;
  pediatricWeightBands?: PediatricWeightBand[];
  pediatricDoseSource?: string | null;
  pediatricDoseStatus?: PediatricDoseLimitStatus | null;
  pediatricDoseReviewedAt?: string | null;
  /**
   * Go-live D2 (V385): the approved version in force, per circuit. Null: nobody approved the entry
   * (or its limits) as a second person yet; it works as before and is shown as provisional.
   */
  formularyApprovedVersion?: number | null;
  doseLimitApprovedVersion?: number | null;
  /** Go-live D2 (V385): the version awaiting a decision on this entry, per circuit. */
  pendingFormularyVersionId?: string | null;
  pendingDoseLimitVersionId?: string | null;
  active: boolean;
  description?: string;
  hospitalId?: string;
  hospitalName?: string;
  createdAt: string;
  updatedAt: string;
}

/* ──────────────── Medication catalogue approvals (go-live D2, V385) ──────────────── */

export type MedicationCatalogVersionKind = 'FORMULARY' | 'DOSE_LIMIT';
/** WITHDRAWAL (QA D4190): the entry taken out of the catalogue; a line of its history, never decided. */
export type MedicationCatalogChangeType = 'CREATE' | 'UPDATE' | 'RATIFY' | 'WITHDRAWAL';

/**
 * QA D4180 (r81): an entry of the catalogue, or a proposal still waiting, that looks like the one
 * being proposed. `exact` (same name, form and strength) is refused by the server.
 */
export interface MedicationCatalogSimilarEntry {
  catalogItemId?: string | null;
  versionId?: string | null;
  label: string;
  form?: string | null;
  exact: boolean;
  state: 'IN_CATALOGUE' | 'WITHDRAWN' | 'PENDING';
  platform: boolean;
}
/** RECORDED (QA D4190): an act that took effect when it was recorded (a withdrawal from the catalogue). */
export type MedicationCatalogVersionStatus =
  'PENDING_APPROVAL' | 'APPROVED' | 'REJECTED' | 'SUPERSEDED' | 'WITHDRAWN' | 'RECORDED';

/** A formulary entry as one version carries it. */
export interface FormularySnapshot {
  nameFr?: string | null;
  genericName?: string | null;
  brandName?: string | null;
  atcCode?: string | null;
  form?: string | null;
  strength?: string | null;
  strengthUnit?: string | null;
  rxnormCode?: string | null;
  tallManName?: string | null;
  route?: string | null;
  controlled?: boolean;
  active?: boolean;
  category?: string | null;
  essentialList?: boolean;
  description?: string | null;
}

/** The dose limits of an entry as one version carries them. */
export interface DoseLimitSnapshot {
  maxDoseMgPerKg?: number | null;
  maxDailyMgPerKg?: number | null;
  maxSingleDoseMg?: number | null;
  maxDailyMg?: number | null;
  manualReviewUnderMonths?: number | null;
  weightBands?: PediatricWeightBand[];
  source?: string | null;
}

/**
 * One version of a catalogue entry or of its dose limits: what is proposed, what was in force,
 * who proposed it, when and why, and who approved, refused or withdrew it.
 */
export interface MedicationCatalogVersion {
  id: string;
  hospitalId?: string | null;
  catalogItemId?: string | null;
  kind: MedicationCatalogVersionKind;
  changeType: MedicationCatalogChangeType;
  versionNumber: number;
  status: MedicationCatalogVersionStatus;
  itemLabel: string;
  formulary?: FormularySnapshot | null;
  previousFormulary?: FormularySnapshot | null;
  doseLimits?: DoseLimitSnapshot | null;
  previousDoseLimits?: DoseLimitSnapshot | null;
  changedFields?: string[];
  changeReason: string;
  proposedById?: string;
  proposedByName?: string | null;
  proposedAt: string;
  decidedById?: string | null;
  decidedByName?: string | null;
  decidedAt?: string | null;
  decisionReason?: string | null;
  /** On what authority it was decided; MEDICAL_LEAD_NO_PHARMACIST is the no-pharmacist fallback. */
  decisionBasis?:
    'PHARMACIST' | 'MEDICAL_LEAD' | 'MEDICAL_LEAD_NO_PHARMACIST' | 'PLATFORM_ADMIN' | null;
  /** Whether the signed-in user may approve it: the right role here, and not their own proposal. */
  callerMayApprove: boolean;
  callerMayReject: boolean;
  ownProposal: boolean;
  /** QA D4180: for a formulary version still waiting, the entries and proposals that look like it. */
  similarEntries?: MedicationCatalogSimilarEntry[] | null;
}

/* ───────────────────────────── Pharmacy Registry ───────────────────────────── */

export interface PharmacyRequest {
  hospitalId: string;
  name: string;
  pharmacyType?: string;
  licenseNumber?: string;
  facilityCode?: string;
  phoneNumber?: string;
  email?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  region?: string;
  postalCode?: string;
  country?: string;
  fulfillmentMode?: string;
  npi?: string;
  ncpdp?: string;
  active?: boolean;
}

export interface PharmacyResponse {
  id: string;
  hospitalId: string;
  name: string;
  pharmacyType?: string;
  licenseNumber?: string;
  facilityCode?: string;
  phoneNumber?: string;
  email?: string;
  addressLine1?: string;
  addressLine2?: string;
  city?: string;
  region?: string;
  postalCode?: string;
  country?: string;
  fulfillmentMode?: string;
  npi?: string;
  ncpdp?: string;
  active: boolean;
  createdAt: string;
  updatedAt: string;
}

/* ───────────────────────────── Inventory ───────────────────────────── */

export interface InventoryItemRequest {
  pharmacyId: string;
  medicationCatalogItemId: string;
  /** Optional on update: the server refuses a value that differs from the live stock. */
  quantityOnHand?: number;
  reorderThreshold: number;
  reorderQuantity: number;
  unit?: string;
  /** QA D264: selling price of one unit, whole XOF. */
  unitPrice?: number | null;
  /** Review of #1239: why the price changes; required by the server when it does (price history). */
  priceChangeReason?: string;
  /**
   * QA D4181 (r81): true documents the item as handed over at no charge (with `freeOfChargeReason`), false
   * withdraws that (with `priceChangeReason`); absent leaves it as it is. Store manager or administrator.
   */
  freeOfCharge?: boolean;
  freeOfChargeReason?: string;
  active?: boolean;
}

export interface InventoryItemResponse {
  stockRevision?: number;
  id: string;
  pharmacyId: string;
  medicationCatalogItemId: string;
  medicationName?: string;
  medicationCode?: string;
  quantityOnHand: number;
  reorderThreshold: number;
  reorderQuantity: number;
  unit?: string;
  unitPrice?: number | null;
  /** QA D4181 (r81): documented as handed over at no charge at this pharmacy, and why. */
  freeOfCharge?: boolean;
  freeOfChargeReason?: string | null;
  active: boolean;
  /** QA D157: a controlled substance; a manual movement of it needs a witness. */
  controlled?: boolean;
  /** QA D2674: units of `unit` in one pack, once the item is counted in units; absent otherwise. */
  unitsPerPack?: number | null;
  /** QA D2674: the pack's name ("plaquette"). */
  packUnit?: string | null;
  /** QA D2674: the stock on hand as whole packs plus loose units. */
  fullPacksOnHand?: number | null;
  looseUnitsOnHand?: number | null;
  createdAt: string;
  updatedAt: string;
}

/** QA D157: a colleague who may witness a controlled-substance movement. */
export interface StockWitnessCandidate {
  userId: string;
  displayName: string;
  /** QA D2679: what the witness types to confirm; UNAVAILABLE when their account has neither factor. */
  confirmationMethod?: 'TOTP' | 'PASSWORD' | 'UNAVAILABLE';
  /** QA D2679: the hospital administrator, listed only when no pharmacy colleague can witness. */
  fallback?: boolean;
}

/** QA D2679: a witness's own confirmation, good for the caller's controlled movements until `expiresAt`. */
export interface WitnessConfirmation {
  witnessedBy: string;
  displayName?: string | null;
  method: 'TOTP' | 'PASSWORD';
  confirmedAt: string;
  expiresAt: string;
}

export interface StockLotRequest {
  inventoryItemId: string;
  lotNumber: string;
  expiryDate: string;
  initialQuantity: number;
  remainingQuantity?: number;
  supplier?: string;
  unitCost?: number;
  receivedDate?: string;
  notes?: string;
  /** QA D655: the colleague who witnessed the receipt of a controlled substance. */
  witnessedBy?: string;
  /**
   * QA D3401 (r70): true when the quantity (and the unit cost) count whole packs of an item kept
   * in units; the server converts with the item's pack size. Stock is stored in units.
   */
  inPacks?: boolean;
}

export interface StockLotResponse {
  stockRevision?: number;
  id: string;
  inventoryItemId: string;
  lotNumber: string;
  expiryDate: string;
  initialQuantity: number;
  remainingQuantity: number;
  supplier?: string;
  unitCost?: number;
  receivedDate?: string;
  receivedBy?: string;
  notes?: string;
  /**
   * Tier 2 item 34 — scannable identifier printed on the lot label
   * ("LOT-" + 12 hex). Null on lots received before V138; printing a label
   * mints one.
   */
  barcodeValue?: string;
  /**
   * QA D1163: the unit (dosage form) of the stock item the lot belongs to,
   * when the server sends it; a lot of another form than the order is not offered.
   */
  unit?: string | null;
  /**
   * QA D3940: the lot's product (reader's language, with its strength), its
   * strength and form — on every read of a lot since QA D4184 (r81): a lot is
   * never listed without its medicine. `productMatch` (how it relates to the
   * order) is the dispensable-lots read's only. A SUBSTITUTE leaves only as a
   * recorded substitution; the server never lists another strength or molecule.
   */
  productName?: string | null;
  strength?: string | null;
  form?: string | null;
  productMatch?: 'PRESCRIBED' | 'EQUIVALENT' | 'SUBSTITUTE' | null;
  createdAt: string;
  updatedAt: string;
}

export interface StockTransactionRequest {
  inventoryItemId: string;
  stockLotId?: string;
  transactionType: string;
  quantity: number;
  reason?: string;
  /** Must be a UUID when present (e.g. destination pharmacy on TRANSFER). */
  referenceId?: string;
  performedBy?: string;
  /** QA D157: the colleague who witnessed a controlled-substance movement. */
  witnessedBy?: string;
  /**
   * QA D3415 (r70): the state of a RETURN's units. Only INTACT goes back on sale; the others, and
   * a controlled drug, are quarantined.
   */
  stockCondition?: ReturnedStockCondition;
  // FU-2: first-class RECEIPT fields (all optional)
  lotNumber?: string;
  supplier?: string;
  poReference?: string;
  /** ISO date (YYYY-MM-DD). */
  expiryDate?: string;
  unitCost?: number;
  /**
   * QA D964: why the chosen lot is taken although a shorter-dated usable lot
   * of the same item could serve this movement. Required by the server only
   * then; recorded with the movement.
   */
  fefoOverrideReason?: string;
}

/** QA D967: one movement of the controlled-drug register, with the drug's balance after it. */
export interface ControlledRegisterEntry {
  transactionId: string;
  occurredAt?: string;
  movementType: string;
  /** Signed: in positive, out negative. */
  quantity: number;
  lotNumber?: string;
  balanceAfter: number;
  patientName?: string;
  prescriberName?: string;
  recordedBy?: string;
  witnessedBy?: string;
  /** Already in the reader's language. */
  reason?: string;
  /** QA D1084: a return booked against a fill: its lot, and the minute it was given (server time). */
  reasonFillLot?: string | null;
  reasonFillAt?: string | null;
  /** QA D3415 (r70): what the row did to the quarantine (signed), and the quarantine after it. */
  quarantineQuantity?: number | null;
  quarantineAfter?: number | null;
}

/** QA D967: one controlled drug's page of the register. */
export interface ControlledRegisterDrug {
  inventoryItemId: string;
  medicationName?: string;
  unit?: string;
  openingBalance: number;
  closingBalance: number;
  quantityOnHand?: number;
  entries: ControlledRegisterEntry[];
  /** QA D3415 (r70): units set aside at the end of the period, waiting to be destroyed. */
  inQuarantine?: number | null;
}

/** QA D967: the controlled-drug (stupéfiants) register of a pharmacy over a period. */
export interface ControlledRegister {
  pharmacyId: string;
  pharmacyName?: string;
  from: string;
  to: string;
  truncated: boolean;
  drugs: ControlledRegisterDrug[];
}

/**
 * QA D932: a fill a return can be booked against (completed or partial, of
 * the chosen item and lot, with stock still out).
 */
export interface ReturnableTransfer {
  transferId: string;
  stockLotId?: string | null;
  destinationName: string;
  transferredAt: string;
  returnableQuantity: number;
}

export interface ReturnableDispense {
  dispenseId: string;
  prescriptionId?: string;
  patientName?: string;
  medicationName?: string;
  dispensedAt?: string;
  quantityDispensed: number;
  returnableQuantity: number;
  unit?: string;
  lotNumber?: string;
  /** QA D3409 (r70): the fill's lot, taken by the form when the fill is chosen first. */
  stockLotId?: string | null;
}

export interface StockTransactionResponse {
  id: string;
  inventoryItemId: string;
  stockLotId?: string;
  transactionType: string;
  quantity: number;
  reason?: string;
  referenceId?: string;
  performedBy?: string;
  /** QA D158: who did it, as a name. */
  performedByName?: string;
  witnessedBy?: string;
  witnessedByName?: string;
  /** QA D158: the lot's number, never its id. */
  stockLotNumber?: string;
  createdAt: string;
  /**
   * QA D4184 (r81): what moved (the product in the reader's language with its strength, its
   * dosage form's code), in which unit (absent on movements older than the unit snapshot), and
   * for a transfer the other store.
   */
  medicationName?: string | null;
  strength?: string | null;
  form?: string | null;
  unit?: string | null;
  counterpartPharmacyName?: string | null;
  // FU-2: first-class RECEIPT fields
  lotNumber?: string;
  supplier?: string;
  poReference?: string;
  expiryDate?: string;
  unitCost?: number;
}

/* ───────────────────────────── Page wrapper ───────────────────────────── */

export interface Page<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  size: number;
  number: number;
}

export interface ApiResponse<T> {
  data: T;
  message?: string;
  status?: string;
}

/* ───────────────────────────── Dispensing ───────────────────────────── */

export interface DispenseRequest {
  prescriptionId: string;
  patientId: string;
  pharmacyId: string;
  dispensedBy: string;
  verifiedBy?: string;
  medicationCatalogItemId?: string;
  stockLotId?: string;
  medicationName: string;
  quantityRequested: number;
  quantityDispensed: number;
  unit?: string;
  substitution?: boolean;
  substitutionReason?: string;
  notes?: string;
  /**
   * QA D964: why the chosen lot is taken although a shorter-dated usable lot
   * of the same item could serve this movement. Required by the server only
   * then; recorded with the movement.
   */
  fefoOverrideReason?: string;
  /**
   * QA D1102 class: the pharmacist's reason for dispensing past a serious
   * interaction alert. Sent only once the server refused the fill with the
   * alerts (`cdsAdvisories`), which the form then shows beside this field.
   */
  cdsOverrideReason?: string;
  /**
   * Policy rule 6 (review of #1239, QA D2675): a one-step fill leaves the counter before it can be paid.
   * When it has a price, it names the deferral somebody else approved for the prescription…
   */
  billingDeferralId?: string;
  /** …or carries the reason of a logged urgency override, which finance reviews afterwards. */
  billingUrgencyReason?: string;
  /**
   * Roadmap row 4 / T-68 — optional client-supplied idempotency key the
   * offline-dispense queue mints when the user clicks Dispense. Allows the
   * same logical dispense to be replayed after connectivity returns without
   * a second stock decrement / audit / ready-for-pickup SMS. Backend
   * contract: see DispenseRequestDTO.idempotencyKey + V94 migration.
   */
  idempotencyKey?: string;

  /** QA D654: the colleague who witnessed a controlled substance leaving the shelf. */
  witnessedBy?: string;

  /**
   * Tier 2 item 34 — raw value scanned from the patient wristband (the bare
   * patient UUID, per #475's printed wristband).
   *
   * Optional: most sites here dispense against a paper slip with no
   * scanner. When supplied the server verifies it and refuses a mismatch.
   */
  patientScanValue?: string;

  /**
   * Tier 2 item 34 — raw value scanned from the stock lot label
   * ("LOT-" + 12 hex, minted server-side).
   *
   * Note that the checks it strengthens — that the lot is the prescribed
   * drug and is in date — run server-side whether or not anybody scans.
   */
  productScanValue?: string;
}

export type DispenseVerificationStatus = 'NOT_VERIFIED' | 'VERIFIED' | 'OVERRIDDEN';

/** Which dispense-time check was overridden. Mirrors the backend enum. */
export type DispenseCheck = 'PATIENT' | 'DRUG' | 'EXPIRY';

/**
 * Mirrors `DispenseResponseDTO` field for field. Six fields used to be
 * declared here that `DispenseMapper` never sent (patientName, pharmacyName,
 * dispensedById, dispensedByName, verifiedById, verifiedByName) while the
 * wire carried `dispensedBy` / `verifiedBy`, so the dispensing history's
 * "Dispensed by" column was blank on every row. The backend now sends
 * `dispensedByName`; the ids are named as the wire names them; the rest,
 * which nothing rendered, are gone rather than left to look available.
 */
export interface DispenseResponse {
  id: string;
  prescriptionId: string;
  patientId: string;
  /** The patient as a person reads them (QA D102); on every dispense read since QA D1058. */
  patientName?: string;
  /** QA D4185 (r81): the record number at the dispensing hospital, and the date of birth. */
  patientMrn?: string | null;
  patientDateOfBirth?: string | null;
  /** The pharmacy name; GET /pharmacy/dispense/{id} only (QA D102). */
  pharmacyName?: string;
  pharmacyId: string;
  stockLotId?: string;
  /** The dispensing user's id. */
  dispensedBy: string;
  /** Their name; absent when none is on file. */
  dispensedByName?: string;
  /** The verifying user's id, when a second person verified. */
  verifiedBy?: string;
  medicationCatalogItemId?: string;
  medicationName: string;
  quantityRequested: number;
  quantityDispensed: number;
  /**
   * QA D264: what the dispense costs; null when the pharmacy has not priced the medication. Sent by
   * GET /pharmacy/dispense/{id} and, since QA D4186, on the unpaid rows of the pharmacy's list.
   */
  chargeAmount?: number | null;
  /**
   * QA D3408 (r70): the insurer share of this fill (a live claim, or a third-party cover approved by the
   * patient's coverage) and the patient share the checkout takes; absent when the insurer pays nothing.
   */
  insurerShareAmount?: number | null;
  patientShareAmount?: number | null;
  /**
   * QA D843: a payment is already POSTED against this dispense. Sent by
   * GET /pharmacy/dispense/{id} and the pharmacy list the checkout reads;
   * absent elsewhere.
   */
  paid?: boolean | null;
  unit?: string;
  substitution: boolean;
  substitutionReason?: string;
  status: string;
  notes?: string;
  /** When the fill was handed over; absent while it is PENDING (prepared, G15). */
  dispensedAt?: string | null;
  /**
   * NOT_VERIFIED is the paper-fallback path and a legitimate outcome, not a
   * failure — do not render it as an error.
   */
  verificationStatus?: DispenseVerificationStatus;
  scanVerifiedAt?: string;
  verificationOverrides?: DispenseCheck[];
  verificationOverrideReason?: string;
  /** G15: who prepared the fill (absent for a one-step fill); kept after hand-over. */
  preparedBy?: string;
  preparedByName?: string;
  /** G15: when it was marked ready for collection. */
  readyAt?: string;
  /** G15: why a preparation was cancelled or voided. */
  cancelReason?: string;
  createdAt: string;
  updatedAt: string;
}

/**
 * G15: the reasons a pharmacist may give when cancelling a preparation. The
 * backend's two PRESCRIPTION_* values are its own (a withdrawn or changed
 * order) and are refused from here.
 */
export const READY_CANCEL_REASONS = [
  'STOCK_UNAVAILABLE',
  'PATIENT_DECLINED',
  'NOT_COLLECTED',
  'OTHER',
] as const;
export type ReadyCancelReason = (typeof READY_CANCEL_REASONS)[number];

/**
 * QA D3403 / D3415 (r70): the state of medicine coming back to the pharmacy. Only INTACT goes back
 * on sale; the others (and a controlled drug a patient brings back) are quarantined.
 */
export const RETURNED_STOCK_CONDITIONS = ['INTACT', 'DAMAGED', 'EXPIRED', 'CONTAMINATED'] as const;
/**
 * Review of #1285: a cancelled preparation may also be « manquant » (nothing comes back: a
 * recorded loss); a return never is.
 */
export const CANCEL_STOCK_CONDITIONS = [...RETURNED_STOCK_CONDITIONS, 'MISSING'] as const;
export type ReturnedStockCondition = (typeof CANCEL_STOCK_CONDITIONS)[number];

/** QA D3415: units set aside, not for sale, waiting to be destroyed. */
export interface QuarantineLine {
  inventoryItemId: string;
  medicationName: string | null;
  unit: string | null;
  controlled: boolean;
  stockLotId: string | null;
  lotNumber: string | null;
  heldQuantity: number;
}

/** QA D3415: the witnessed destruction of quarantined units. */
export interface QuarantineDestructionRequest {
  inventoryItemId: string;
  stockLotId?: string | null;
  quantity: number;
  reason: string;
  witnessedBy?: string;
}

/** G15: GET /pharmacy/dispense/settings (G13 adds the work-queue claim switch and TTL). */
export interface DispenseSettings {
  readyForCollectionEnabled: boolean;
  /** G13: whether work-queue claims are on; absent from an older backend. */
  queueClaimEnabled?: boolean;
  /** G13: how long a claim lasts without being renewed, in minutes. */
  queueClaimTtlMinutes?: number;
}

/** G13: which work-queue rows to list, by claim. */
export type QueueClaimFilter = 'ALL' | 'MINE' | 'UNCLAIMED';

/**
 * G13: an active work-queue claim ("being prepared by"). Advisory: it never
 * blocks a dispense or any other action. Times are the server's zone-less
 * local timestamps.
 */
export interface WorkQueueClaim {
  prescriptionId: string;
  claimedByUserId: string;
  claimedByName?: string;
  claimedAt: string;
  expiresAt?: string;
  /** True only for the caller's own claim. */
  mine: boolean;
  /** Claim and take-over answers only: true when the caller already held it. */
  renewed?: boolean;
}

/** G15: the fill prepared for a work-queue row and waiting for collection. */
export interface WorkQueueReadyForCollection {
  dispenseId: string;
  readyAt?: string;
  preparedByName?: string;
  quantity?: number;
  unit?: string;
  /** QA D3942: the product prepared, which a substitution makes another than the order's. */
  medicationName?: string;
  /** QA D3942: the prepared product replaces the prescribed one. */
  substitution?: boolean;
  substitutionReason?: string;
  /** When the one reminder SMS went out; absent before. */
  reminderSentAt?: string;
  /** Review of #1285: a controlled drug; set aside or lost on cancel, it needs a witness. */
  controlled?: boolean;
}

/** Work-queue prescription — minimal projection returned by GET /pharmacy/dispense/work-queue. */
export interface WorkQueuePrescription {
  id: string;
  medicationName: string;
  dosage?: string;
  frequency?: string;
  quantity?: number;
  /**
   * QA D1001: what may still be dispensed on this order (the server's own
   * ceiling: every fill owed minus the fills not cancelled). Absent when the
   * order carries no quantity.
   */
  remainingQuantity?: number;
  quantityUnit?: string;
  status: string;
  createdAt?: string;
  patient?: {
    id: string;
    firstName?: string;
    middleName?: string;
    lastName?: string;
    /** QA D4185 (r81): the record number at the order's hospital, and the date of birth. */
    mrn?: string | null;
    dateOfBirth?: string | null;
  };
  staff?: {
    id: string;
    user?: {
      id: string;
      firstName?: string;
      lastName?: string;
    };
  };
  /** Absent on a first fill with no refill allowance and no request history. */
  refill?: WorkQueueRefillContext;
  /**
   * G15: the fill prepared for this order and waiting at the counter; absent
   * when there is none. While it is set the row is handed over or its
   * preparation cancelled, never dispensed or routed.
   */
  readyForCollection?: WorkQueueReadyForCollection;
  /** G13: who is preparing this order; absent when nobody holds an active claim. */
  claim?: WorkQueueClaim;
  /**
   * True when the row is not a plain fill and the pharmacist should look
   * before dispensing; {@link attentionReason} says which. Absent (rather
   * than false) on payloads produced before the backend added the flag.
   */
  needsAttention?: boolean;
  /**
   * PENDING_STOCK, PARTNER_REJECTED, PARTNER_ACCEPTED,
   * BACK_ORDER_OUTSTANDING, CLARIFICATION_RESOLVED or (G15)
   * READY_UNCOLLECTED; absent when nothing
   * needs attention. Only CLARIFICATION_RESOLVED is acted on here (gap G5) —
   * the rest belong to the back-order and partner-routing cues.
   */
  attentionReason?: string;
  /**
   * When the prescriber answered the pharmacist's question, if the pharmacy
   * has not acted on that answer yet; absent otherwise.
   *
   * <p>A SECOND fact about the row, not a competing reason:
   * `attentionReason` reports one reason by precedence and resolving a
   * clarification restores the status the question was asked from, so an
   * answer on a PENDING_STOCK or PARTNER_REJECTED order is masked by that
   * status. This is the only reliable "the prescriber answered" cue.
   *
   * <p>Carries no clinical text — the projection is served to roles that
   * `GET /prescriptions/{id}` refuses. The words are fetched in the dialog.
   */
  clarificationResolvedAt?: string;
  /**
   * QA D1101: the prescriber passed an allergy or a critical decision-support
   * alert with a reason. A cue only; the reason is read on the prescription.
   */
  alertOverridden?: boolean;
  /**
   * QA D1555: the order's allergy cards against the patient's CURRENT allergies (an allergy recorded
   * after the signature included); the stop's `exceptionKey` is the token a fill sends back.
   */
  allergyAlerts?: CdsCard[];
  /** QA D1584: the allergy stop's review state (awaiting, CONTINUE with the plan, STOP). */
  allergyReview?: AllergyReviewState | null;
  /** Existing when a recorded death was disputed: a prescriber reviews it first. */
  heldForReview?: boolean;
}

/**
 * What the prescriber last decided about this patient's refill request, plus the
 * remaining allowance. A denial or hold matters as much as an approval at the
 * counter: a patient can arrive asking for a refill their doctor refused.
 */
export interface WorkQueueRefillContext {
  allowed?: number;
  remaining?: number;
  used?: number;
  lastStatus?: RefillDecisionStatus;
  lastProviderNotes?: string;
  lastDecidedAt?: string;
  /** An approved request not yet dispensed — the patient is here to collect. */
  awaitingRefillPickup?: boolean;
}

export type RefillDecisionStatus =
  'REQUESTED' | 'PAUSED' | 'APPROVED' | 'DENIED' | 'DISPENSED' | 'CANCELLED';

/* ───────────────────────────── Stock-Out Routing ───────────────────────────── */

export interface StockCheckResult {
  medicationName: string;
  /** Null when `quantityOnHand` is an aggregate across all hospital dispensaries. */
  pharmacyName: string | null;
  /** Null when `quantityOnHand` is an aggregate across all hospital dispensaries. */
  pharmacyId: string | null;
  quantityOnHand: number;
  /** QA D913: what is still owed on the order; absent from a server that predates it. */
  quantityRequired?: number | null;
  sufficient: boolean;
  partnerPharmacies: PartnerOption[];
  /** QA D966: the order's status (PRINTED_FOR_PATIENT once printed). */
  prescriptionStatus?: string;
  /** QA D966: whether a routing action can still be taken; the buttons follow it. */
  routable?: boolean;
  /**
   * QA D4193 (r81): what each of the hospital's own stores holds; `quantityOnHand` is their
   * total. Absent from a server that predates it.
   */
  stores?: StoreStock[] | null;
}

/** One of the hospital's own stores and what it holds of the prescribed medicine. */
export interface StoreStock {
  pharmacyId: string;
  pharmacyName: string;
  quantityOnHand: number;
  unit?: string | null;
}

export interface PartnerOption {
  pharmacyId: string;
  pharmacyName: string;
  pharmacyType: string;
  city: string;
  phoneNumber: string;
  hasOnFormulary: boolean;
}

export interface RoutingDecisionRequest {
  prescriptionId: string;
  routingType: string;
  targetPharmacyId?: string;
  reason?: string;
  estimatedRestockDate?: string;
}

export interface RoutingDecisionResponse {
  id: string;
  prescriptionId: string;
  routingType: string;
  targetPharmacyId?: string;
  targetPharmacyName?: string;
  decidedByUserId: string;
  patientId: string;
  /** Why the decision was taken, as it was typed — free text only. */
  reason?: string;
  /**
   * The decision was cancelled because the partner never delivered. A flag,
   * not a stored sentence: the sentence used to be composed in English on the
   * server and rendered verbatim to French and Spanish prescribers.
   */
  partnerNoShow?: boolean;
  /** The pharmacist's own words about the no-show; absent when they typed none. */
  noShowReason?: string;
  estimatedRestockDate?: string;
  /**
   * The quantity this routing is for — the REMAINDER on a partially filled
   * order, the full amount otherwise (`FillAccounting.remaining`). Null when
   * the prescription carries no quantity at all, which means "unknown": do not
   * render a zero in its place.
   */
  remainingQuantity?: number;
  status: string;
  decidedAt: string;
  createdAt: string;
  updatedAt: string;
}

/* ───────────────────────────── Pharmacy Payments (T-41) ───────────────────────────── */

export type PharmacyPaymentMethod = 'CASH' | 'MOBILE_MONEY' | 'INSURANCE';

export interface PharmacyPaymentRequest {
  dispenseId: string;
  patientId: string;
  hospitalId: string;
  paymentMethod: PharmacyPaymentMethod;
  amount: number;
  currency?: string;
  referenceNumber?: string;
  /** Billing plan PR 9: required by the server when the method is MOBILE_MONEY. */
  mobileMoneyOperator?: MobileMoneyOperator;
  receivedBy: string;
  notes?: string;
  /** Billing plan PR 11 (QA D2430): one key per checkout; a retry with it records nothing twice. */
  clientRequestId?: string;
}

export interface PharmacyPaymentResponse {
  id: string;
  /** QA D1032: the readable receipt number (CODE-PH-YYYY-000001) the server assigns. */
  receiptNumber?: string | null;
  dispenseId: string;
  patientId: string;
  hospitalId: string;
  paymentMethod: PharmacyPaymentMethod;
  amount: number;
  currency: string;
  referenceNumber?: string;
  /** Billing plan PR 9: null when not stated (older rows) or not Mobile Money. */
  mobileMoneyOperator?: MobileMoneyOperator | null;
  receivedBy: string;
  notes?: string;
  /** QA D404: POSTED, or VOIDED once the dispense it paid for was cancelled. */
  status?: 'POSTED' | 'VOIDED';
  voidedAt?: string | null;
  voidReason?: string | null;
  createdAt: string;
  updatedAt: string;
  /** QA D2310: the receipt's lines, read on the server; on the checkout's answer and a single payment read. */
  receipt?: PharmacyReceiptDetails | null;
}

/** QA D2310: quantity, unit price, record number, cashier and pharmacy of a pharmacy receipt. */
export interface PharmacyReceiptDetails {
  medicationName?: string | null;
  quantity?: number | null;
  unit?: string | null;
  /** QA D4186: the prices kept on the payment when it was taken; absent on a payment older than that. */
  unitPrice?: number | null;
  lineTotal?: number | null;
  insurerShare?: number | null;
  patientMrn?: string | null;
  /** QA D4186: the patient, read from the payment, so a receipt reopened later still names them. */
  patientName?: string | null;
  patientDateOfBirth?: string | null;
  cashierName?: string | null;
  pharmacyName?: string | null;
  pharmacyAddress?: string | null;
  pharmacyPhone?: string | null;
  /** QA D4186: true on a later read of the payment (history, reprint): the receipt is a « Duplicata ». */
  duplicate?: boolean;
}

/* ───────────────────────────── Pharmacy Claims (T-47/T-48/T-49) ───────────────────────────── */

export type PharmacyClaimStatus = 'DRAFT' | 'SUBMITTED' | 'ACCEPTED' | 'REJECTED' | 'PAID';

export interface PharmacyClaimRequest {
  dispenseId: string;
  patientId: string;
  hospitalId: string;
  coverageReference?: string;
  claimStatus?: PharmacyClaimStatus;
  /** QA D3408 (r70): optional; the server takes the insurer share by the coverage rule. */
  amount?: number;
  currency?: string;
  notes?: string;
  rejectionReason?: string;
}

export interface PharmacyClaimResponse {
  id: string;
  dispenseId: string;
  patientId: string;
  /** QA D992 class: shown instead of a slice of the patient id. */
  patientName?: string | null;
  /** QA D4185 (r81): the record number at the claim's hospital, and the date of birth. */
  patientMrn?: string | null;
  patientDateOfBirth?: string | null;
  hospitalId: string;
  coverageReference?: string;
  claimStatus: PharmacyClaimStatus;
  amount: number;
  currency: string;
  submittedAt?: string;
  submittedBy?: string;
  rejectionReason?: string;
  notes?: string;
  createdAt: string;
  updatedAt: string;
  /** QA D2925 (r66): the reader may record the payer's answer (a money desk or a hospital admin). */
  canRecordPayerDecision?: boolean;
  /** QA D2925 (r66): accepted, but no money desk stands behind it yet: it covers no fill. */
  acceptanceUnconfirmed?: boolean;
}
/* ───────────────────────────── Service ───────────────────────────── */

@Injectable({ providedIn: 'root' })
export class PharmacyService {
  private readonly http = inject(HttpClient);

  // ── Medication Catalog ──

  listMedications(page = 0, size = 20): Observable<Page<MedicationCatalogItemResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<MedicationCatalogItemResponse>>('/medication-catalog', { params });
  }

  searchMedications(
    query: string,
    page = 0,
    size = 20,
  ): Observable<Page<MedicationCatalogItemResponse>> {
    const params = new HttpParams().set('q', query).set('page', page).set('size', size);
    return this.http.get<Page<MedicationCatalogItemResponse>>('/medication-catalog/search', {
      params,
    });
  }

  getMedication(id: string): Observable<MedicationCatalogItemResponse> {
    return this.http.get<MedicationCatalogItemResponse>(`/medication-catalog/${id}`);
  }

  /**
   * Go-live D2 (V385): a new entry is proposed, not created. The answer is the version awaiting
   * approval; the entry is in the catalogue once a pharmacist other than its author approved it.
   */
  createMedication(req: MedicationCatalogItemRequest): Observable<MedicationCatalogVersion> {
    return this.http.post<MedicationCatalogVersion>('/medication-catalog', req);
  }

  /** The approval queue (or every version, with no status) of the acting hospital. */
  listCatalogVersions(
    status: MedicationCatalogVersionStatus | null,
    page = 0,
    size = 50,
  ): Observable<Page<MedicationCatalogVersion>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (status) params = params.set('status', status);
    return this.http.get<Page<MedicationCatalogVersion>>('/medication-catalog/versions', {
      params,
    });
  }

  /** Every version of one entry, the latest first. */
  catalogVersionHistory(itemId: string): Observable<MedicationCatalogVersion[]> {
    return this.http.get<MedicationCatalogVersion[]>(`/medication-catalog/${itemId}/versions`);
  }

  approveCatalogVersion(versionId: string): Observable<MedicationCatalogVersion> {
    return this.http.post<MedicationCatalogVersion>(
      `/medication-catalog/versions/${versionId}/approve`,
      {},
    );
  }

  rejectCatalogVersion(versionId: string, reason: string): Observable<MedicationCatalogVersion> {
    return this.http.post<MedicationCatalogVersion>(
      `/medication-catalog/versions/${versionId}/reject`,
      { reason },
    );
  }

  withdrawCatalogVersion(versionId: string): Observable<MedicationCatalogVersion> {
    return this.http.post<MedicationCatalogVersion>(
      `/medication-catalog/versions/${versionId}/withdraw`,
      {},
    );
  }

  /** Submits an entry nobody approved yet, as it stands, for its approval. */
  requestCatalogApproval(
    itemId: string,
    reason: string,
  ): Observable<MedicationCatalogItemResponse> {
    return this.http.post<MedicationCatalogItemResponse>(
      `/medication-catalog/${itemId}/approval-request`,
      { reason },
    );
  }

  updateMedication(
    id: string,
    req: MedicationCatalogItemRequest,
  ): Observable<MedicationCatalogItemResponse> {
    return this.http.put<MedicationCatalogItemResponse>(`/medication-catalog/${id}`, req);
  }

  /** QA D4190 (r81): a withdrawal says why; the reason is kept on the entry's history. */
  deleteMedication(id: string, reason: string): Observable<void> {
    const params = new HttpParams().set('reason', reason);
    return this.http.delete<void>(`/medication-catalog/${id}`, { params });
  }

  /** QA D4180 (r81): what already looks like the entry about to be proposed. */
  similarMedications(
    entry: { nameFr: string; form?: string; strength?: string; strengthUnit?: string },
    excludeId?: string | null,
  ): Observable<MedicationCatalogSimilarEntry[]> {
    let params = new HttpParams().set('nameFr', entry.nameFr);
    if (entry.form) params = params.set('form', entry.form);
    if (entry.strength) params = params.set('strength', entry.strength);
    if (entry.strengthUnit) params = params.set('strengthUnit', entry.strengthUnit);
    if (excludeId) params = params.set('excludeId', excludeId);
    return this.http.get<MedicationCatalogSimilarEntry[]>('/medication-catalog/similar', {
      params,
    });
  }

  /**
   * Round 18 (QA D935): the pharmacy sets an item's paediatric dose limits. Go-live D2 (V385):
   * they are proposed, and in force once another pharmacist or the médecin-chef approved them.
   */
  updatePediatricDosing(
    id: string,
    req: PediatricDosingRequest,
  ): Observable<MedicationCatalogItemResponse> {
    return this.http.put<MedicationCatalogItemResponse>(
      `/medication-catalog/${id}/pediatric-dosing`,
      req,
    );
  }

  // ── Pharmacy Registry ──

  /**
   * The whole registry: the hospital's own pharmacies AND the partner and community pharmacies an order
   * can be sent to. Only the registry page lists it. A store picker calls `listStores`
   * (QA D4182, `scripts/lib/r81-pharmacy-dispensing.test.mjs`).
   */
  listPharmacies(page = 0, size = 20): Observable<Page<PharmacyResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<PharmacyResponse>>('/pharmacy-registry', { params });
  }

  /**
   * QA D4182 (r81): the hospital's own stores, the list behind every store picker (dispensing, inventory,
   * goods receipt, adjustments and transfers, controlled register, checkout, counter sale). A partner
   * pharmacy is another business: it holds no hospital stock and has no till of the hospital.
   */
  listStores(page = 0, size = 20): Observable<Page<PharmacyResponse>> {
    const params = new HttpParams().set('page', page).set('size', size).set('storesOnly', true);
    return this.http.get<Page<PharmacyResponse>>('/pharmacy-registry', { params });
  }

  searchPharmacies(query: string, page = 0, size = 20): Observable<Page<PharmacyResponse>> {
    const params = new HttpParams().set('q', query).set('page', page).set('size', size);
    return this.http.get<Page<PharmacyResponse>>('/pharmacy-registry/search', { params });
  }

  getPharmacy(id: string): Observable<PharmacyResponse> {
    return this.http.get<PharmacyResponse>(`/pharmacy-registry/${id}`);
  }

  createPharmacy(req: PharmacyRequest): Observable<PharmacyResponse> {
    return this.http.post<PharmacyResponse>('/pharmacy-registry', req);
  }

  updatePharmacy(id: string, req: PharmacyRequest): Observable<PharmacyResponse> {
    return this.http.put<PharmacyResponse>(`/pharmacy-registry/${id}`, req);
  }

  deletePharmacy(id: string): Observable<void> {
    return this.http.delete<void>(`/pharmacy-registry/${id}`);
  }

  // ── Inventory Items ──

  listInventoryItems(page = 0, size = 20): Observable<ApiResponse<Page<InventoryItemResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<InventoryItemResponse>>>('/pharmacy/inventory/items', {
      params,
    });
  }

  listInventoryByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<InventoryItemResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<InventoryItemResponse>>>(
      `/pharmacy/inventory/items/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  getInventoryItem(id: string): Observable<ApiResponse<InventoryItemResponse>> {
    return this.http.get<ApiResponse<InventoryItemResponse>>(`/pharmacy/inventory/items/${id}`);
  }

  createInventoryItem(req: InventoryItemRequest): Observable<ApiResponse<InventoryItemResponse>> {
    return this.http.post<ApiResponse<InventoryItemResponse>>('/pharmacy/inventory/items', req);
  }

  updateInventoryItem(
    id: string,
    req: InventoryItemRequest,
  ): Observable<ApiResponse<InventoryItemResponse>> {
    return this.http.put<ApiResponse<InventoryItemResponse>>(
      `/pharmacy/inventory/items/${id}`,
      req,
    );
  }

  deleteInventoryItem(id: string): Observable<ApiResponse<string>> {
    return this.http.delete<ApiResponse<string>>(`/pharmacy/inventory/items/${id}`);
  }

  // ── Stock Lots ──

  receiveStock(req: StockLotRequest): Observable<ApiResponse<StockLotResponse>> {
    return this.http.post<ApiResponse<StockLotResponse>>('/pharmacy/inventory/lots', req);
  }

  getStockLot(id: string): Observable<ApiResponse<StockLotResponse>> {
    return this.http.get<ApiResponse<StockLotResponse>>(`/pharmacy/inventory/lots/${id}`);
  }

  listLotsByInventoryItem(
    inventoryItemId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockLotResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockLotResponse>>>(
      `/pharmacy/inventory/lots/item/${inventoryItemId}`,
      { params },
    );
  }

  /** QA D241: the pharmacy's lots of the prescribed drug, with stock left. */
  listDispensableLots(
    prescriptionId: string,
    pharmacyId: string,
  ): Observable<ApiResponse<StockLotResponse[]>> {
    const params = new HttpParams().set('pharmacyId', pharmacyId);
    return this.http.get<ApiResponse<StockLotResponse[]>>(
      `/pharmacy/dispense/prescription/${prescriptionId}/dispensable-lots`,
      { params },
    );
  }

  /**
   * Every lot of the pharmacy, one page. QA D1233: `sort` (Spring's
   * `property,direction`) lets the goods receipt ask for the newest received
   * first across ALL items, instead of reading the lots of the first five.
   */
  listLotsByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
    sort: readonly string[] = [],
  ): Observable<ApiResponse<Page<StockLotResponse>>> {
    let params = new HttpParams().set('page', page).set('size', size);
    for (const s of sort) params = params.append('sort', s);
    return this.http.get<ApiResponse<Page<StockLotResponse>>>(
      `/pharmacy/inventory/lots/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  getExpiringSoon(pharmacyId: string, daysAhead = 90): Observable<ApiResponse<StockLotResponse[]>> {
    const params = new HttpParams().set('daysAhead', daysAhead);
    return this.http.get<ApiResponse<StockLotResponse[]>>(
      `/pharmacy/inventory/lots/expiring/${pharmacyId}`,
      { params },
    );
  }

  // ── Reorder Alerts ──

  getReorderAlerts(): Observable<ApiResponse<InventoryItemResponse[]>> {
    return this.http.get<ApiResponse<InventoryItemResponse[]>>(
      '/pharmacy/inventory/reorder-alerts',
    );
  }

  getReorderAlertsByPharmacy(pharmacyId: string): Observable<ApiResponse<InventoryItemResponse[]>> {
    return this.http.get<ApiResponse<InventoryItemResponse[]>>(
      `/pharmacy/inventory/reorder-alerts/pharmacy/${pharmacyId}`,
    );
  }

  triggerReorderAlerts(): Observable<ApiResponse<string>> {
    return this.http.post<ApiResponse<string>>('/pharmacy/inventory/reorder-alerts/trigger', {});
  }

  // ── Stock Transactions ──

  recordTransaction(
    req: StockTransactionRequest,
  ): Observable<ApiResponse<StockTransactionResponse>> {
    return this.http.post<ApiResponse<StockTransactionResponse>>(
      '/pharmacy/stock-transactions',
      req,
    );
  }

  /** QA D157: colleagues of the active hospital who may witness a controlled-substance movement. */
  listWitnessCandidates(): Observable<ApiResponse<StockWitnessCandidate[]>> {
    return this.http.get<ApiResponse<StockWitnessCandidate[]>>(
      '/pharmacy/stock-transactions/witness-candidates',
    );
  }

  /**
   * QA D2679: the witness confirms with their own password, or the code of their authenticator. The secret
   * is posted in the body of this one call, checked and dropped by the server; nothing else carries it.
   */
  confirmWitness(
    witnessedBy: string,
    secret: string,
  ): Observable<ApiResponse<WitnessConfirmation>> {
    return this.http.post<ApiResponse<WitnessConfirmation>>(
      '/pharmacy/stock-transactions/witness-confirmations',
      { witnessedBy, secret },
    );
  }

  /** QA D967: the controlled-drug register (dates are ISO YYYY-MM-DD). */
  getControlledRegister(
    pharmacyId: string,
    from: string,
    to: string,
    inventoryItemId?: string,
  ): Observable<ApiResponse<ControlledRegister>> {
    return this.http.get<ApiResponse<ControlledRegister>>('/pharmacy/controlled-register', {
      params: this.registerParams(pharmacyId, from, to, inventoryItemId),
    });
  }

  /** QA D967: the register as CSV or PDF, in the reader's language. */
  exportControlledRegister(
    format: 'csv' | 'pdf',
    pharmacyId: string,
    from: string,
    to: string,
    inventoryItemId?: string,
  ): Observable<Blob> {
    return this.http.get(`/pharmacy/controlled-register/export.${format}`, {
      params: this.registerParams(pharmacyId, from, to, inventoryItemId),
      responseType: 'blob',
    });
  }

  private registerParams(
    pharmacyId: string,
    from: string,
    to: string,
    inventoryItemId?: string,
  ): HttpParams {
    let params = new HttpParams().set('pharmacyId', pharmacyId).set('from', from).set('to', to);
    if (inventoryItemId) params = params.set('inventoryItemId', inventoryItemId);
    return params;
  }

  listReturnableTransfers(
    inventoryItemId: string,
    stockLotId?: string,
  ): Observable<ApiResponse<ReturnableTransfer[]>> {
    let params = new HttpParams().set('inventoryItemId', inventoryItemId);
    if (stockLotId) params = params.set('stockLotId', stockLotId);
    return this.http.get<ApiResponse<ReturnableTransfer[]>>(
      '/pharmacy/stock-transactions/returnable-transfers',
      { params },
    );
  }

  /** QA D932: the fills a return of this item (and lot) can be booked against. */
  listReturnableDispenses(
    inventoryItemId: string,
    stockLotId?: string,
  ): Observable<ApiResponse<ReturnableDispense[]>> {
    let params = new HttpParams().set('inventoryItemId', inventoryItemId);
    if (stockLotId) params = params.set('stockLotId', stockLotId);
    return this.http.get<ApiResponse<ReturnableDispense[]>>(
      '/pharmacy/stock-transactions/returnable-dispenses',
      { params },
    );
  }

  getTransaction(id: string): Observable<ApiResponse<StockTransactionResponse>> {
    return this.http.get<ApiResponse<StockTransactionResponse>>(
      `/pharmacy/stock-transactions/${id}`,
    );
  }

  listTransactionsByItem(
    inventoryItemId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/item/${inventoryItemId}`,
      { params },
    );
  }

  listTransactionsByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  listTransactionsByDateRange(
    pharmacyId: string,
    from: string,
    to: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams()
      .set('from', from)
      .set('to', to)
      .set('page', page)
      .set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/pharmacy/${pharmacyId}/date-range`,
      { params },
    );
  }

  listTransactionsByType(
    pharmacyId: string,
    type: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<StockTransactionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<StockTransactionResponse>>>(
      `/pharmacy/stock-transactions/pharmacy/${pharmacyId}/type/${type}`,
      { params },
    );
  }

  // ── Dispensing ──

  /**
   * The dispensing work queue. Without `sort` the endpoint orders by
   * `createdAt` ascending (oldest first); a caller that loads a window of
   * the queue and wants the newest orders passes `['createdAt,desc', 'id,desc']`
   * (one `sort` parameter per key, the id as the tiebreaker that gives one
   * fixed order). Offset pages still shift when an order leaves the queue
   * between two reads, so a caller that widens its window re-reads it from
   * page 0 in one request instead of appending page N.
   */
  getDispenseWorkQueue(
    page = 0,
    size = 20,
    claim: QueueClaimFilter = 'ALL',
    sort?: string[],
    search?: string,
  ): Observable<ApiResponse<Page<WorkQueuePrescription>>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (claim !== 'ALL') params = params.set('claim', claim);
    for (const key of sort ?? []) {
      params = params.append('sort', key);
    }
    // QA D2319: the queue's search box (a patient's name, their record number, a medication). The typed
    // term is posted in the body, never put in the URL; the claim filter and the paging stay in the query.
    const term = search?.trim();
    if (term) {
      return this.http.post<ApiResponse<Page<WorkQueuePrescription>>>(
        '/pharmacy/dispense/work-queue/term-search',
        { search: term },
        { params },
      );
    }
    return this.http.get<ApiResponse<Page<WorkQueuePrescription>>>(
      '/pharmacy/dispense/work-queue',
      { params },
    );
  }

  // ── Work-queue claim (G13) ──

  /** Claim a queue row, or renew one's own claim; 409 when a colleague holds it. */
  claimQueueRow(prescriptionId: string): Observable<ApiResponse<WorkQueueClaim>> {
    return this.http.post<ApiResponse<WorkQueueClaim>>(
      `/pharmacy/dispense/work-queue/${prescriptionId}/claim`,
      null,
    );
  }

  /** Take a colleague's claim over; audited with the previous holder. */
  takeOverQueueRow(prescriptionId: string): Observable<ApiResponse<WorkQueueClaim>> {
    return this.http.post<ApiResponse<WorkQueueClaim>>(
      `/pharmacy/dispense/work-queue/${prescriptionId}/claim/take-over`,
      null,
    );
  }

  /** Release one's own claim; nothing to release answers the same. */
  releaseQueueRow(prescriptionId: string): Observable<ApiResponse<null>> {
    return this.http.post<ApiResponse<null>>(
      `/pharmacy/dispense/work-queue/${prescriptionId}/claim/release`,
      null,
    );
  }

  createDispense(req: DispenseRequest): Observable<ApiResponse<DispenseResponse>> {
    return this.http.post<ApiResponse<DispenseResponse>>('/pharmacy/dispense', req);
  }

  getDispense(id: string): Observable<ApiResponse<DispenseResponse>> {
    return this.http.get<ApiResponse<DispenseResponse>>(`/pharmacy/dispense/${id}`);
  }

  /**
   * `DispenseRepository.findByPrescriptionId` is a derived query with NO
   * ordering, and the controller's `@PageableDefault` sets none either, so
   * page 0 is an arbitrary subset rather than the most recent fills. Pass
   * `sort` (Spring resolves it off the request) whenever you show fewer rows
   * than the prescription has — otherwise the table can omit the newest fill
   * and present older ones as current.
   */
  listDispensesByPrescription(
    prescriptionId: string,
    page = 0,
    size = 20,
    sort?: string,
  ): Observable<ApiResponse<Page<DispenseResponse>>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (sort) params = params.set('sort', sort);
    return this.http.get<ApiResponse<Page<DispenseResponse>>>(
      `/pharmacy/dispense/prescription/${prescriptionId}`,
      { params },
    );
  }

  listDispensesByPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<DispenseResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<DispenseResponse>>>(
      `/pharmacy/dispense/patient/${patientId}`,
      { params },
    );
  }

  listDispensesByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<DispenseResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<DispenseResponse>>>(
      `/pharmacy/dispense/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  /**
   * QA D654: a controlled fill goes back on the shelf in front of a witness.
   * QA D1069: every cancel says why (it voids the payment and returns the stock).
   */
  cancelDispense(
    id: string,
    reason: string,
    witnessedBy?: string,
  ): Observable<ApiResponse<DispenseResponse>> {
    return this.http.post<ApiResponse<DispenseResponse>>(
      `/pharmacy/dispense/${id}/cancel`,
      witnessedBy ? { reason, witnessedBy } : { reason },
    );
  }

  // ── Ready for collection (G15) ──

  /** Whether "Mark ready for collection" is switched on server-side. */
  getDispenseSettings(): Observable<ApiResponse<DispenseSettings>> {
    return this.http.get<ApiResponse<DispenseSettings>>('/pharmacy/dispense/settings');
  }

  /** Prepare a fill (stock set aside) and text the patient that it is ready. */
  markReady(req: DispenseRequest): Observable<ApiResponse<DispenseResponse>> {
    return this.http.post<ApiResponse<DispenseResponse>>('/pharmacy/dispense/ready', req);
  }

  /** Hand a prepared fill over; the wristband scan is optional and checked when sent. */
  handOver(
    dispenseId: string,
    body: {
      patientScanValue?: string;
      notes?: string;
      /** Policy rule 6 (QA D2675): the deferral somebody else approved for the prescription… */
      billingDeferralId?: string;
      /** …or the reason of a logged urgency override. */
      billingUrgencyReason?: string;
    } = {},
  ): Observable<ApiResponse<DispenseResponse>> {
    return this.http.post<ApiResponse<DispenseResponse>>(
      `/pharmacy/dispense/${dispenseId}/hand-over`,
      body,
    );
  }

  /** Cancel a preparation: the stock goes back and the patient is told. */
  cancelReady(
    dispenseId: string,
    reason: ReadyCancelReason,
    stockCondition: ReturnedStockCondition,
    witnessedBy?: string,
  ): Observable<ApiResponse<DispenseResponse>> {
    return this.http.post<ApiResponse<DispenseResponse>>(
      `/pharmacy/dispense/${dispenseId}/cancel-ready`,
      { reason, stockCondition, ...(witnessedBy ? { witnessedBy } : {}) },
    );
  }

  /** QA D3415: what the pharmacy holds in quarantine, per item and lot. */
  listQuarantine(pharmacyId: string): Observable<ApiResponse<QuarantineLine[]>> {
    return this.http.get<ApiResponse<QuarantineLine[]>>('/pharmacy/stock-transactions/quarantine', {
      params: { pharmacyId },
    });
  }

  /** QA D3415: destroys quarantined units (witnessed for a controlled drug). */
  destroyQuarantined(
    req: QuarantineDestructionRequest,
  ): Observable<ApiResponse<StockTransactionResponse>> {
    return this.http.post<ApiResponse<StockTransactionResponse>>(
      '/pharmacy/stock-transactions/quarantine/destructions',
      req,
    );
  }

  // ── Stock-Out Routing ──

  checkStock(prescriptionId: string): Observable<ApiResponse<StockCheckResult>> {
    return this.http.get<ApiResponse<StockCheckResult>>(
      `/pharmacy/routing/stock-check/${prescriptionId}`,
    );
  }

  routeToPartner(req: RoutingDecisionRequest): Observable<ApiResponse<RoutingDecisionResponse>> {
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      '/pharmacy/routing/route-to-partner',
      req,
    );
  }

  /** QA D966: the printed copy the patient takes to an outside pharmacy (PDF). */
  getPrintedPrescriptionCopy(prescriptionId: string): Observable<Blob> {
    return this.http.get(`/pharmacy/routing/print-for-patient/${prescriptionId}/document`, {
      responseType: 'blob',
    });
  }

  /**
   * QA D966: the lot label as an authenticated blob. The page opened the bare
   * URL in a new tab, which skips the /api prefix and the token, so it landed
   * on the login page and printed nothing.
   */
  getStockLotLabel(stockLotId: string): Observable<Blob> {
    return this.http.get(`/pharmacy/stock-lots/${stockLotId}/label.pdf`, { responseType: 'blob' });
  }

  printForPatient(prescriptionId: string): Observable<ApiResponse<RoutingDecisionResponse>> {
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/print-for-patient/${prescriptionId}`,
      {},
    );
  }

  backOrder(
    prescriptionId: string,
    estimatedRestockDate?: string,
  ): Observable<ApiResponse<RoutingDecisionResponse>> {
    let params = new HttpParams();
    if (estimatedRestockDate) {
      params = params.set('estimatedRestockDate', estimatedRestockDate);
    }
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/back-order/${prescriptionId}`,
      {},
      { params },
    );
  }

  partnerRespond(
    routingDecisionId: string,
    accepted: boolean,
  ): Observable<ApiResponse<RoutingDecisionResponse>> {
    const params = new HttpParams().set('accepted', accepted);
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/partner-respond/${routingDecisionId}`,
      {},
      { params },
    );
  }

  confirmPartnerDispense(
    routingDecisionId: string,
  ): Observable<ApiResponse<RoutingDecisionResponse>> {
    return this.http.post<ApiResponse<RoutingDecisionResponse>>(
      `/pharmacy/routing/partner-dispense-confirm/${routingDecisionId}`,
      {},
    );
  }

  /**
   * Same unordered-derived-query trap as `listDispensesByPrescription`: the
   * repository has a `findByPrescriptionIdOrderByDecidedAtDesc`, but the
   * paged endpoint uses the unordered one. `decidedAt,desc` is what the
   * backend itself orders these by when it needs the latest decision.
   */
  listRoutingDecisionsByPrescription(
    prescriptionId: string,
    page = 0,
    size = 20,
    sort?: string | string[],
  ): Observable<ApiResponse<Page<RoutingDecisionResponse>>> {
    let params = new HttpParams().set('page', page).set('size', size);
    // Spring takes one `sort` parameter per key, so a tiebreaker is a second
    // parameter rather than a longer string.
    for (const key of typeof sort === 'string' ? [sort] : (sort ?? [])) {
      params = params.append('sort', key);
    }
    return this.http.get<ApiResponse<Page<RoutingDecisionResponse>>>(
      `/pharmacy/routing/decisions/prescription/${prescriptionId}`,
      { params },
    );
  }

  listRoutingDecisionsByPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<RoutingDecisionResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<RoutingDecisionResponse>>>(
      `/pharmacy/routing/decisions/patient/${patientId}`,
      { params },
    );
  }

  // ── Pharmacy Payments (T-41) ──

  createPayment(req: PharmacyPaymentRequest): Observable<ApiResponse<PharmacyPaymentResponse>> {
    return this.http.post<ApiResponse<PharmacyPaymentResponse>>('/pharmacy/payments', req);
  }

  getPayment(id: string): Observable<ApiResponse<PharmacyPaymentResponse>> {
    return this.http.get<ApiResponse<PharmacyPaymentResponse>>(`/pharmacy/payments/${id}`);
  }

  /**
   * QA D4186 (r81): the payments taken for fills of one pharmacy, newest first, each with its receipt as
   * first charged: the history a receipt is reopened and reprinted from.
   */
  listPaymentsByPharmacy(
    pharmacyId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<PharmacyPaymentResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<PharmacyPaymentResponse>>>(
      `/pharmacy/payments/pharmacy/${pharmacyId}`,
      { params },
    );
  }

  listPaymentsByDispense(
    dispenseId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<PharmacyPaymentResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<PharmacyPaymentResponse>>>(
      `/pharmacy/payments/dispense/${dispenseId}`,
      { params },
    );
  }

  listPaymentsByPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<PharmacyPaymentResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<PharmacyPaymentResponse>>>(
      `/pharmacy/payments/patient/${patientId}`,
      { params },
    );
  }

  /**
   * Patient self-service: fetch the authenticated patient's own pharmacy
   * payments. Backend resolves patientId from the JWT — no id in the URL.
   */
  listMyPharmacyPayments(
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<PharmacyPaymentResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<PharmacyPaymentResponse>>>(
      '/me/patient/pharmacy/payments',
      { params },
    );
  }

  // ── Pharmacy Claims (T-47/T-48/T-49) ──

  createClaim(req: PharmacyClaimRequest): Observable<ApiResponse<PharmacyClaimResponse>> {
    return this.http.post<ApiResponse<PharmacyClaimResponse>>('/pharmacy/claims', req);
  }

  submitClaim(id: string): Observable<ApiResponse<PharmacyClaimResponse>> {
    return this.http.post<ApiResponse<PharmacyClaimResponse>>(`/pharmacy/claims/${id}/submit`, {});
  }

  acceptClaim(id: string, notes?: string): Observable<ApiResponse<PharmacyClaimResponse>> {
    return this.http.post<ApiResponse<PharmacyClaimResponse>>(`/pharmacy/claims/${id}/accept`, {
      notes: notes ?? null,
    });
  }

  rejectClaim(id: string, rejectionReason: string): Observable<ApiResponse<PharmacyClaimResponse>> {
    return this.http.post<ApiResponse<PharmacyClaimResponse>>(`/pharmacy/claims/${id}/reject`, {
      rejectionReason,
    });
  }

  payClaim(id: string, notes?: string): Observable<ApiResponse<PharmacyClaimResponse>> {
    return this.http.post<ApiResponse<PharmacyClaimResponse>>(`/pharmacy/claims/${id}/pay`, {
      notes: notes ?? null,
    });
  }

  getClaim(id: string): Observable<ApiResponse<PharmacyClaimResponse>> {
    return this.http.get<ApiResponse<PharmacyClaimResponse>>(`/pharmacy/claims/${id}`);
  }

  listClaimsByHospital(page = 0, size = 20): Observable<ApiResponse<Page<PharmacyClaimResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<PharmacyClaimResponse>>>('/pharmacy/claims', { params });
  }

  listClaimsByPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<ApiResponse<Page<PharmacyClaimResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<PharmacyClaimResponse>>>(
      `/pharmacy/claims/patient/${patientId}`,
      { params },
    );
  }

  /** Patient self-service: backend resolves patientId from the JWT. */
  listMyPharmacyClaims(page = 0, size = 20): Observable<ApiResponse<Page<PharmacyClaimResponse>>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<ApiResponse<Page<PharmacyClaimResponse>>>('/me/patient/pharmacy/claims', {
      params,
    });
  }

  exportClaimsCsv(statuses: PharmacyClaimStatus[] = []): Observable<Blob> {
    let params = new HttpParams();
    for (const s of statuses) {
      params = params.append('status', s);
    }
    return this.http.get('/pharmacy/claims/export/csv', { params, responseType: 'blob' });
  }

  exportClaimsFhir(statuses: PharmacyClaimStatus[] = []): Observable<Blob> {
    let params = new HttpParams();
    for (const s of statuses) {
      params = params.append('status', s);
    }
    return this.http.get('/pharmacy/claims/export/fhir', { params, responseType: 'blob' });
  }

  // ── P-09: MTM Reviews ──

  startMtmReview(req: MtmReviewRequest): Observable<MtmReviewResponse> {
    return this.http.post<MtmReviewResponse>('/mtm-reviews', req);
  }

  updateMtmReview(id: string, req: MtmReviewRequest): Observable<MtmReviewResponse> {
    return this.http.put<MtmReviewResponse>(`/mtm-reviews/${id}`, req);
  }

  getMtmReview(id: string): Observable<MtmReviewResponse> {
    return this.http.get<MtmReviewResponse>(`/mtm-reviews/${id}`);
  }

  listMtmReviewsByHospital(
    hospitalId: string,
    page = 0,
    size = 20,
  ): Observable<Page<MtmReviewResponse>> {
    const params = new HttpParams()
      .set('hospitalId', hospitalId)
      .set('page', page)
      .set('size', size);
    return this.http.get<Page<MtmReviewResponse>>('/mtm-reviews', { params });
  }

  listMtmReviewsByPatient(
    patientId: string,
    page = 0,
    size = 20,
  ): Observable<Page<MtmReviewResponse>> {
    const params = new HttpParams().set('page', page).set('size', size);
    return this.http.get<Page<MtmReviewResponse>>(`/mtm-reviews/by-patient/${patientId}`, {
      params,
    });
  }
}

// ── P-09: MTM Review types ──

export type MtmReviewStatus = 'DRAFT' | 'COMPLETED' | 'REFERRED';

export interface MtmReviewRequest {
  patientId: string;
  hospitalId: string;
  chronicConditionFocus?: string;
  adherenceConcern?: boolean;
  interventionSummary?: string;
  recommendedActions?: string;
  status?: MtmReviewStatus;
  followUpDate?: string;
}

export interface MtmReviewResponse {
  id: string;
  patientId: string;
  /** QA D900: the patient's name and MRN at the review's hospital, so the list never shows the UUID. */
  patientName?: string;
  patientMrn?: string;
  hospitalId: string;
  pharmacistUserId: string;
  reviewDate: string;
  chronicConditionFocus?: string;
  adherenceConcern: boolean;
  polypharmacyAlert: boolean;
  interventionSummary?: string;
  recommendedActions?: string;
  status: MtmReviewStatus;
  followUpDate?: string;
  createdAt: string;
  updatedAt: string;
}
