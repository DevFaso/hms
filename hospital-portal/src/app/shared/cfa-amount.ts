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

/** The message key for a problem; `requiredKey` lets a screen keep its own wording for an empty field. */
export function cfaAmountMessageKey(
  problem: CfaAmountProblem,
  requiredKey: string = CFA_AMOUNT_REQUIRED_KEY,
): string {
  return problem === 'whole' ? CFA_WHOLE_AMOUNT_KEY : requiredKey;
}
