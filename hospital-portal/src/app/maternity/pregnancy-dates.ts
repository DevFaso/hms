import { MenstrualHistory } from '../services/maternity.service';

/** Naegele's rule: the due date is 280 days after the last menstrual period (the server computes the same). */
export const PREGNANCY_DAYS = 280;

function parseDay(day: string | null | undefined): Date | null {
  if (!day) return null;
  const [y, m, d] = day.slice(0, 10).split('-').map(Number);
  if (!y || !m || !d) return null;
  return new Date(y, m - 1, d);
}

function formatDay(date: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`;
}

/** QA D1005: the due date from the last menstrual period (yyyy-MM-dd), or '' when the LMP is not a date. */
export function dueDateFromLmp(lmp: string | null | undefined): string {
  const start = parseDay(lmp);
  if (!start) return '';
  start.setDate(start.getDate() + PREGNANCY_DAYS);
  return formatDay(start);
}

/** The due date a history carries: ultrasound first, then the recorded one, then LMP + 280 days. */
export function effectiveDueDate(menstrual: MenstrualHistory | null | undefined): string {
  if (!menstrual) return '';
  return (
    menstrual.estimatedDueDateByUltrasound ||
    menstrual.estimatedDueDate ||
    dueDateFromLmp(menstrual.lastMenstrualPeriod)
  );
}

/**
 * Gestational age today from a due date, as completed weeks and days, or null
 * when there is no due date or the pregnancy would be over 44 weeks (no
 * longer ongoing).
 */
export function gestationalAge(
  dueDate: string | null | undefined,
  today: Date = new Date(),
): { weeks: number; days: number } | null {
  const start = parseDay(dueDate);
  if (!start) return null;
  start.setDate(start.getDate() - PREGNANCY_DAYS);
  const midnight = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const elapsed = Math.round((midnight.getTime() - start.getTime()) / 86_400_000);
  if (elapsed < 0 || elapsed > 44 * 7) return null;
  return { weeks: Math.floor(elapsed / 7), days: elapsed % 7 };
}

/**
 * QA D1067: whether the pregnancy this history dates is over. The server sets
 * `deliveredAt` when a delivery is recorded at or after the history's LMP
 * (the same rule that refuses an antenatal calendar, prenatal.pregnancy.delivered).
 */
export function isDelivered(history: { deliveredAt?: string | null } | null | undefined): boolean {
  return !!history?.deliveredAt;
}

/**
 * QA D1067: the ongoing pregnancy a recorded history describes (gestational
 * age today and due date), or null when there is none, it is over 44 weeks,
 * or the woman has delivered. Every screen that shows a running gestational
 * age for a stored history goes through here (a guard keeps it so): the
 * D1005 chart line read the due date alone and showed a delivered woman at
 * "39 SA".
 */
export function ongoingPregnancy(
  history:
    { deliveredAt?: string | null; menstrualHistory?: MenstrualHistory | null } | null | undefined,
  today: Date = new Date(),
): { weeks: number; days: number; dueDate: string } | null {
  if (!history || isDelivered(history)) return null;
  const dueDate = effectiveDueDate(history.menstrualHistory);
  const ga = gestationalAge(dueDate, today);
  return ga && dueDate ? { ...ga, dueDate } : null;
}
