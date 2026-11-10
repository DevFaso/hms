import { announceNewbornWarning, NEWBORN_WARNING_MS, NewbornVitalAlert, newbornVitalWarning } from './newborn-vital-alerts';

/** QA D1564: the newborn warning after a vitals save; the bands are the server's, never the portal's. */
describe('newborn vital alerts', () => {
  const alerts: NewbornVitalAlert[] = [
    { code: 'newborn-reference-heart-rate', severity: 'INFO', type: 'CARDIOVASCULAR', message: 'Outside the range.' },
    { code: 'newborn-heart-rate', severity: 'URGENT', type: 'CARDIOVASCULAR', message: 'Recheck now.' },
  ];

  it('says nothing when the set raised nothing', () => {
    expect(newbornVitalWarning(undefined)).toBeNull();
    expect(newbornVitalWarning([])).toBeNull();
  });

  it('puts the urgent finding first and uses the urgent heading', () => {
    const warning = newbornVitalWarning(alerts);
    expect(warning?.headingKey).toBe('VITAL_LIMITS.NEWBORN_WARNING_URGENT');
    expect(warning?.lines).toEqual(['Recheck now.', 'Outside the range.']);
  });

  it('uses the plain heading when nothing is urgent', () => {
    expect(newbornVitalWarning([alerts[0]])?.headingKey).toBe('VITAL_LIMITS.NEWBORN_WARNING');
  });

  it('shows one long-lived warning toast with the heading and the lines', () => {
    const pushes: [string, string, number | undefined][] = [];
    const toast = { push: (type: 'warning', message: string, ms?: number) => pushes.push([type, message, ms]) };
    const translate = { instant: (key: string) => `[${key}]` };

    expect(announceNewbornWarning(alerts, toast, translate)).toBeTrue();
    expect(pushes).toEqual([
      ['warning', '[VITAL_LIMITS.NEWBORN_WARNING_URGENT] Recheck now. Outside the range.', NEWBORN_WARNING_MS],
    ]);
    expect(announceNewbornWarning(undefined, toast, translate)).toBeFalse();
    expect(pushes.length).toBe(1);
  });
});
