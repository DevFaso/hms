/**
 * Where the portal keeps state in the browser, in one place.
 *
 * - The session itself (access token, user profile) and anything clinical
 *   (the patient's personal notes) live in memory only, never in web storage.
 *   After a reload the session comes back through the refresh cookie
 *   (auth/session-restore.ts); a tab that never signed in does not ask.
 * - `localStorage` outlives the tab and the sign-out, and is shared by every
 *   person who uses this browser: it holds device preferences only (the
 *   language, the side-nav layout, a list filter, the username a person asked
 *   the sign-in page to remember).
 * - `sessionStorage` lives with the tab: it holds UI state of the signed-in
 *   user (the idle lock, the roles the account holds, a reload guard) and the
 *   flag saying this tab has a session to restore. Every key of it the portal
 *   writes is listed below, and {@link clearSessionScopedStorage} removes them
 *   all at sign-out, at the end of the session and when another account takes
 *   over the tab.
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

/** This tab signed in: after a reload it asks the refresh cookie for its session. Holds `'1'`. */
export const TAB_SESSION_KEY = 'hms.session.tab';

/** Every `sessionStorage` key the portal writes; all belong to the signed-in user. */
export const SESSION_SCOPED_KEYS: readonly string[] = [
  'hms.session.tab',
  'hms_idle_locked',
  'hms_lock_ts',
  'hms-chunk-reload',
  'hms.heldRoles',
];

/**
 * Keys earlier builds wrote (the session, the impersonation snapshot and the
 * patient's notes); removed from both storages at start-up and at sign-out.
 */
export const LEGACY_STORAGE_KEYS: readonly string[] = [
  'auth_token',
  'auth_refresh_token',
  'user_profile',
  'auth_token_pre_impersonation',
  'auth_remember_pre_impersonation',
  'auth_profile_pre_impersonation',
];
export const LEGACY_STORAGE_PREFIXES: readonly string[] = ['portal-notes-', 'hms.patientNotes.'];

function removeAll(storage: Storage, keys: readonly string[], prefixes: readonly string[]): void {
  const prefixed: string[] = [];
  for (let i = 0; i < storage.length; i++) {
    const key = storage.key(i);
    if (key && prefixes.some((prefix) => key.startsWith(prefix))) prefixed.push(key);
  }
  for (const key of [...keys, ...prefixed]) storage.removeItem(key);
}

/** Removes what earlier builds left in web storage (session tokens, profile, notes). */
export function purgeLegacyBrowserStorage(): void {
  try {
    removeAll(localStorage, LEGACY_STORAGE_KEYS, LEGACY_STORAGE_PREFIXES);
  } catch {
    /* storage not available */
  }
  try {
    removeAll(sessionStorage, LEGACY_STORAGE_KEYS, LEGACY_STORAGE_PREFIXES);
  } catch {
    /* storage not available */
  }
}

/**
 * Removes everything the signed-in user left in the browser: every
 * session-scoped `sessionStorage` key, the in-memory drafts and the legacy
 * keys. Device preferences ({@link LOCAL_STORAGE_ALLOWLIST}) stay.
 */
export function clearSessionScopedStorage(): void {
  try {
    removeAll(sessionStorage, SESSION_SCOPED_KEYS, []);
  } catch {
    /* storage not available */
  }
  clearUserDrafts();
  purgeLegacyBrowserStorage();
}

/** Marks this tab as signed in (a flag, no credential), so a reload restores the session. */
export function markTabSession(): void {
  try {
    sessionStorage.setItem(TAB_SESSION_KEY, '1');
  } catch {
    /* storage not available: a reload signs in again */
  }
}

export function hasTabSession(): boolean {
  try {
    return sessionStorage.getItem(TAB_SESSION_KEY) === '1';
  } catch {
    return false;
  }
}

export function clearTabSession(): void {
  try {
    sessionStorage.removeItem(TAB_SESSION_KEY);
  } catch {
    /* storage not available */
  }
}

/** The patient's personal notes, per user and section, for as long as the page is open. */
const patientNotes = new Map<string, string>();

/**
 * Removes the user's own drafts (not the session itself): run at sign-out and
 * when another account takes over the tab, so nothing of the previous one is shown.
 */
export function clearUserDrafts(): void {
  patientNotes.clear();
}

function patientNoteKey(userId: string, section: string): string {
  return `${userId}.${section}`;
}

/** The patient's note for one history section, kept in memory for this page. */
export function readPatientNote(userId: string | null, section: string): string {
  if (!userId) return '';
  return patientNotes.get(patientNoteKey(userId, section)) ?? '';
}

/** Keeps (or, when empty, drops) the patient's note for this page. */
export function writePatientNote(userId: string | null, section: string, text: string): void {
  if (!userId) return;
  if (text) patientNotes.set(patientNoteKey(userId, section), text);
  else patientNotes.delete(patientNoteKey(userId, section));
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
