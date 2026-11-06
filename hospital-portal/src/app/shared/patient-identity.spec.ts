import {
  birthDateRefusalKey,
  earliestBirthDate,
  isMadeOfDigits,
  latestBirthDate,
} from './patient-identity';

describe('patient identity rules (QA D1403 + D1414, mirrors PatientIdentityRules)', () => {
  const now = new Date(2026, 10, 6);

  it('accepts yesterday and 130 years back, to the day', () => {
    expect(latestBirthDate(now)).toBe('2026-11-05');
    expect(earliestBirthDate(now)).toBe('1896-11-06');
    expect(birthDateRefusalKey('2026-11-05', now)).toBeNull();
    expect(birthDateRefusalKey('1896-11-06', now)).toBeNull();
  });

  it('refuses today, the future and further back', () => {
    expect(birthDateRefusalKey('2026-11-06', now)).toBe('PATIENTS.DOB_NOT_PAST');
    expect(birthDateRefusalKey('2030-01-01', now)).toBe('PATIENTS.DOB_NOT_PAST');
    expect(birthDateRefusalKey('1896-11-05', now)).toBe('PATIENTS.DOB_IMPOSSIBLE');
  });

  it('leaves a blank date to the required check', () => {
    expect(birthDateRefusalKey('', now)).toBeNull();
  });

  it('a name of digits, whatever separates them; not a name with a letter, nor a dash', () => {
    for (const name of ['12345', ' 12345 ', '123 45', '12-34']) {
      expect(isMadeOfDigits(name)).withContext(name).toBeTrue();
    }
    for (const name of ['A1', 'Awa', "O'Brien", '-', '', 'Kaboré 2']) {
      expect(isMadeOfDigits(name)).withContext(name).toBeFalse();
    }
  });
});
