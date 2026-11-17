/**
 * Billing plan PR 9 (scorecard M5): the operator a Mobile Money payment went
 * through, so the cash close totals Orange Money and Moov Money apart.
 *
 * There is no operator integration: the cashier records the payment by hand
 * with the operator's transaction reference and checks it against the
 * operator's merchant statement. The server refuses a staff Mobile Money
 * payment without an operator (`billing.payment.operatorRequired`); a row
 * recorded before the operator existed reads "Opérateur non précisé".
 */
export type MobileMoneyOperator = 'ORANGE_MONEY' | 'MOOV_MONEY' | 'AUTRE';

export interface MobileMoneyOperatorOption {
  value: MobileMoneyOperator;
  labelKey: string;
}

export const MOBILE_MONEY_OPERATORS: readonly MobileMoneyOperatorOption[] = [
  { value: 'ORANGE_MONEY', labelKey: 'MOBILE_MONEY.OPERATOR.ORANGE_MONEY' },
  { value: 'MOOV_MONEY', labelKey: 'MOBILE_MONEY.OPERATOR.MOOV_MONEY' },
  { value: 'AUTRE', labelKey: 'MOBILE_MONEY.OPERATOR.AUTRE' },
];

/** The label key of a stored operator; "Opérateur non précisé" when none was recorded. */
export function mobileMoneyOperatorLabelKey(operator: string | null | undefined): string {
  return MOBILE_MONEY_OPERATORS.some((o) => o.value === operator)
    ? `MOBILE_MONEY.OPERATOR.${operator}`
    : 'MOBILE_MONEY.OPERATOR.NOT_STATED';
}

/** Whether `method` is Mobile Money, the one method that needs an operator. */
export function isMobileMoney(method: string | null | undefined): boolean {
  return method === 'MOBILE_MONEY';
}

/** A run of digit groups joined by single spaces or dashes ("4111 1111-1111 1111"). */
const DIGIT_RUN = /\d+(?:[ -]\d+)*/g;

/**
 * Owner decision D-15: e-Keneya never collects card numbers, CVVs or PINs.
 * The same rule as the server's `NoCardNumberValidator`: 13 to 19 digits,
 * together or in groups separated by a space or a dash, that pass the Luhn
 * check. Every whole-group window of a run is tried. The server refuses such a
 * value too; this lets the form say so before anything is sent.
 */
export function containsCardNumber(value: string | null | undefined): boolean {
  if (!value?.trim()) return false;
  for (const run of value.match(DIGIT_RUN) ?? []) {
    const groups = run.split(/[ -]/).filter((g) => g.length > 0);
    for (let from = 0; from < groups.length; from++) {
      let digits = '';
      for (let to = from; to < groups.length && digits.length < 19; to++) {
        digits += groups[to];
        if (digits.length >= 13 && digits.length <= 19 && passesLuhn(digits)) return true;
      }
    }
  }
  return false;
}

function passesLuhn(digits: string): boolean {
  let sum = 0;
  let doubled = false;
  for (let i = digits.length - 1; i >= 0; i--) {
    let d = digits.charCodeAt(i) - 48;
    if (doubled) {
      d *= 2;
      if (d > 9) d -= 9;
    }
    sum += d;
    doubled = !doubled;
  }
  return sum % 10 === 0;
}
