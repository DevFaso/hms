import {
  announceHypertensionWarning,
  announceMaternalWarning,
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

    expect(announceNewbornWarning(alerts, toast, translate, null)).toBeTrue();
    expect(pushes).toEqual([
      [
        'warning',
        '[VITAL_LIMITS.NEWBORN_WARNING_URGENT] Recheck now. Outside the range.',
        NEWBORN_WARNING_MS,
      ],
    ]);
    expect(announceNewbornWarning(undefined, toast, translate, null)).toBeFalse();
    expect(pushes.length).toBe(1);
  });
});

/** QA D1890: a mother's severe blood pressure after birth; the rule is the server's, never the portal's. */
describe('postpartum vital alerts', () => {
  const translate = { instant: (key: string) => `[${key}]` };

  it('says nothing when the set raised nothing', () => {
    const toast = { push: jasmine.createSpy('push') };
    expect(announcePostpartumWarning(undefined, toast, translate, null)).toBeFalse();
    expect(announcePostpartumWarning([], toast, translate, null)).toBeFalse();
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
      null,
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
    expect(announceHypertensionWarning(undefined, toast, translate, null)).toBeFalse();
    expect(announceHypertensionWarning([], toast, translate, null)).toBeFalse();
    expect(toast.push).not.toHaveBeenCalled();
  });

  it('shows a long-lived warning toast asking for a recheck', () => {
    const toast = { push: jasmine.createSpy('push') };
    expect(
      announceHypertensionWarning(
        [alert('WARNING', 'Recontrôler la tension.')],
        toast,
        translate,
        null,
      ),
    ).toBeTrue();
    expect(toast.push).toHaveBeenCalledOnceWith(
      'warning',
      '[VITAL_LIMITS.ADULT_HTN_WARNING] Recontrôler la tension.',
      NEWBORN_WARNING_MS,
    );
  });

  it('shows an urgent error toast when a sign of organ damage was ticked', () => {
    const toast = { push: jasmine.createSpy('push') };
    announceHypertensionWarning(
      [alert('URGENT', 'Avis médical urgent requis.')],
      toast,
      translate,
      null,
    );
    expect(toast.push).toHaveBeenCalledOnceWith(
      'error',
      '[VITAL_LIMITS.ADULT_HTN_URGENT] Avis médical urgent requis.',
      NEWBORN_WARNING_MS,
    );
  });
});

/** QA D2585 (r65): a lingering warning names its patient, so it never reads as the next one's. */
describe('vital-sign warnings name their patient', () => {
  const translate = {
    instant: (key: string, params?: Record<string, unknown>) =>
      params ? `[${key}:${String(params['patient'])}]` : `[${key}]`,
  };
  const alert = (severity: 'URGENT' | 'WARNING'): NewbornVitalAlert => ({
    code: 'c',
    severity,
    type: 'HYPERTENSION',
    message: 'Recontrôler.',
  });

  it('starts every announcement with the patient name', () => {
    const toast = { push: jasmine.createSpy('push') };
    announceHypertensionWarning([alert('URGENT')], toast, translate, 'Salimata TestTension');
    announcePostpartumWarning([alert('URGENT')], toast, translate, 'Patient Test 04');
    announceNewbornWarning([alert('WARNING')], toast, translate, 'Bébé Test 04');
    const messages = toast.push.calls.allArgs().map((args) => String(args[1]));
    expect(messages[0]).toMatch(/^\[VITAL_LIMITS\.FOR_PATIENT:Salimata TestTension\] /);
    expect(messages[1]).toMatch(/^\[VITAL_LIMITS\.FOR_PATIENT:Patient Test 04\] /);
    expect(messages[2]).toMatch(/^\[VITAL_LIMITS\.FOR_PATIENT:Bébé Test 04\] /);
  });

  it('keeps the warning when the name is unknown', () => {
    const toast = { push: jasmine.createSpy('push') };
    announceHypertensionWarning([alert('WARNING')], toast, translate, '  ');
    expect(String(toast.push.calls.mostRecent().args[1])).toBe(
      '[VITAL_LIMITS.ADULT_HTN_WARNING] Recontrôler.',
    );
  });
});

