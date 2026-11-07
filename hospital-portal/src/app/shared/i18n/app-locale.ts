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

/**
 * A wall-clock "HH:mm[:ss]" time in the active locale: "08:30" in French and
 * Spanish, "8:30 AM" in English (QA D348). Unparseable input is returned as is.
 */
export function formatClockTime(time: string, locale: string = currentLocale()): string {
  const [h, m] = time.split(':').map(Number);
  if (!Number.isFinite(h) || !Number.isFinite(m)) return time;
  // English keeps its 12-hour "8:30 AM"; French and Spanish read "08:30".
  const hour = locale.startsWith('en') ? 'numeric' : '2-digit';
  return new Date(2000, 0, 1, h, m).toLocaleTimeString(locale, { hour, minute: '2-digit' });
}

/**
 * A number with exactly `digits` decimals in the active locale's
 * notation: 1.235 reads "1,235" in French and Spanish, "1.235" in English
 * (QA D434, the QC chart labels used toFixed and always printed a dot).
 */
export function formatDecimal(
  value: number,
  digits: number,
  locale: string = currentLocale(),
): string {
  return value.toLocaleString(locale, {
    minimumFractionDigits: digits,
    maximumFractionDigits: digits,
    useGrouping: false,
  });
}

/**
 * Decimal numbers inside a display string written with the locale's
 * separator: "37.9" reads "37,9" in French and Spanish (QA D344). Only a dot
 * between two digits is touched, so "mg/dL", dates and ids are left alone.
 */
export function localizeDecimals(text: string, locale: string = currentLocale()): string {
  const separator = (1.5).toLocaleString(locale).charAt(1);
  return separator === '.' ? text : text.replaceAll(/(\d)\.(\d)/g, `$1${separator}$2`);
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

/**
 * QA D621: an amount in francs CFA, read the same way in every language:
 * "4 000 F CFA" in French, "4,000 F CFA" in English, "4.000 F CFA" in Spanish.
 * `Intl`'s own XOF currency printed "XOF" in Spanish and "F CFA4,000" in
 * English. Another currency keeps `Intl`'s formatting.
 */
export function formatCfa(
  amount: number | null | undefined,
  currency: string | null | undefined = 'XOF',
  locale: string = currentLocale(),
): string {
  const value = Number(amount ?? 0);
  const code = (currency || 'XOF').toUpperCase();
  if (code !== 'XOF') {
    return new Intl.NumberFormat(locale, {
      style: 'currency',
      currency: code,
      maximumFractionDigits: 0,
    }).format(value);
  }
  // QA D1008: Spanish groups only from five digits by default ("8225" next to
  // "19.875"); an explicit true means "always" (ECMA-402 NumberFormat v3).
  const digits = new Intl.NumberFormat(locale, {
    maximumFractionDigits: 0,
    useGrouping: true,
  }).format(value);
  return `${digits}\u00a0F\u00a0CFA`;
}
