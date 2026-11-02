/**
 * QA D1089: what is wrong with a typed franc CFA amount, checked in one order
 * everywhere: an empty or non-positive amount first ("enter an amount"), then
 * decimals ("francs have no cents", QA D184). The waiver form answered an
 * empty amount with the decimals message because it tested both at once.
 *
 * `BILLING.PAYMENT_WHOLE_AMOUNT` is resolved only here
 * (`scripts/lib/cfa-amount.test.mjs`), so no screen can show it for an empty
 * field again.
 */
export type CfaAmountProblem = 'required' | 'whole';

export const CFA_WHOLE_AMOUNT_KEY = 'BILLING.PAYMENT_WHOLE_AMOUNT';
export const CFA_AMOUNT_REQUIRED_KEY = 'BILLING.PAYMENT_AMOUNT_REQUIRED';

export function cfaAmountProblem(
  amount: number | string | null | undefined,
): CfaAmountProblem | null {
  if (amount === null || amount === undefined || amount === '') return 'required';
  const value = typeof amount === 'number' ? amount : Number(amount);
  if (!Number.isFinite(value) || value <= 0) return 'required';
  return Number.isInteger(value) ? null : 'whole';
}

/**
 * The message key for a problem. `requiredKey` is the screen's own wording for
 * an empty field, and every caller names it: QA D1294 (round 27) — an invoice
 * line's blank unit price took the default and said "Enter a payment amount
 * greater than zero". A payment screen passes {@link CFA_AMOUNT_REQUIRED_KEY}.
 */
export function cfaAmountMessageKey(problem: CfaAmountProblem, requiredKey: string): string {
  return problem === 'whole' ? CFA_WHOLE_AMOUNT_KEY : requiredKey;
}
