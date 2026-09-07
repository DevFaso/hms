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
  // Tracked separately from `keys`: a CREDENTIALS or WELCOME problem says
  // nothing about whether the activation itself has a route, and using an
  // empty key set as the trigger let exactly those keys mask the case below.
  let activationProblemReported = false;
  for (const r of report) {
    if (!PROBLEM_OUTCOMES.includes(r.outcome)) continue;
    if ((r.purpose ?? 'ACTIVATION') === 'ACTIVATION') activationProblemReported = true;
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
  // A report made entirely of NO_CONTACT rows produces no key above — each
  // row is individually unremarkable ("this patient has no email") while
  // together they mean the account has no way in at all: created inactive,
  // confirmation code sitting in the database. Warn whenever nothing proves
  // an activation went out and nothing already said why.
  if (!activationProblemReported && !hasActivationSent(report)) {
    keys.add('DELIVERY.NO_ACTIVATION_CHANNEL');
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
