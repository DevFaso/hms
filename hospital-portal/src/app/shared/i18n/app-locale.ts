import type { TranslateService } from '@ngx-translate/core';

/**
 * The one place the UI language and the Intl/Angular locale are decided.
 *
 * ngx-translate owns the *strings*; Angular's `LOCALE_ID` and the `Intl` APIs
 * own dates, times, numbers and currency. Until now only the first was set —
 * `defaultLanguage: 'fr'` — while the second was left at Angular's built-in
 * `en-US`, so 304 `| date` pipes and 40 `| number` pipes rendered
 * "Sep 13, 2026" and "1,234.5" on an otherwise French screen.
 *
 * The language is read from storage ONCE and cached: `LOCALE_ID` is fixed at
 * bootstrap anyway, so a language change reloads the page (see
 * {@link switchLanguage}), and nothing else can change the value mid-session.
 */
export const DEFAULT_LANG = 'fr';
export const SUPPORTED_LANGS = ['fr', 'en', 'es'] as const;
export type SupportedLang = (typeof SUPPORTED_LANGS)[number];

const STORAGE_KEY = 'lang';

/** BCP-47 tags for `Intl` and `LOCALE_ID`. French is the Burkina Faso register. */
const BCP47: Record<SupportedLang, string> = { fr: 'fr-FR', en: 'en-US', es: 'es-ES' };

let cached: SupportedLang | undefined;

export function isSupportedLang(value: unknown): value is SupportedLang {
  return typeof value === 'string' && (SUPPORTED_LANGS as readonly string[]).includes(value);
}

function readStored(): SupportedLang {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    return isSupportedLang(raw) ? raw : DEFAULT_LANG;
  } catch {
    return DEFAULT_LANG;
  }
}

/** The persisted choice, or the French default when none is stored or storage throws. */
export function storedLang(): SupportedLang {
  cached ??= readStored();
  return cached;
}

/** Locale for a given language code; unknown codes fall back to the default. */
export function localeFor(lang: string | null | undefined): string {
  return BCP47[isSupportedLang(lang) ? lang : DEFAULT_LANG];
}

/** BCP-47 locale for the active language, for `Intl.*` and `toLocale*` calls. */
export function currentLocale(): string {
  return localeFor(storedLang());
}

/** Specs that set `localStorage.lang` between cases must drop the cache. */
export function resetStoredLangForTests(): void {
  cached = undefined;
}

/**
 * Persist a language choice and mark the document. Returns true only when the
 * choice is durably stored AND differs from the previous one — the callers
 * reload on true so the `LOCALE_ID`-bound pipes follow. When storage refuses
 * the write (privacy modes), the language is kept for the session in memory
 * and NO reload is asked for: reloading would read the old value back and
 * snap the UI to French on every attempt.
 */
export function applyLanguage(lang: SupportedLang): boolean {
  if (storedLang() === lang) return false;
  let persisted = true;
  try {
    localStorage.setItem(STORAGE_KEY, lang);
  } catch {
    persisted = false;
  }
  cached = lang;
  document.documentElement.lang = lang;
  return persisted;
}

/**
 * The single language-switch sequence, shared by the shell menu and Settings.
 * Reloading discards unsaved forms, so the user is asked first; a refusal
 * leaves everything as it was.
 */
export function switchLanguage(lang: string, translate: TranslateService): boolean {
  if (!isSupportedLang(lang) || lang === storedLang()) return false;
  if (!window.confirm(translate.instant('COMMON.LANGUAGE_RELOAD_CONFIRM'))) return false;
  translate.use(lang);
  if (applyLanguage(lang)) window.location.reload();
  return true;
}