/** Policy 2026-11-20 (provisional): the maternal early warning; the score is the server's, never the portal's. */
describe('maternal early warning alerts', () => {
  const translate = { instant: (key: string) => `[${key}]` };
  const level = (severity: NewbornVitalAlert['severity']): NewbornVitalAlert => ({
    code: 'mews-low-medium',
    severity,
    type: 'MATERNAL_EARLY_WARNING',
    message: 'Score 2, revue sous 30 min.',
  });
  const shock: NewbornVitalAlert = {
    code: 'maternal-shock',
    severity: 'WARNING',
    type: 'MATERNAL_EARLY_WARNING',
    message: 'Indice de choc en zone jaune.',
  };

  it('says nothing when the set raised nothing', () => {
    const toast = { push: jasmine.createSpy('push') };
    expect(announceMaternalWarning(undefined, toast, translate, null)).toBeFalse();
    expect(announceMaternalWarning([], toast, translate, null)).toBeFalse();
    expect(toast.push).not.toHaveBeenCalled();
  });

  it('a level of concern says the team was paged, with the provisional heading', () => {
    const toast = { push: jasmine.createSpy('push') };
    expect(announceMaternalWarning([level('WARNING'), shock], toast, translate, null)).toBeTrue();
    expect(toast.push).toHaveBeenCalledOnceWith(
      'warning',
      '[VITAL_LIMITS.MATERNAL_EWS_PAGED] Score 2, revue sous 30 min. Indice de choc en zone jaune.',
      NEWBORN_WARNING_MS,
    );
  });

  it('a high level or a red alert is an error toast', () => {
    const toast = { push: jasmine.createSpy('push') };
    announceMaternalWarning([level('URGENT')], toast, translate, 'Awa Test');
    expect(toast.push.calls.mostRecent().args[0]).toBe('error');
    expect(String(toast.push.calls.mostRecent().args[1])).toContain('MATERNAL_EWS_PAGED');
  });

  it("a withheld page never says the team was told: the heading is the neutral one, the sentence is the server's", () => {
    const toast = { push: jasmine.createSpy('push') };
    const withheld: NewbornVitalAlert = {
      code: 'mews-high-withheld',
      severity: 'URGENT',
      type: 'MATERNAL_EARLY_WARNING',
      message: 'Équipe déjà prévenue il y a moins de 30 min ; aucun nouvel appel.',
    };
    announceMaternalWarning([withheld], toast, translate, null);
    expect(toast.push).toHaveBeenCalledOnceWith(
      'error',
      '[VITAL_LIMITS.MATERNAL_EWS_WARNING] Équipe déjà prévenue il y a moins de 30 min ; aucun nouvel appel.',
      NEWBORN_WARNING_MS,
    );
    expect(String(toast.push.calls.mostRecent().args[1])).not.toContain('MATERNAL_EWS_PAGED');
  });

  it('a page that reached nobody never says the team was told either', () => {
    const toast = { push: jasmine.createSpy('push') };
    announceMaternalWarning(
      [
        {
          ...level('WARNING'),
          code: 'mews-low-medium-unsent',
          message: "Personne n'a pu être prévenu.",
        },
      ],
      toast,
      translate,
      null,
    );
    expect(String(toast.push.calls.mostRecent().args[1])).toBe(
      "[VITAL_LIMITS.MATERNAL_EWS_WARNING] Personne n'a pu être prévenu.",
    );
  });

  it('a yellow shock index alone warns without saying anyone was paged', () => {
    const toast = { push: jasmine.createSpy('push') };
    announceMaternalWarning([shock], toast, translate, null);
    expect(toast.push).toHaveBeenCalledOnceWith(
      'warning',
      '[VITAL_LIMITS.MATERNAL_EWS_WARNING] Indice de choc en zone jaune.',
      NEWBORN_WARNING_MS,
    );
  });
});

/** V356: a warning's « provisoire » sentence follows this hospital's sign-off of its rule. */
describe('vital warnings and the hospital rule status', () => {
  const translate = { instant: (key: string) => `[${key}]` };
  const urgent: NewbornVitalAlert[] = [
    { code: 'x', severity: 'URGENT', type: 'BP', message: 'Recheck now.' },
  ];

  it('keeps the fixed heading while the status is unknown, and uses the status once known', () => {
    const pushes: string[] = [];
    const toast = { push: (_type: 'warning' | 'error', message: string) => pushes.push(message) };
    const unknown = () => null;
    const signed = (key: string) =>
      key === 'SEVERE_HYPERTENSION_ADULT' ? 'Validée le 20 nov. 2026 par Médecin-chef' : null;

    announceHypertensionWarning(urgent, toast, translate, null, unknown);
    announceHypertensionWarning(urgent, toast, translate, null, signed);
    announcePostpartumWarning(urgent, toast, translate, null, signed);
    announceMaternalWarning(urgent, toast, translate, null, (key) =>
      key === 'MATERNAL_EARLY_WARNING' ? 'Provisoire — source : NHS' : null,
    );
    announceNewbornWarning(urgent, toast, translate, null, (key) =>
      key === 'NEWBORN_VITALS' ? 'Validée le 1 déc. 2026 par Pédiatre' : null,
    );

    expect(pushes).toEqual([
      '[VITAL_LIMITS.ADULT_HTN_URGENT] Recheck now.',
      '[CLINICAL_RULES.SURFACE.VITALS_ADULT_HTN_URGENT] Validée le 20 nov. 2026 par Médecin-chef Recheck now.',
      '[VITAL_LIMITS.POSTPARTUM_WARNING_URGENT] Recheck now.',
      '[CLINICAL_RULES.SURFACE.VITALS_MATERNAL_EWS] Provisoire — source : NHS Recheck now.',
      '[CLINICAL_RULES.SURFACE.VITALS_NEWBORN_URGENT] Validée le 1 déc. 2026 par Pédiatre Recheck now.',
    ]);
  });
});
