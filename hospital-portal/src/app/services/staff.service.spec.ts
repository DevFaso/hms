import { isClinicalProvider, isPhysician } from './staff.service';

describe('staff provider predicates', () => {
  it('admits only physicians as admitting or discharging provider (QA D176)', () => {
    expect(isPhysician({ roleCode: 'ROLE_DOCTOR' })).toBeTrue();
    expect(isPhysician({ roleCode: 'ROLE_SURGEON' })).toBeTrue();
    expect(isPhysician({ roleCode: 'PHYSICIAN' })).toBeTrue();
    expect(isPhysician({ roleCode: 'ROLE_NURSE' })).toBeFalse();
    expect(isPhysician({ roleCode: 'ROLE_MIDWIFE' })).toBeFalse();
    expect(isPhysician({ roleCode: undefined })).toBeFalse();
  });

  it('still counts nurses and midwives as clinical providers elsewhere', () => {
    expect(isClinicalProvider({ roleCode: 'ROLE_NURSE' })).toBeTrue();
    expect(isClinicalProvider({ roleCode: 'ROLE_MIDWIFE' })).toBeTrue();
  });
});
