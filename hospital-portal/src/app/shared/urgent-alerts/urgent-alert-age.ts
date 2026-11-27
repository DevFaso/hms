import { ServerTimePipe } from '../pipes/server-time.pipe';

const SERVER_TIME = new ServerTimePipe();

/** The roles an urgent alert of the escalation ladder is filed to (GET /urgent-alerts/mine and /patients). */
export const URGENT_ALERT_READER_ROLES = [
  'ROLE_DOCTOR',
  'ROLE_PHYSICIAN',
  'ROLE_SURGEON',
  'ROLE_NURSE',
  'ROLE_MIDWIFE',
  'ROLE_LAB_DIRECTOR',
];

/** How long an alert has waited, as a translation key and its parameters. */
export interface UrgentAlertAge {
  key: string;
  params: { minutes: number; hours: number };
}

/**
 * QA D4101 (r81): the age of the oldest unacknowledged alert, from the server's zone-less UTC time. Under an hour
 * it is given in minutes, then in hours and minutes; null when there is no time to read.
 */
export function urgentAlertAge(
  raisedAt: string | null | undefined,
  now: Date,
): UrgentAlertAge | null {
  if (!raisedAt) return null;
  const raised = new Date(SERVER_TIME.transform(raisedAt) as string).getTime();
  if (Number.isNaN(raised)) return null;
  const total = Math.max(0, Math.floor((now.getTime() - raised) / 60000));
  const hours = Math.floor(total / 60);
  const minutes = total % 60;
  return {
    key: hours > 0 ? 'URGENT_ALERTS.AGE_HOURS' : 'URGENT_ALERTS.AGE_MINUTES',
    params: { minutes, hours },
  };
}
