import { CFA_AMOUNT_REQUIRED_KEY, cfaAmountMessageKey, cfaAmountProblem } from './cfa-amount';

describe('cfaAmountProblem (QA D1089)', () => {
  it('asks for an amount when the field is empty, zero or negative', () => {
    for (const empty of [null, undefined, '', 0, -5, Number.NaN]) {
      expect(cfaAmountProblem(empty)).toBe('required');
    }
  });

  it('refuses decimals only once there is an amount', () => {
    expect(cfaAmountProblem(1500.5)).toBe('whole');
    expect(cfaAmountProblem(1500)).toBeNull();
  });

  it('maps each problem to its own message', () => {
    // QA D1294: no default wording any more; a payment screen names the payment key.
    expect(cfaAmountMessageKey('required', CFA_AMOUNT_REQUIRED_KEY)).toBe(
      'BILLING.PAYMENT_AMOUNT_REQUIRED',
    );
    expect(cfaAmountMessageKey('whole', CFA_AMOUNT_REQUIRED_KEY)).toBe(
      'BILLING.PAYMENT_WHOLE_AMOUNT',
    );
    expect(cfaAmountMessageKey('required', 'BILLING.LINE_UNIT_PRICE_REQUIRED')).toBe(
      'BILLING.LINE_UNIT_PRICE_REQUIRED',
    );
    expect(cfaAmountMessageKey('required', 'PHARMACY.CHECKOUT_INVALID_AMOUNT')).toBe(
      'PHARMACY.CHECKOUT_INVALID_AMOUNT',
    );
  });
});
