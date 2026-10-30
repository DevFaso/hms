import { cfaAmountMessageKey, cfaAmountProblem } from './cfa-amount';

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
    expect(cfaAmountMessageKey('required')).toBe('BILLING.PAYMENT_AMOUNT_REQUIRED');
    expect(cfaAmountMessageKey('whole')).toBe('BILLING.PAYMENT_WHOLE_AMOUNT');
    expect(cfaAmountMessageKey('required', 'PHARMACY.CHECKOUT_INVALID_AMOUNT')).toBe(
      'PHARMACY.CHECKOUT_INVALID_AMOUNT',
    );
  });
});
