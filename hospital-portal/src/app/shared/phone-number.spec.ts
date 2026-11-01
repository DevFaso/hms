import { isFacilityPhone, toE164 } from './phone-number';

describe('toE164 (mirror of the server PhoneNumbers.toE164)', () => {
  it('accepts a Burkina number typed locally or internationally, with desk formatting', () => {
    expect(toE164('70202020')).toBe('+22670202020');
    expect(toE164('70 20 20 20')).toBe('+22670202020');
    expect(toE164('+22670202020')).toBe('+22670202020');
    expect(toE164('+226 70 20 20 20')).toBe('+22670202020');
    expect(toE164('0022670202020')).toBe('+22670202020');
    expect(toE164('70.20-20(20)')).toBe('+22670202020');
  });

  it("trims like Java's String.trim: C0 controls at the ends go, a no-break space stays", () => {
    expect(toE164('\u001f70202020\u0000')).toBe('+22670202020');
    expect(toE164('\u0001+22670202020')).toBe('+22670202020');
    expect(toE164('70202020\u00a0')).toBeNull();
    expect(toE164('7020\u00012020')).toBeNull();
  });

  it('accepts a foreign international number', () => {
    expect(toE164('+33 6 12 34 56 78')).toBe('+33612345678');
  });

  it('refuses an e-mail, letters, a blank and a Burkina number of the wrong length', () => {
    expect(toE164('admin@example.com')).toBeNull();
    expect(toE164('70ab2020')).toBeNull();
    expect(toE164('   ')).toBeNull();
    expect(toE164(null)).toBeNull();
    expect(toE164(undefined)).toBeNull();
    expect(toE164('7020202')).toBeNull();
    expect(toE164('702020201')).toBeNull();
    expect(toE164('+2267020202')).toBeNull();
    expect(toE164('+0123456789')).toBeNull();
    expect(toE164('+1234567')).toBeNull();
  });

  it('drops the trunk zero only for a calling code other than Burkina Faso', () => {
    expect(toE164('0612345678', '33')).toBe('+33612345678');
    expect(toE164('070202020')).toBeNull();
  });
});

// QA D1211 (round 23): the client copy of PhoneNumberValidator (the facility
// rule, no country), with the values QA typed and the server refuses.
describe('isFacilityPhone (mirror of the server @PhoneNumber rule)', () => {
  it('refuses what QA typed into phone fields', () => {
    for (const value of [
      '12ab',
      'xyz',
      'appeler le voisin',
      'pas de tel',
      'voisin',
      '+12ab',
      '12345',
    ]) {
      expect(isFacilityPhone(value)).withContext(value).toBeFalse();
    }
  });

  it('keeps real numbers and a blank field', () => {
    for (const value of [
      '',
      '   ',
      null,
      undefined,
      '70202020',
      '70 20 20 20',
      '+226 70 20 20 20',
      '0022670202020',
      '+225 07 08 09 10 11',
      '+33 6 12 34 56 78',
      '0612345678',
      '123456',
    ]) {
      expect(isFacilityPhone(value)).withContext(String(value)).toBeTrue();
    }
  });

  it('refuses an international number E.164 cannot hold', () => {
    expect(isFacilityPhone('+0123456789')).toBeFalse();
    expect(isFacilityPhone('00123')).toBeFalse();
  });
});
