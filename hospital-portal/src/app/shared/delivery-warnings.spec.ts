import { deliveryWarningKeys, hasActivationSent } from './delivery-warnings';
import type { NotificationDeliveryStatus } from './delivery-warnings';

describe('deliveryWarningKeys', () => {
  const row = (p: Partial<NotificationDeliveryStatus>): NotificationDeliveryStatus => ({
    channel: 'EMAIL',
    purpose: 'ACTIVATION',
    outcome: 'SENT',
    ...p,
  });

  it('stays quiet when the activation email went out', () => {
    expect(deliveryWarningKeys([row({})])).toEqual([]);
  });

  it('reports a dead SMS transport', () => {
    expect(deliveryWarningKeys([row({ channel: 'SMS', outcome: 'NOT_CONFIGURED' })])).toEqual([
      'DELIVERY.SMS_NOT_CONFIGURED',
    ]);
  });

  it('does not warn about a phone-first patient having no email', () => {
    // EMAIL NO_CONTACT is expected here — the SMS is the activation route.
    expect(
      deliveryWarningKeys([
        row({ channel: 'EMAIL', outcome: 'NO_CONTACT' }),
        row({ channel: 'SMS', outcome: 'SENT' }),
      ]),
    ).toEqual([]);
  });

  it('warns when every channel had no contact — nobody can activate the account', () => {
    expect(
      deliveryWarningKeys([
        row({ channel: 'EMAIL', outcome: 'NO_CONTACT' }),
        row({ channel: 'SMS', outcome: 'NO_CONTACT' }),
      ]),
    ).toEqual(['DELIVERY.NO_ACTIVATION_CHANNEL']);
  });

  it('does not add the no-channel warning on top of a specific one', () => {
    const keys = deliveryWarningKeys([
      row({ channel: 'EMAIL', outcome: 'NO_CONTACT' }),
      row({ channel: 'SMS', outcome: 'MOCKED' }),
    ]);
    expect(keys).toEqual(['DELIVERY.SMS_NOT_CONFIGURED']);
    expect(keys).not.toContain('DELIVERY.NO_ACTIVATION_CHANNEL');
  });

  it('treats an empty report as nothing attempted, not as a failure', () => {
    expect(deliveryWarningKeys([])).toEqual([]);
    expect(deliveryWarningKeys(null)).toEqual([]);
  });

  it('does not count a delivered WELCOME mail as a delivered activation', () => {
    // The welcome mail carries credentials, not the confirmation code.
    const report = [row({ purpose: 'WELCOME', outcome: 'SENT' })];
    expect(hasActivationSent(report)).toBeFalse();
    expect(deliveryWarningKeys(report)).toEqual(['DELIVERY.NO_ACTIVATION_CHANNEL']);
  });
});
