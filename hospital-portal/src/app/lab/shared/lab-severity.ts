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
 * UNIT_MISMATCH when no configured range is in the result's unit and
 * GRADE_UNKNOWN when a range applies but the value does not settle a grade
 * (QA D1394: ">1" on a range ending at 6.5).
 */
export const LAB_SEVERITY_FLAGS = [
  'CRITICAL',
  'HIGH',
  'LOW',
  'ABNORMAL',
  'NORMAL',
  'UNSPECIFIED',
] as const;

export type LabSeverityFlag =
  (typeof LAB_SEVERITY_FLAGS)[number] | 'UNIT_MISMATCH' | 'GRADE_UNKNOWN';

export interface GradedLabResult {
  severityFlag?: string | null;
  unitMismatch?: boolean | null;
  gradeUnknown?: boolean | null;
}

/** The result's grade as the server stated it; anything unknown is UNSPECIFIED, never NORMAL. */
export function labSeverityFlag(r: GradedLabResult): LabSeverityFlag {
  if (r.unitMismatch) return 'UNIT_MISMATCH';
  if (r.gradeUnknown) return 'GRADE_UNKNOWN';
  const flag = (r.severityFlag ?? '').trim().toUpperCase();
  return (LAB_SEVERITY_FLAGS as readonly string[]).includes(flag)
    ? (flag as LabSeverityFlag)
    : 'UNSPECIFIED';
}

/** QA D1092 / D1394: the server could not grade it, so a person confirms before releasing it. */
export function labReleaseNeedsReview(r: GradedLabResult): boolean {
  return !!r.unitMismatch || !!r.gradeUnknown;
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
