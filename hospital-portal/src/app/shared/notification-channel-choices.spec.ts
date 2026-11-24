import { changedChannelChoices } from './notification-channel-choices';

describe('notification choice deltas', () => {
  it('does not resend unchanged switches from a stale matrix', () => {
    const saved = [
      { notificationType: 'BILLING', channel: 'EMAIL', enabled: true },
      { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: true },
    ];
    expect(changedChannelChoices([saved[0], { ...saved[1], enabled: false }], saved)).toEqual([
      { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: false },
    ]);
  });

  it('keeps the implicit-on default and never forwards server recorder fields', () => {
    const current = [
      { notificationType: 'BILLING', channel: 'EMAIL', enabled: true },
      {
        notificationType: 'LAB_RESULT',
        channel: 'SMS',
        enabled: false,
        recordedByUserId: 'server-author',
      },
    ];
    expect(changedChannelChoices(current, [])).toEqual([
      { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: false },
    ]);
    expect(changedChannelChoices(current, current)).toEqual([]);
  });
});
