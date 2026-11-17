import {
  announceHypertensionWarning,
  announceNewbornWarning,
  announcePostpartumWarning,
  NEWBORN_WARNING_MS,
  NewbornVitalAlert,
  newbornVitalWarning,
} from './newborn-vital-alerts';

/** QA D1564: the newborn warning after a vitals save; the bands are the server's, never the portal's. */
describe('newborn vital alerts', () => {
  const alerts: NewbornVitalAlert[] = [
    {
      code: 'newborn-reference-heart-rate',
      severity: 'INFO',
      type: 'CARDIOVASCULAR',
      message: 'Outside the range.',
    },
    {
      code: 'newborn-heart-rate',
      severity: 'URGENT',
      type: 'CARDIOVASCULAR',
      message: 'Recheck now.',
    },
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
    const toast = {
      push: (type: 'warning', message: string, ms?: number) => pushes.push([type, message, ms]),
    };
    const translate = { instant: (key: string) => `[${key}]` };

    expect(announceNewbornWarning(alerts, toast, translate)).toBeTrue();
    expect(pushes).toEqual([
      [
        'warning',
        '[VITAL_LIMITS.NEWBORN_WARNING_URGENT] Recheck now. Outside the range.',
        NEWBORN_WARNING_MS,
      ],
    ]);
    expect(announceNewbornWarning(undefined, toast, translate)).toBeFalse();
    expect(pushes.length).toBe(1);
  });
});

/** QA D1890: a mother's severe blood pressure after birth; the rule is the server's, never the portal's. */
describe('postpartum vital alerts', () => {
  const translate = { instant: (key: string) => `[${key}]` };

  it('says nothing when the set raised nothing', () => {
    const toast = { push: jasmine.createSpy('push') };
    expect(announcePostpartumWarning(undefined, toast, translate)).toBeFalse();
    expect(announcePostpartumWarning([], toast, translate)).toBeFalse();
    expect(toast.push).not.toHaveBeenCalled();
  });

  it('shows one long-lived urgent toast with the postpartum heading and the warning', () => {
    const toast = { push: jasmine.createSpy('push') };
    const shown = announcePostpartumWarning(
      [
        {
          code: 'postpartum-severe-hypertension',
          severity: 'URGENT',
          type: 'HYPERTENSION',
          message: 'Avis obstétrical urgent requis.',
        },
      ],
      toast,
      translate,
    );
    expect(shown).toBeTrue();
    expect(toast.push).toHaveBeenCalledOnceWith(
      'error',
      '[VITAL_LIMITS.POSTPARTUM_WARNING_URGENT] Avis obstétrical urgent requis.',
      NEWBORN_WARNING_MS,
    );
  });
});

/** r54: an adult's severe blood pressure; the rule is the server's, never the portal's. */
describe('adult severe blood pressure alerts', () => {
  const translate = { instant: (key: string) => `[${key}]` };
  const alert = (severity: NewbornVitalAlert['severity'], message: string): NewbornVitalAlert => ({
    code: 'adult-severe-hypertension',
    severity,
    type: 'HYPERTENSION',
    message,
  });

  it('says nothing when the set raised nothing', () => {
    const toast = { push: jasmine.createSpy('push') };
    expect(announceHypertensionWarning(undefined, toast, translate)).toBeFalse();
    expect(announceHypertensionWarning([], toast, translate)).toBeFalse();
    expect(toast.push).not.toHaveBeenCalled();
  });

  it('shows a long-lived warning toast asking for a recheck', () => {
    const toast = { push: jasmine.createSpy('push') };
    expect(
      announceHypertensionWarning([alert('WARNING', 'Recontrôler la tension.')], toast, translate),
    ).toBeTrue();
    expect(toast.push).toHaveBeenCalledOnceWith(
      'warning',
      '[VITAL_LIMITS.ADULT_HTN_WARNING] Recontrôler la tension.',
      NEWBORN_WARNING_MS,
    );
  });

  it('shows an urgent error toast when a sign of organ damage was ticked', () => {
    const toast = { push: jasmine.createSpy('push') };
    announceHypertensionWarning([alert('URGENT', 'Avis médical urgent requis.')], toast, translate);
    expect(toast.push).toHaveBeenCalledOnceWith(
      'error',
      '[VITAL_LIMITS.ADULT_HTN_URGENT] Avis médical urgent requis.',
      NEWBORN_WARNING_MS,
    );
  });
});
