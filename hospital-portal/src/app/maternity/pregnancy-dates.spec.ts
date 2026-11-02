import {
  dueDateFromLmp,
  effectiveDueDate,
  gestationalAge,
  isDelivered,
  ongoingPregnancy,
} from './pregnancy-dates';

describe('pregnancy dates (QA D1005)', () => {
  it('computes the due date the server computes from the LMP (Awa: 2026-05-20 → 2027-02-24)', () => {
    expect(dueDateFromLmp('2026-05-20')).toBe('2027-02-24');
    expect(dueDateFromLmp('')).toBe('');
    expect(dueDateFromLmp(undefined)).toBe('');
  });

  it('prefers the ultrasound date, then the recorded date, then the LMP', () => {
    expect(effectiveDueDate({ lastMenstrualPeriod: '2026-05-20' })).toBe('2027-02-24');
    expect(
      effectiveDueDate({ lastMenstrualPeriod: '2026-05-20', estimatedDueDate: '2027-02-20' }),
    ).toBe('2027-02-20');
    expect(
      effectiveDueDate({
        lastMenstrualPeriod: '2026-05-20',
        estimatedDueDate: '2027-02-20',
        estimatedDueDateByUltrasound: '2027-03-01',
      }),
    ).toBe('2027-03-01');
  });

  it('gives the gestational age in weeks and days, and none once the pregnancy is long over', () => {
    expect(gestationalAge('2027-02-24', new Date(2026, 9, 21))).toEqual({ weeks: 22, days: 0 });
    expect(gestationalAge('2027-02-24', new Date(2026, 9, 27))).toEqual({ weeks: 22, days: 6 });
    expect(gestationalAge('2025-01-01', new Date(2026, 9, 27))).toBeNull();
    expect(gestationalAge(null)).toBeNull();
  });

  it('QA D1067: an ongoing pregnancy is read off the history, and none once she delivered', () => {
    const today = new Date(2026, 9, 30);
    const history = { menstrualHistory: { lastMenstrualPeriod: '2026-01-29' } };
    expect(ongoingPregnancy(history, today)).toEqual({ weeks: 39, days: 1, dueDate: '2026-11-05' });
    expect(ongoingPregnancy({ ...history, deliveredAt: '2026-10-30T17:12:00' }, today)).toBeNull();
    expect(isDelivered({ deliveredAt: '2026-10-30T17:12:00' })).toBeTrue();
    expect(isDelivered({ deliveredAt: null })).toBeFalse();
    expect(ongoingPregnancy(null, today)).toBeNull();
    expect(ongoingPregnancy({}, today)).toBeNull();
  });
});
