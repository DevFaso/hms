/**
 * QA D969: one reading of a lab result's grade for every lab screen.
 *
 * The validation queue kept its own list of flags (CRITICAL, LOW, HIGH,
 * NORMAL) and read anything else as "not graded", so a positive malaria rapid
 * test, which the server grades ABNORMAL from its recorded flag, showed
 * "Non évalué" there while /lab-results showed "Anormal". Every lab screen
 * now asks this module, and labels the answer through the labSeverityFlag
 * enum domain.
 *
 * The flags are the ones LabResultMapper emits (severityFlag), plus
 * UNIT_MISMATCH when no configured range is in the result's unit.
 */
export const LAB_SEVERITY_FLAGS = [
  'CRITICAL',
  'HIGH',
  'LOW',
  'ABNORMAL',
  'NORMAL',
  'UNSPECIFIED',
] as const;

export type LabSeverityFlag = (typeof LAB_SEVERITY_FLAGS)[number] | 'UNIT_MISMATCH';

export interface GradedLabResult {
  severityFlag?: string | null;
  unitMismatch?: boolean | null;
}

/** The result's grade as the server stated it; anything unknown is UNSPECIFIED, never NORMAL. */
export function labSeverityFlag(r: GradedLabResult): LabSeverityFlag {
  if (r.unitMismatch) return 'UNIT_MISMATCH';
  const flag = (r.severityFlag ?? '').trim().toUpperCase();
  return (LAB_SEVERITY_FLAGS as readonly string[]).includes(flag)
    ? (flag as LabSeverityFlag)
    : 'UNSPECIFIED';
}

/** How a grade is drawn: critical, abnormal (out of range or a positive answer), normal, or ungraded. */
export function labSeverityTone(
  flag: LabSeverityFlag,
): 'critical' | 'abnormal' | 'normal' | 'ungraded' {
  switch (flag) {
    case 'CRITICAL':
      return 'critical';
    case 'HIGH':
    case 'LOW':
    case 'ABNORMAL':
      return 'abnormal';
    case 'NORMAL':
      return 'normal';
    default:
      return 'ungraded';
  }
}
