/**
 * QA D634 / D1321 (round 29): a staff payment dialog prefilled the whole
 * balance and did not say the patient had already declared part of it
 * (pending confirmation), so the cashier took the money twice, or the server
 * refused the amount ("le patient a déclaré 1 200 …"). The billing screen
 * fixed it for itself in D634; the desk's pay-collect kept the old prefill.
 *
 * One rule for every staff payment dialog: the amount offered is what is
 * still due once the pending declarations are left out — never more.
 */

/** A payment row as the payments endpoint lists it. */
export interface PaymentRowLike {
  readonly status?: string | null;
  readonly amount?: number | null;
}

/** What the patient declared on an invoice and staff have not confirmed yet. */
export function pendingDeclared(rows: readonly PaymentRowLike[] | null | undefined): number {
  return (rows ?? [])
    .filter((r) => r.status === 'PENDING')
    .reduce((sum, r) => sum + (Number(r.amount) || 0), 0);
}

/**
 * The amount a staff payment dialog prefills: the balance less what is
 * pending, or `null` when nothing is left to take (the field stays empty and
 * the reader decides).
 */
export function amountStillDue(balance: number | null | undefined, pending: number): number | null {
  const rest = Math.max((Number(balance) || 0) - Math.max(pending, 0), 0);
  return rest > 0 ? rest : null;
}
