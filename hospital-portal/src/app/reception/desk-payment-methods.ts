/**
 * QA D948/D975: the ways a patient pays at the front desk, one list for the
 * check-in copay and the pending-payments dialog. Mobile Money is how most
 * patients pay; a method whose `needsReference` is true needs the operator's
 * transaction number or the insurer's claim/authorization number (never the
 * policy number: a reference is single-use, `billing.payment.duplicateReference`).
 * The server refuses it without one: `billing.payment.referenceRequired`.
 */
export interface DeskPaymentMethod {
  value: 'CASH' | 'MOBILE_MONEY' | 'CARD' | 'INSURANCE' | 'OTHER';
  labelKey: string;
  needsReference: boolean;
}

export const DESK_PAYMENT_METHODS: readonly DeskPaymentMethod[] = [
  { value: 'CASH', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.CASH', needsReference: false },
  {
    value: 'MOBILE_MONEY',
    labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.MOBILE_MONEY',
    needsReference: true,
  },
  { value: 'CARD', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.CARD', needsReference: false },
  { value: 'INSURANCE', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.INSURANCE', needsReference: true },
  { value: 'OTHER', labelKey: 'PORTAL.ENUM.PAYMENT_METHOD.OTHER', needsReference: false },
];

/** Whether `method` needs a reference before it can be recorded. */
export function deskMethodNeedsReference(method: string | null | undefined): boolean {
  return DESK_PAYMENT_METHODS.some((m) => m.value === method && m.needsReference);
}
