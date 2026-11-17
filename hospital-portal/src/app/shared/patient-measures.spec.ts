import {
  isGrowthAge,
  isRecordedMale,
  offersFundalHeight,
  offersHeadCircumference,
} from './patient-measures';

describe('patient-measures (QA D1077)', () => {
  const now = new Date('2026-10-30T12:00:00').getTime();

  it('never offers a fundal height for a patient recorded as male', () => {
    expect(isRecordedMale('MALE')).toBeTrue();
    expect(isRecordedMale('m')).toBeTrue();
    // Round 19: the server's male values (ObstetricEligibility), not only MALE/M.
    expect(isRecordedMale('Homme')).toBeTrue();
    expect(isRecordedMale('masculin')).toBeTrue();
    expect(isRecordedMale('FEMALE')).toBeFalse();
    expect(offersFundalHeight('MALE')).toBeFalse();
    expect(offersFundalHeight('M')).toBeFalse();
    expect(offersFundalHeight('FEMALE')).toBeTrue();
    expect(offersFundalHeight(null)).toBeTrue();
  });

  it('offers head circumference on the growth tab age rule (under 20)', () => {
    expect(offersHeadCircumference('1961-05-01', now)).withContext('65 years old').toBeFalse();
    expect(offersHeadCircumference('2020-01-01', now)).toBeTrue();
    expect(offersHeadCircumference('2007-01-01', now)).withContext('19 years old').toBeTrue();
    expect(offersHeadCircumference('2006-01-01', now)).withContext('20 years old').toBeFalse();
    expect(offersHeadCircumference(null, now)).withContext('unknown DOB').toBeTrue();
    expect(isGrowthAge(null, now)).toBeFalse();
  });
});
