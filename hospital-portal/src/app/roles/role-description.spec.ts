import en from '../../assets/i18n/en.json';
import fr from '../../assets/i18n/fr.json';
import es from '../../assets/i18n/es.json';
import { SEEDED_ROLE_DESCRIPTIONS, roleDescriptionKey } from './role-description';

/** QA D434: the seeded role descriptions were shown in English whatever the language. */
describe('roleDescriptionKey (QA D434)', () => {
  const doctor = SEEDED_ROLE_DESCRIPTIONS['ROLE_DOCTOR'];

  it('translates a system role whose description is still the seeded text', () => {
    expect(roleDescriptionKey({ code: 'ROLE_DOCTOR', description: doctor })).toBe(
      'ROLES.SYSTEM_DESC.DOCTOR',
    );
    // Some rows carry the token on `authority` only.
    expect(roleDescriptionKey({ authority: 'ROLE_DOCTOR', description: doctor })).toBe(
      'ROLES.SYSTEM_DESC.DOCTOR',
    );
  });

  it('shows a description an administrator wrote as typed', () => {
    expect(
      roleDescriptionKey({ code: 'ROLE_DOCTOR', description: 'Médecin du service' }),
    ).toBeNull();
    expect(
      roleDescriptionKey({ code: 'ROLE_CHEF_SERVICE', description: 'Chef de service' }),
    ).toBeNull();
  });

  it('has a French and a Spanish translation for every seeded description', () => {
    const bundles = { en, fr, es } as unknown as Record<
      string,
      { ROLES: { SYSTEM_DESC: Record<string, string> } }
    >;
    for (const [code, seeded] of Object.entries(SEEDED_ROLE_DESCRIPTIONS)) {
      const key = code.replace(/^ROLE_/, '');
      expect(bundles['en'].ROLES.SYSTEM_DESC[key]).withContext(code).toBe(seeded);
      for (const lang of ['fr', 'es']) {
        const text = bundles[lang].ROLES.SYSTEM_DESC[key];
        expect(text).withContext(`${lang} ${code}`).toBeTruthy();
        expect(text).withContext(`${lang} ${code}`).not.toBe(seeded);
      }
    }
  });
});
