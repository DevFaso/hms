import { isClinicalProvider } from './staff.service';

describe('isClinicalProvider (QA D28, D56)', () => {
  it('keeps doctors, physicians, surgeons, nurses and midwives, with or without ROLE_', () => {
    for (const code of [
      'ROLE_DOCTOR',
      'DOCTOR',
      'ROLE_PHYSICIAN',
      'ROLE_SURGEON',
      'ROLE_NURSE',
      'ROLE_MIDWIFE',
    ]) {
      expect(isClinicalProvider({ roleCode: code }))
        .withContext(code)
        .toBeTrue();
    }
  });

  it('drops admins, receptionists and staff with no role', () => {
    for (const code of ['ROLE_HOSPITAL_ADMIN', 'ROLE_RECEPTIONIST', 'ROLE_PHARMACIST', undefined]) {
      expect(isClinicalProvider({ roleCode: code }))
        .withContext(String(code))
        .toBeFalse();
    }
  });
});
