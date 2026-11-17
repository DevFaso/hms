/**
 * QA D1071: the icon and tone of an invoice, from its STATUS.
 *
 * The patient's billing page chose its icon from "is it payable": anything
 * not payable got the green check, so a cancelled invoice read as paid. An
 * invoice's icon and colour now come from here only (guarded by
 * `scripts/lib/invoice-tone.test.mjs`): a cancelled invoice is never shown as
 * paid, a folded check-in copay says it was carried over, and only a PAID
 * invoice gets the check.
 */
export type InvoiceToneName = 'due' | 'paid' | 'cancelled' | 'credited' | 'neutral';

export interface InvoiceTone {
  /** A Material Symbols ligature. */
  icon: string;
  /** Styled by `[data-tone]` in the page's stylesheet, never by inline colours. */
  tone: InvoiceToneName;
}

export interface InvoiceToneInput {
  status?: string | null;
  /** The visit invoice a folded check-in copay was credited to (QA D428). */
  creditedTo?: string | null;
}

/** `payable`: issued, open and with money left on it (the page's own rule, QA D202). */
export function invoiceTone(inv: InvoiceToneInput, payable: boolean): InvoiceTone {
  if (inv.creditedTo) return { icon: 'redo', tone: 'credited' };
  if (inv.status === 'CANCELLED') return { icon: 'block', tone: 'cancelled' };
  if (payable) return { icon: 'pending', tone: 'due' };
  if (inv.status === 'PAID') return { icon: 'check_circle', tone: 'paid' };
  return { icon: 'receipt_long', tone: 'neutral' };
}
