import { firstImplausibleVital, VITAL_SIGN_LIMITS } from './vital-sign-limits';

describe('firstImplausibleVital', () => {
  it('flags the values the QA run saved (45 °C, HR 400, SpO2 150)', () => {
    expect(firstImplausibleVital({ temperatureCelsius: 45 })).toBe(
      VITAL_SIGN_LIMITS.temperatureCelsius,
    );
    expect(firstImplausibleVital({ heartRateBpm: 400 })).toBe(VITAL_SIGN_LIMITS.heartRateBpm);
    expect(firstImplausibleVital({ spo2Percent: 150 })).toBe(VITAL_SIGN_LIMITS.spo2Percent);
  });

  it('accepts abnormal but real readings at the bounds, and missing readings', () => {
    expect(
      firstImplausibleVital({
        temperatureCelsius: 44,
        heartRateBpm: 20,
        spo2Percent: 70,
        weightKg: 0.2,
        respiratoryRateBpm: undefined,
        systolicBpMmHg: null,
      }),
    ).toBeNull();
  });

  it('treats NaN as implausible', () => {
    expect(firstImplausibleVital({ heightCm: Number.NaN })).toBe(VITAL_SIGN_LIMITS.heightCm);
  });
});
