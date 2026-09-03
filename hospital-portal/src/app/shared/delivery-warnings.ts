/**
 * Per-channel outcome of an activation email/SMS attempt, reported by the
 * backend on registration and resend responses. Before this existed a dead
 * mail/SMS transport looked exactly like a successful registration.
 */
export interface NotificationDeliveryStatus {
  channel: 'EMAIL' | 'SMS';
  purpose?: 'ACTIVATION' | 'CREDENTIALS' | 'WELCOME';
  outcome: 'SENT' | 'FAILED' | 'NOT_CONFIGURED' | 'MOCKED' | 'NO_CONTACT';
  target?: string;
  detail?: string;
}

/**
 * NO_CONTACT is informational (a phone-first patient simply has no email);
 * only these mean an activation message someone expected went nowhere.
 */
const PROBLEM_OUTCOMES: readonly string[] = ['FAILED', 'NOT_CONFIGURED', 'MOCKED'];

/** i18n keys (deduplicated) for every message that did NOT reach the person. */
export function deliveryWarningKeys(
  report: NotificationDeliveryStatus[] | null | undefined,
): string[] {
  if (!report?.length) return [];
  const keys = new Set<string>();
  for (const r of report) {
    if (!PROBLEM_OUTCOMES.includes(r.outcome)) continue;
    // Purpose-specific wording: a failed WELCOME mail next to a delivered
    // activation email must not read as "activation email failed".
    if (r.purpose === 'WELCOME') {
      keys.add('DELIVERY.WELCOME_EMAIL_FAILED');
    } else if (r.purpose === 'CREDENTIALS') {
      keys.add('DELIVERY.CREDENTIALS_NOT_DELIVERED');
    } else if (r.channel === 'EMAIL') {
      keys.add(r.outcome === 'FAILED' ? 'DELIVERY.EMAIL_FAILED' : 'DELIVERY.EMAIL_NOT_CONFIGURED');
    } else {
      keys.add(r.outcome === 'FAILED' ? 'DELIVERY.SMS_FAILED' : 'DELIVERY.SMS_NOT_CONFIGURED');
    }
  }
  return [...keys];
}

/**
 * True only when the report proves an ACTIVATION message actually went out on
 * the given channel (any channel when omitted). An empty report means nothing
 * was attempted — success toasts must not treat that as delivered.
 */
export function hasActivationSent(
  report: NotificationDeliveryStatus[] | null | undefined,
  channel?: 'EMAIL' | 'SMS',
): boolean {
  return !!report?.some(
    (r) =>
      r.outcome === 'SENT' &&
      (r.purpose ?? 'ACTIVATION') === 'ACTIVATION' &&
      (!channel || r.channel === channel),
  );
}
