import { isMaternalCandidate } from './maternal-candidate';

describe('isMaternalCandidate (QA r81 D4116)', () => {
  const today = new Date(2026, 10, 27);

  it('does not offer a newborn as the mother', () => {
    expect(isMaternalCandidate({ gender: 'FEMALE', dateOfBirth: '2026-11-27' }, today)).toBe(false);
    expect(isMaternalCandidate({ dateOfBirth: '2026-01-15' }, today)).toBe(false);
  });

  it('does not offer a patient recorded male', () => {
    expect(isMaternalCandidate({ gender: 'MALE', dateOfBirth: '1990-01-01' }, today)).toBe(false);
    expect(isMaternalCandidate({ gender: 'm' }, today)).toBe(false);
  });

  it('offers a woman, and a patient whose sex or birth date is not recorded', () => {
    expect(isMaternalCandidate({ gender: 'FEMALE', dateOfBirth: '2014-03-02' }, today)).toBe(true);
    expect(isMaternalCandidate({}, today)).toBe(true);
    expect(isMaternalCandidate({ gender: 'OTHER', dateOfBirth: '2025-11-27' }, today)).toBe(true);
  });
});
