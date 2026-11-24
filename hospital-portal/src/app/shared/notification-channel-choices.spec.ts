import { changedChannelChoices, consentRecorderKey } from './notification-channel-choices';

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
        recordedByKind: 'STAFF',
        recordedByName: 'server-author',
      },
    ];
    expect(changedChannelChoices(current, [])).toEqual([
      { notificationType: 'LAB_RESULT', channel: 'SMS', enabled: false },
    ]);
    expect(changedChannelChoices(current, current)).toEqual([]);
  });

  it('QA r73 D3700: a recorder the server did not name gets a neutral label, never an id', () => {
    expect(consentRecorderKey('OTHER_FACILITY')).toBe('PATIENT_CHANNELS.RECORDER_OTHER_FACILITY');
    expect(consentRecorderKey('PATIENT')).toBe('PATIENT_CHANNELS.RECORDER_PATIENT');
    expect(consentRecorderKey('STAFF')).toBeNull();
    expect(consentRecorderKey(null)).toBeNull();
  });
});
