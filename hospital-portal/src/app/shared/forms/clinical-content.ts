/**
 * QA D1150 / D1151 / D1158 (round 21): a clinical record saved with nothing
 * in it. A blank ultrasound report read back as "Normal"; blank postpartum
 * observations counted as the 15-minute checks of a haemorrhaging woman; a
 * blank newborn assessment, encounter note or triage reached the chart.
 *
 * A form calls {@link hasClinicalContent} with the fields that ARE the
 * finding (never the time, the signer or the hospital) and refuses — marking
 * the first of them through `refuseFields` — when none holds anything.
 */

/** Whether one value counts as recorded: text that is not blank, any number (0 too), a ticked box. */
export function isRecorded(value: unknown): boolean {
  if (typeof value === 'string') return value.trim() !== '';
  if (typeof value === 'number') return Number.isFinite(value);
  if (typeof value === 'boolean') return value;
  if (Array.isArray(value)) return value.length > 0;
  return value !== null && value !== undefined;
}

/** Whether at least one of `fields` of `form` is recorded. */
export function hasClinicalContent<K extends string>(
  form: Partial<Record<K, unknown>>,
  fields: readonly K[],
): boolean {
  return fields.some((field) => isRecorded(form[field]));
}
