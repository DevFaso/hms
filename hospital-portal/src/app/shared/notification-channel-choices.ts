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
export type NoticeChannel = (typeof NOTICE_CHANNELS)[number];
