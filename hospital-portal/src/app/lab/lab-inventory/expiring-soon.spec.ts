import { EXPIRING_SOON_DAYS, isExpiringSoon } from './expiring-soon';

describe('isExpiringSoon (D4154)', () => {
  const now = new Date(2026, 10, 27, 9, 30);

  it('flags a lot that expires in 13 days', () => {
    expect(isExpiringSoon('2026-12-10', false, now)).toBeTrue();
  });

  it('flags the last day of the window and today, not the day after the window', () => {
    expect(isExpiringSoon('2026-12-27', false, now)).toBeTrue();
    expect(EXPIRING_SOON_DAYS).toBe(30);
    expect(isExpiringSoon('2026-12-28', false, now)).toBeFalse();
    expect(isExpiringSoon('2026-11-27', false, now)).toBeTrue();
  });

  it('leaves expired, far-off and undated lots unflagged', () => {
    expect(isExpiringSoon('2026-12-10', true, now)).toBeFalse();
    expect(isExpiringSoon('2026-11-26', false, now)).toBeFalse();
    expect(isExpiringSoon('2027-06-01', false, now)).toBeFalse();
    expect(isExpiringSoon(null, false, now)).toBeFalse();
    expect(isExpiringSoon('garbage', false, now)).toBeFalse();
  });
});
