import { bornLabelKey } from './born-label';

describe('bornLabelKey (QA D1311)', () => {
  it('picks the word by the patient sex, neutral when unknown', () => {
    expect(bornLabelKey('FEMALE')).toBe('PATIENTS.BORN_FEMALE');
    expect(bornLabelKey('f')).toBe('PATIENTS.BORN_FEMALE');
    expect(bornLabelKey('MALE')).toBe('PATIENTS.BORN_MALE');
    expect(bornLabelKey('OTHER')).toBe('PATIENTS.BORN');
    expect(bornLabelKey(null)).toBe('PATIENTS.BORN');
  });
});
