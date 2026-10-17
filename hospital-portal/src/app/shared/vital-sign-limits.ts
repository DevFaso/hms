/**
 * Physiological plausibility bounds for a recorded vital sign. They mirror
 * hospital-core's VitalSignLimits (the server rejects the same values with a
 * 400): they catch typing slips such as 45 °C, a heart rate of 400 or an SpO2
 * of 150 %, not abnormal-but-real readings, which are flagged by NEWS2.
 */
export interface VitalSignLimit {
  min: number;
  max: number;
  /** i18n key of the "must be between {{min}} and {{max}}" message. */
  messageKey: string;
}

export type VitalSignField =
  | 'temperatureCelsius'
  | 'heartRateBpm'
  | 'respiratoryRateBpm'
  | 'systolicBpMmHg'
  | 'diastolicBpMmHg'
  | 'spo2Percent'
  | 'bloodGlucoseMgDl'
  | 'weightKg'
  | 'heightCm'
  | 'headCircumferenceCm';

export const VITAL_SIGN_LIMITS: Record<VitalSignField, VitalSignLimit> = {
  temperatureCelsius: { min: 25, max: 44, messageKey: 'VITAL_LIMITS.TEMPERATURE' },
  heartRateBpm: { min: 20, max: 300, messageKey: 'VITAL_LIMITS.HEART_RATE' },
  respiratoryRateBpm: { min: 4, max: 80, messageKey: 'VITAL_LIMITS.RESPIRATORY_RATE' },
  systolicBpMmHg: { min: 40, max: 300, messageKey: 'VITAL_LIMITS.SYSTOLIC' },
  diastolicBpMmHg: { min: 20, max: 200, messageKey: 'VITAL_LIMITS.DIASTOLIC' },
  spo2Percent: { min: 0, max: 100, messageKey: 'VITAL_LIMITS.SPO2' },
  bloodGlucoseMgDl: { min: 20, max: 800, messageKey: 'VITAL_LIMITS.GLUCOSE' },
  weightKg: { min: 0.2, max: 400, messageKey: 'VITAL_LIMITS.WEIGHT' },
  heightCm: { min: 20, max: 250, messageKey: 'VITAL_LIMITS.HEIGHT' },
  headCircumferenceCm: { min: 15, max: 70, messageKey: 'VITAL_LIMITS.HEAD_CIRCUMFERENCE' },
};

/** The first reading outside its bounds, or null when every present reading is plausible. */
export function firstImplausibleVital(
  values: Partial<Record<VitalSignField, number | null | undefined>>,
): VitalSignLimit | null {
  for (const field of Object.keys(VITAL_SIGN_LIMITS) as VitalSignField[]) {
    const value = values[field];
    if (value === null || value === undefined) continue;
    const limit = VITAL_SIGN_LIMITS[field];
    if (Number.isNaN(value) || value < limit.min || value > limit.max) return limit;
  }
  return null;
}
