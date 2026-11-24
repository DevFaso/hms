/**
 * QA D2204 / D2203: the choices a patient (or the desk, for them) can make about messages, and only those a
 * sender reads. The kinds are exactly the preferences the backend's PatientNotificationEvent names; PUSH and
 * the « Accès délégué », « Système » and « Général » kinds were offered and did nothing, so they are gone.
 * PatientChannelChoicesGuardTest (hospital-core) fails the build when this list and the senders drift apart.
 */
export const NOTICE_TYPES = [
  'APPOINTMENT_REMINDER',
  'LAB_RESULT',
  'MEDICATION_REFILL',
  'BILLING',
  'DISCHARGE_SUMMARY',
] as const;

/** The channels the patient chooses in their own settings: the bell, e-mail and SMS. */
export const NOTICE_CHANNELS = ['IN_APP', 'EMAIL', 'SMS'] as const;

/** The channels the desk records for a patient (the bell stays the patient's own choice). */
export const DESK_NOTICE_CHANNELS = ['SMS', 'EMAIL'] as const;

export type NoticeType = (typeof NOTICE_TYPES)[number];

/**
 * QA r73 D3700: who recorded a consent choice, as the server lets this reader know it. Another facility's staff
 * member comes without a name, and no reader ever receives the recorder's account id.
 */
export type ConsentRecorderKind = 'PATIENT' | 'STAFF' | 'OTHER_FACILITY';

/** The label key for a recorder shown without a name; null when the name (or « unknown ») is shown. */
export function consentRecorderKey(kind: string | null | undefined): string | null {
  if (kind === 'PATIENT') return 'PATIENT_CHANNELS.RECORDER_PATIENT';
  if (kind === 'OTHER_FACILITY') return 'PATIENT_CHANNELS.RECORDER_OTHER_FACILITY';
  return null;
}
export type NoticeChannel = (typeof NOTICE_CHANNELS)[number];

export interface NotificationChoiceValue {
  notificationType: string;
  channel: string;
  enabled: boolean;
}

/** Send only the choices actually edited, never stale neighbouring switches or server provenance. */
export function changedChannelChoices(
  current: readonly NotificationChoiceValue[],
  saved: readonly NotificationChoiceValue[],
): NotificationChoiceValue[] {
  const prior = new Map(
    saved.map((row) => [`${row.notificationType}/${row.channel}`, row.enabled]),
  );
  return current
    .filter((row) => row.enabled !== (prior.get(`${row.notificationType}/${row.channel}`) ?? true))
    .map(({ notificationType, channel, enabled }) => ({ notificationType, channel, enabled }));
}
