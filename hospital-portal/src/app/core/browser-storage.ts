/**
 * Where the portal keeps state in the browser, in one place.
 *
 * - `localStorage` outlives the tab and the sign-out, and is shared by every
 *   person who uses this browser: it holds device preferences only (the
 *   language, the side-nav layout, a list filter, the username a person asked
 *   the sign-in page to remember). Never a token, a profile or clinical text.
 * - `sessionStorage` lives with the tab: it holds the signed-in user's session
 *   (the access token and profile, so a reload keeps the session) and the
 *   user's drafts. Every key of it the portal writes is listed below, and
 *   {@link clearSessionScopedStorage} removes them all at sign-out, at the end
 *   of the session and when another account takes over the tab.
 *
 * `npm run guards` (scripts/check-browser-storage.mjs) refuses a
 * `localStorage` write whose key is not in {@link LOCAL_STORAGE_ALLOWLIST} and
 * a `sessionStorage` write whose key is not in {@link SESSION_SCOPED_KEYS}.
 */

/** The only `localStorage` keys the portal writes: device preferences, never user data. */
export const LOCAL_STORAGE_ALLOWLIST: readonly string[] = [
  'lang',
  'hms.nav.order',
  'hms.sidebar.width',
  'hms.pharmacy.queueClaimFilter',
  'super_admin_audit_saved_searches_migrated',
  'hms.login.rememberedUsername',
];

/** The username the sign-in page fills in, when its owner ticked « remember my username ». */
export const REMEMBERED_USERNAME_KEY = 'hms.login.rememberedUsername';

/**
 * `localStorage` keys earlier builds wrote (the session and the patient's
 * personal notes); removed at start-up and at every sign-out.
 */
export const LEGACY_LOCAL_STORAGE_KEYS: readonly string[] = [
  'auth_token',
  'auth_refresh_token',
  'user_profile',
];
export const LEGACY_LOCAL_STORAGE_PREFIXES: readonly string[] = ['portal-notes-'];

/** Every `sessionStorage` key the portal writes; all belong to the signed-in user. */
export const SESSION_SCOPED_KEYS: readonly string[] = [
  'auth_token',
  'auth_refresh_token',
  'user_profile',
  'hms_idle_locked',
  'hms_lock_ts',
  'hms-chunk-reload',
  'hms.heldRoles',
  'auth_token_pre_impersonation',
  'auth_remember_pre_impersonation',
  'auth_profile_pre_impersonation',
];

/** The patient's personal history notes: `hms.patientNotes.<userId>.<section>`. */
export const PATIENT_NOTE_PREFIX = 'hms.patientNotes.';
export const SESSION_SCOPED_PREFIXES: readonly string[] = [PATIENT_NOTE_PREFIX];

function keysWithPrefix(storage: Storage, prefixes: readonly string[]): string[] {
  const keys: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key && prefixes.some((prefix) => key.startsWith(prefix))) keys.push(key);
  }
  return keys;
}

function removeAll(storage: Storage, keys: readonly string[], prefixes: readonly string[]): void {
  for (const key of [...keys, ...keysWithPrefix(storage, prefixes)]) storage.removeItem(key);
}

/** Removes what earlier builds left in `localStorage` (session tokens, profile, notes). */
export function purgeLegacyBrowserStorage(): void {
  try {
    removeAll(localStorage, LEGACY_LOCAL_STORAGE_KEYS, LEGACY_LOCAL_STORAGE_PREFIXES);
  } catch {
    /* storage not available */
  }
}

/**
 * Removes everything the signed-in user left in the browser: every
 * session-scoped `sessionStorage` key and the legacy `localStorage` ones.
 * Device preferences ({@link LOCAL_STORAGE_ALLOWLIST}) stay.
 */
export function clearSessionScopedStorage(): void {
  try {
    removeAll(sessionStorage, SESSION_SCOPED_KEYS, SESSION_SCOPED_PREFIXES);
  } catch {
    /* storage not available */
  }
  purgeLegacyBrowserStorage();
}

/**
 * Removes the user's own drafts (not the session itself): run when another
 * account takes over the tab, so nothing of the previous one is shown.
 */
export function clearUserDrafts(): void {
  try {
    removeAll(sessionStorage, [], SESSION_SCOPED_PREFIXES);
  } catch {
    /* storage not available */
  }
}

function patientNoteKey(userId: string, section: string): string {
  return `${PATIENT_NOTE_PREFIX}${userId}.${section}`;
}

/** The patient's note for one history section, kept for this tab's session. */
export function readPatientNote(userId: string | null, section: string): string {
  if (!userId) return '';
  try {
    return sessionStorage.getItem(patientNoteKey(userId, section)) ?? '';
  } catch {
    return '';
  }
}

/** Keeps (or, when empty, drops) the patient's note for this tab's session. */
export function writePatientNote(userId: string | null, section: string, text: string): void {
  if (!userId) return;
  try {
    if (text) sessionStorage.setItem(patientNoteKey(userId, section), text);
    else sessionStorage.removeItem(patientNoteKey(userId, section));
  } catch {
    /* storage not available */
  }
}

/** The username the sign-in page should fill in, if its owner asked for it. */
export function readRememberedUsername(): string {
  try {
    return localStorage.getItem(REMEMBERED_USERNAME_KEY) ?? '';
  } catch {
    return '';
  }
}

/** Remembers the username for the next sign-in, or forgets it. */
export function rememberUsername(username: string | null): void {
  try {
    if (username) localStorage.setItem(REMEMBERED_USERNAME_KEY, username);
    else localStorage.removeItem(REMEMBERED_USERNAME_KEY);
  } catch {
    /* storage not available */
  }
}
