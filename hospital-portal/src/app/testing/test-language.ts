import { resetStoredLangForTests, SupportedLang } from '../shared/i18n/app-locale';

/** Keep persisted/cached app language in step with a fixture's translated strings. */
export function useTestLanguage(language: SupportedLang): () => void {
  const previous = localStorage.getItem('lang');
  localStorage.setItem('lang', language);
  resetStoredLangForTests();
  return () => {
    if (previous === null) localStorage.removeItem('lang');
    else localStorage.setItem('lang', previous);
    resetStoredLangForTests();
  };
}
