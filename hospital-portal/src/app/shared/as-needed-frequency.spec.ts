import { AS_NEEDED_PHRASES, isAsNeededFrequency } from './as-needed-frequency';
import cases from './as-needed-frequency.cases.json';

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

  // QA D1360: the shared fixture hospital-core's MarDoseScheduleTest runs too.
  it('reads every as-needed wording of the shared fixture (au besoin, à la demande, si douleur, ...)', () => {
    expect(cases.asNeeded.length).toBeGreaterThanOrEqual(AS_NEEDED_PHRASES.length);
    for (const f of cases.asNeeded) {
      expect(isAsNeededFrequency(f)).withContext(f).toBeTrue();
    }
  });

  it('reads no case of the shared fixture as as-needed that the server does not', () => {
    for (const f of cases.notAsNeeded) {
      expect(isAsNeededFrequency(f)).withContext(f).toBeFalse();
    }
  });
});
