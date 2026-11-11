import { CdsCard } from '../cds-card/cds-card.model';

/**
 * QA D1563 / D1555 (round 43), Policy 4 (user recommendation 2026-11-10, pending clinical approval):
 * an allergy recorded after the order was signed is read again at every gate (the eMAR dose, the
 * pharmacist's verification, the fill and the hand-over, the routing to an outside pharmacy). A drug
 * the patient's current allergies stop is HELD until an authorized prescriber reviews the conflict on
 * the order: the server answers 400 on `allergyPrescriberReview` with the allergy card
 * (`cdsAdvisories`) and a sentence that says who was notified. A nurse's or a pharmacist's own reason
 * never releases it, so nothing here sends one back. Every portal surface reads the hold here and
 * shows it through `<app-allergy-hold-dialog>` (scripts/lib/allergy-acknowledgement-surfaces.test.mjs).
 */

/** The field the server names on every allergy hold. Not a request field: nothing a screen sends releases it. */
export const ALLERGY_HOLD_FIELD = 'allergyPrescriberReview';

/** The prefix of the allergy card's `exceptionKey`. */
export const ALLERGY_KEY_PREFIX = 'allergy:';

/**
 * QA D1584 / D1585: where an allergy conflict stands at the gates, read by the server on the same token
 * the gates check. `plan` and `rationale` come only to a clinical reader of the order at its own hospital.
 */
export type AllergyReviewStatus = 'AWAITING_REVIEW' | 'CONTINUE' | 'STOP';

export interface AllergyReviewState {
  state: AllergyReviewStatus;
  reviewedByName?: string | null;
  reviewedAt?: string | null;
  plan?: string | null;
  rationale?: string | null;
}

/** What the server held: its sentence (awaiting review, who was notified, or the prescriber's STOP) and the card(s). */
export interface AllergyHold {
  why: string;
  cards: CdsCard[];
  /** QA D1585: `STOP` when the prescriber stopped the order; otherwise it awaits review. */
  state?: AllergyReviewStatus;
}

/** The server's allergy hold when `err` is one, else `null` (any other refusal keeps its own handling). */
export function allergyHoldRefusal(err: unknown): AllergyHold | null {
  const status = (err as { status?: unknown } | null)?.status;
  if (status !== 400) return null;
  const body = (err as { error?: unknown }).error as
    | {
        field?: unknown;
        fieldErrors?: Record<string, unknown> | null;
        message?: unknown;
        cdsAdvisories?: CdsCard[] | null;
        reviewState?: unknown;
      }
    | null
    | undefined;
  if (!body || typeof body !== 'object') return null;
  const onField = body.fieldErrors?.[ALLERGY_HOLD_FIELD];
  const why =
    typeof onField === 'string' && onField.trim()
      ? onField.trim()
      : body.field === ALLERGY_HOLD_FIELD && typeof body.message === 'string'
        ? body.message.trim()
        : '';
  if (!why) return null;
  const cards = Array.isArray(body.cdsAdvisories) ? body.cdsAdvisories : [];
  const state: AllergyReviewStatus = body.reviewState === 'STOP' ? 'STOP' : 'AWAITING_REVIEW';
  return { why, cards, state };
}
