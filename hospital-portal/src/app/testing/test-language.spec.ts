import { resetStoredLangForTests, storedLang } from '../shared/i18n/app-locale';
import { useTestLanguage } from './test-language';

describe('test language isolation', () => {
  let restore: () => void;

  beforeEach(() => (restore = useTestLanguage('fr')));
  afterEach(() => restore());

  it('a French fixture replaces a previously cached Spanish preference, then restores it', () => {
    localStorage.setItem('lang', 'es');
    resetStoredLangForTests();
    expect(storedLang()).toBe('es');
    const finishFixture = useTestLanguage('fr');
    expect(storedLang()).toBe('fr');
    finishFixture();
    expect(localStorage.getItem('lang')).toBe('es');
    expect(storedLang()).toBe('es');
  });

  it('restores absent storage and clears a cached preference on teardown', () => {
    localStorage.removeItem('lang');
    resetStoredLangForTests();
    const finishFixture = useTestLanguage('es');
    expect(storedLang()).toBe('es');
    finishFixture();
    expect(localStorage.getItem('lang')).toBeNull();
    expect(storedLang()).toBe('fr');
  });
});
