import { isAsNeededFrequency } from './as-needed-frequency';

describe('isAsNeededFrequency (QA round 30: the server reading of an as-needed order)', () => {
  it('reads PRN and "si besoin" in English, French and Spanish, any case and accents', () => {
    for (const f of [
      'PRN',
      'prn',
      'si besoin',
      'Si besoin, max 4/j',
      'as needed',
      'Si es necesario',
      'SI NECESARIO',
      'Paracétamol prn',
    ]) {
      expect(isAsNeededFrequency(f)).withContext(f).toBeTrue();
    }
  });

  it('a fixed schedule, a single dose or nothing is not as-needed', () => {
    for (const f of [
      '3 fois par jour',
      'q6h',
      'TID',
      'STAT',
      'PRN STAT',
      '',
      '   ',
      null,
      undefined,
    ]) {
      expect(isAsNeededFrequency(f)).withContext(String(f)).toBeFalse();
    }
  });
});
