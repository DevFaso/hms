import {
  MOBILE_MONEY_OPERATORS,
  containsCardNumber,
  isMobileMoney,
  mobileMoneyOperatorLabelKey,
} from './mobile-money-operator';

describe('Mobile Money operator (billing plan PR 9)', () => {
  it('offers Orange Money, Moov Money and another operator, in that order', () => {
    expect(MOBILE_MONEY_OPERATORS.map((o) => o.value)).toEqual([
      'ORANGE_MONEY',
      'MOOV_MONEY',
      'AUTRE',
    ]);
  });

  it('labels a stored operator, and an older row as "not stated"', () => {
    expect(mobileMoneyOperatorLabelKey('ORANGE_MONEY')).toBe('MOBILE_MONEY.OPERATOR.ORANGE_MONEY');
    expect(mobileMoneyOperatorLabelKey('MOOV_MONEY')).toBe('MOBILE_MONEY.OPERATOR.MOOV_MONEY');
    expect(mobileMoneyOperatorLabelKey(null)).toBe('MOBILE_MONEY.OPERATOR.NOT_STATED');
    expect(mobileMoneyOperatorLabelKey(undefined)).toBe('MOBILE_MONEY.OPERATOR.NOT_STATED');
    expect(mobileMoneyOperatorLabelKey('BOGUS')).toBe('MOBILE_MONEY.OPERATOR.NOT_STATED');
  });

  it('only Mobile Money needs an operator', () => {
    expect(isMobileMoney('MOBILE_MONEY')).toBeTrue();
    expect(isMobileMoney('CASH')).toBeFalse();
    expect(isMobileMoney(null)).toBeFalse();
  });
});

describe('containsCardNumber (owner decision D-15)', () => {
  it('finds a Luhn-valid 13 to 19 digit card number, grouped or not', () => {
    for (const card of [
      '4111111111111111',
      '4111 1111 1111 1111',
      '4111-1111-1111-1111',
      '378282246310005',
      '4222222222222',
      'carte 5555 5555 5555 4444 exp 12/27',
      '4111 1111 1111 1111 2027',
    ]) {
      expect(containsCardNumber(card)).withContext(card).toBeTrue();
    }
  });

  it('keeps an ordinary reference', () => {
    for (const ok of [
      null,
      undefined,
      '',
      '  ',
      'OM-778812',
      'MP231117.1234.A12345',
      '4111111111111112',
      '411111111111',
      '12345678901234567890',
      '+226 70 20 20 20',
      'VIR-2026-11-17-000123',
    ]) {
      expect(containsCardNumber(ok)).withContext(String(ok)).toBeFalse();
    }
  });
});
