/**
 * QA D1564: a newborn's warnings on a saved vital set. The server judges the set with the
 * newborn assessment's own bands (NewbornAlertRules, provisional pending the neonatal lead's
 * approval) and returns what it found; the portal holds no newborn numbers of its own.
 */
export interface NewbornVitalAlert {
  code: string;
  severity: 'URGENT' | 'CAUTION' | 'INFO';
  type: string;
  /** Already in the recorder's language. */
  message: string;
}

const ORDER: Record<NewbornVitalAlert['severity'], number> = { URGENT: 0, CAUTION: 1, INFO: 2 };

/** How long the newborn warning stays on screen: it is read after the dialog closes. */
export const NEWBORN_WARNING_MS = 20000;

/**
 * The heading key and the warning lines, most urgent first; null when the set raised nothing.
 * The set is saved either way: these warn, they never refuse.
 */
export function newbornVitalWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
): { headingKey: string; lines: string[] } | null {
  if (!alerts?.length) return null;
  const sorted = [...alerts].sort((a, b) => (ORDER[a.severity] ?? 3) - (ORDER[b.severity] ?? 3));
  const urgent = sorted.some((a) => a.severity === 'URGENT');
  return {
    headingKey: urgent ? 'VITAL_LIMITS.NEWBORN_WARNING_URGENT' : 'VITAL_LIMITS.NEWBORN_WARNING',
    lines: sorted.map((a) => a.message).filter((m) => !!m),
  };
}

/** The two services the announcement needs, typed narrowly so a spec can pass fakes. */
interface WarningToast {
  push(type: 'warning', message: string, durationMs?: number): void;
}
interface Translator {
  instant(key: string): unknown;
}

/** Shows the newborn warning after a save, if the set raised one. Returns whether it did. */
export function announceNewbornWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
  toast: WarningToast,
  translate: Translator,
): boolean {
  const warning = newbornVitalWarning(alerts);
  if (!warning) return false;
  const heading = String(translate.instant(warning.headingKey));
  toast.push('warning', [heading, ...warning.lines].join(' '), NEWBORN_WARNING_MS);
  return true;
}

/**
 * QA D1890 (provisional, pending the obstetric lead's approval): a mother's severe blood pressure
 * after a recorded birth. The server judges the set with the one shared rule (the postpartum
 * observation and the doctor's BPA panel use the same one) and returns the URGENT warning; the
 * portal holds no threshold of its own. The set is saved either way, and nothing waits on an
 * acknowledgement. Returns whether a warning was shown.
 */
export function announcePostpartumWarning(
  alerts: NewbornVitalAlert[] | null | undefined,
  toast: { push(type: 'error', message: string, durationMs?: number): void },
  translate: Translator,
): boolean {
  const lines = (alerts ?? []).map((a) => a.message).filter((m) => !!m);
  if (!lines.length) return false;
  const heading = String(translate.instant('VITAL_LIMITS.POSTPARTUM_WARNING_URGENT'));
  toast.push('error', [heading, ...lines].join(' '), NEWBORN_WARNING_MS);
  return true;
}
