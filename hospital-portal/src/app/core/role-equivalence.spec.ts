import { expandRoleEquivalents, roleSatisfies } from './role-equivalence';

// Role audit decision C2: physicians and surgeons ARE doctors — one
// equivalence rule instead of three role names in every guard and gate.
describe('role equivalence (C2)', () => {
  it('physician and surgeon satisfy ROLE_DOCTOR requirements', () => {
    expect(roleSatisfies(['ROLE_DOCTOR'], 'ROLE_PHYSICIAN')).toBeTrue();
    expect(roleSatisfies(['ROLE_DOCTOR'], 'ROLE_SURGEON')).toBeTrue();
    expect(roleSatisfies(['ROLE_DOCTOR'], 'ROLE_NURSE')).toBeFalse();
    expect(roleSatisfies(['ROLE_NURSE'], 'ROLE_PHYSICIAN')).toBeFalse();
  });

  it('an exact match still passes and null never does', () => {
    expect(roleSatisfies(['ROLE_PHYSICIAN'], 'ROLE_PHYSICIAN')).toBeTrue();
    expect(roleSatisfies(['ROLE_DOCTOR'], null)).toBeFalse();
  });

  it('expands held roles with ROLE_DOCTOR without duplicating it', () => {
    expect(expandRoleEquivalents(['ROLE_PHYSICIAN'])).toContain('ROLE_DOCTOR');
    expect(expandRoleEquivalents(['ROLE_SURGEON'])).toContain('ROLE_DOCTOR');
    expect(expandRoleEquivalents(['ROLE_NURSE'])).toEqual(['ROLE_NURSE']);
    const alreadyDoctor = expandRoleEquivalents(['ROLE_PHYSICIAN', 'ROLE_DOCTOR']);
    expect(alreadyDoctor.filter((r) => r === 'ROLE_DOCTOR').length).toBe(1);
  });
});
