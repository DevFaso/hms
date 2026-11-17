import { STAFF_PAYMENT_METHODS } from '../services/billing.service';
import { DESK_PAYMENT_METHODS, deskMethodNeedsReference } from '../reception/desk-payment-methods';
import { PHARMACY_CHECKOUT_METHODS } from '../pharmacy/pharmacy-checkout';

/**
 * QA D1611 (round 44): every till the portal offers (the cashier's invoice
 * payment, the front desk's copay and pending payments, the pharmacy
 * checkout) takes money received now. "Assurance" printed "Total payé" and
 * counted the insurer's share as collected, with no claim to the payer; the
 * server refuses it (`billing.payment.insuranceNotAPayment`). A till list that
 * offers it again fails here.
 */
describe('Till payment methods (QA D1611)', () => {
  const tills: Record<string, readonly string[]> = {
    'invoice payment (billing)': STAFF_PAYMENT_METHODS,
    'front desk (copay, pending payments)': DESK_PAYMENT_METHODS.map((m) => m.value),
    'pharmacy checkout': PHARMACY_CHECKOUT_METHODS.map((m) => m.value),
  };

  for (const [till, methods] of Object.entries(tills)) {
    it(`${till} never offers INSURANCE as money received`, () => {
      expect(methods.length).toBeGreaterThan(0);
      expect(methods).not.toContain('INSURANCE');
    });
  }

  it('the desk still asks a mobile-money reference', () => {
    expect(deskMethodNeedsReference('MOBILE_MONEY')).toBeTrue();
    expect(deskMethodNeedsReference('INSURANCE')).toBeFalse();
  });
});
