/**
 * The one place the UI language and the Intl/Angular locale are decided.
 *
 * ngx-translate owns the *strings*; Angular's `LOCALE_ID` and the `Intl` APIs
 * own dates, times, numbers and currency. Until now only the first was set —
 * `defaultLanguage: 'fr'` — while the second was left at Angular's built-in
 * `en-US`, so 304 `| date` pipes and 40 `| number` pipes rendered
 * "Sep 13, 2026" and "1,234.5" on an otherwise French screen, and a dozen
 * components hard-coded `toLocaleDateString('en-US', …)` on top.
 *
 * `LOCALE_ID` is read once at bootstrap, so a language switch must reload the
 * page for the pipes to follow — see {@link applyLanguage}.
 */
export const DEFAULT_LANG = 'fr';
export const SUPPORTED_LANGS = ['fr', 'en', 'es'] as const;
export type SupportedLang = (typeof SUPPORTED_LANGS)[number];

const STORAGE_KEY = 'lang';

/** BCP-47 tags for `Intl` and `LOCALE_ID`. French is the Burkina Faso register. */
const BCP47: Record<SupportedLang, string> = { fr: 'fr-FR', en: 'en-US', es: 'es-ES' };

export function isSupportedLang(value: unknown): value is SupportedLang {
  return typeof value === 'string' && (SUPPORTED_LANGS as readonly string[]).includes(value);
}

/** The persisted choice, or the French default when none is stored or storage throws. */
export function storedLang(): SupportedLang {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return isSupportedLang(raw) ? raw : DEFAULT_LANG;
  } catch {
    return DEFAULT_LANG;
  }
}

/** BCP-47 locale for the active language, for `Intl.*` and `toLocale*` calls. */
export function currentLocale(): string {
  return BCP47[storedLang()];
}

/** Locale for a given language code; unknown codes fall back to the default. */
export function localeFor(lang: string | null | undefined): string {
  return BCP47[isSupportedLang(lang) ? lang : DEFAULT_LANG];
}

/**
 * Persist a language choice and mark the document. Returns true when the
 * language actually changed, in which case the caller should reload so the
 * `LOCALE_ID`-bound pipes pick it up; ngx-translate alone would leave dates
 * in the previous language until the next visit.
 */
export function applyLanguage(lang: SupportedLang, doc: Document = document): boolean {
  const changed = storedLang() !== lang;
  try {
    localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    // Privacy modes can refuse storage; the choice still applies for the session.
  }
  doc.documentElement.lang = lang;
  return changed;
}
