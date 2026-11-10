import { CdsCard } from '../cds-card/cds-card.model';

/**
 * QA D1563 / D1555 (round 43): an allergy recorded after the order was signed is read again at every
 * gate (the eMAR dose, the pharmacist's verification, the fill and the hand-over, the routing to an
 * outside pharmacy). A drug the patient's current allergies stop is refused with a 400 on the request
 * field `allergyAcknowledgementReason`, whose body carries the allergy card (`cdsAdvisories`); the card's
 * `exceptionKey` is the token the next attempt sends back with the reason, which ties the reason to
 * that allergy. Every portal surface reads the refusal here and asks for the reason through
 * `<app-allergy-acknowledgement-dialog>` (scripts/lib/allergy-acknowledgement-surfaces.test.mjs).
 */

/** The request field the server reads the reason from, and names in its refusal. */
export const ALLERGY_ACK_FIELD = 'allergyAcknowledgementReason';

/** The prefix of the allergy card's `exceptionKey`. */
export const ALLERGY_KEY_PREFIX = 'allergy:';

/** What the server refused: its sentence, the allergy card(s) and the token to send back. */
export interface AllergyAcknowledgementRefusal {
  why: string;
  cards: CdsCard[];
  token: string | null;
}

/** The two request fields an acknowledgement adds to the refused request. */
export interface AllergyAcknowledgementFields {
  allergyAcknowledgementReason: string;
  allergyAcknowledgementToken?: string;
}

/** The server's allergy refusal when `err` is one, else `null` (any other refusal keeps its own handling). */
export function allergyAcknowledgementRefusal(err: unknown): AllergyAcknowledgementRefusal | null {
  const status = (err as { status?: unknown } | null)?.status;
  if (status !== 400) return null;
  const body = (err as { error?: unknown }).error as
    | {
        field?: unknown;
        fieldErrors?: Record<string, unknown> | null;
        message?: unknown;
        cdsAdvisories?: CdsCard[] | null;
      }
    | null
    | undefined;
  if (!body || typeof body !== 'object') return null;
  const onField = body.fieldErrors?.[ALLERGY_ACK_FIELD];
  const why =
    typeof onField === 'string' && onField.trim()
      ? onField.trim()
      : body.field === ALLERGY_ACK_FIELD && typeof body.message === 'string'
        ? body.message.trim()
        : '';
  if (!why) return null;
  const cards = Array.isArray(body.cdsAdvisories) ? body.cdsAdvisories : [];
  return { why, cards, token: allergyToken(cards) };
}

/** The token an allergy card carries, when one does. */
export function allergyToken(cards: readonly CdsCard[] | null | undefined): string | null {
  const card = (cards ?? []).find((c) => c.exceptionKey?.startsWith(ALLERGY_KEY_PREFIX));
  return card?.exceptionKey ?? null;
}

/** The fields the nurse's or pharmacist's reason adds to the refused request. */
export function allergyAcknowledgementFields(
  refusal: AllergyAcknowledgementRefusal,
  reason: string,
): AllergyAcknowledgementFields {
  const fields: AllergyAcknowledgementFields = { allergyAcknowledgementReason: reason.trim() };
  if (refusal.token) fields.allergyAcknowledgementToken = refusal.token;
  return fields;
}
