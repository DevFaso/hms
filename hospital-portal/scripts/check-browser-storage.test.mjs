import { test } from 'node:test';
import assert from 'node:assert/strict';

import { componentSources } from './lib/component-sources.mjs';
import { loadRegistry, storageFindings } from './check-browser-storage.mjs';

/*
 * Tokens, the user's profile and clinical text never go to web storage: they
 * live in memory, and a reload gets the session back from the refresh cookie.
 * localStorage holds device preferences only; every sessionStorage key is one
 * the sign-out clears. The lists live in src/app/core/browser-storage.ts.
 */

const registry = loadRegistry();
const scan = (text, file = 'src/app/x/x.ts') => storageFindings([{ file, text }], registry);

test('the portal writes browser storage only under registered keys', () => {
  assert.deepEqual(storageFindings(componentSources(), registry), []);
});

test('no token, profile or note key is allowed in either storage', () => {
  for (const key of [
    'auth_token',
    'auth_refresh_token',
    'user_profile',
    'auth_token_pre_impersonation',
    'auth_profile_pre_impersonation',
  ]) {
    assert.ok(!registry.local.has(key), `${key} must not be allowed in localStorage`);
    assert.ok(!registry.session.has(key), `${key} must not be allowed in sessionStorage`);
  }
  for (const key of [...registry.local, ...registry.session]) {
    assert.doesNotMatch(key, /token|profile|note/i, `${key} looks like a credential or user data`);
  }
});

test('a token, a profile or a note written to localStorage is refused', () => {
  assert.equal(scan("localStorage.setItem('auth_token', token);").length, 1);
  assert.equal(
    scan("const PROFILE_KEY = 'user_profile';\nlocalStorage.setItem(PROFILE_KEY, json);").length,
    1,
  );
  assert.equal(
    scan("private readonly NOTES = 'portal-notes-';\nlocalStorage.setItem(this.NOTES + s, v);")
      .length,
    1,
  );
  assert.equal(scan('globalThis.localStorage?.setItem(`notes-${id}`, v);').length, 1);
  assert.equal(scan("localStorage['auth_token'] = token;").length, 1);
  assert.equal(scan('window.localStorage.auth_token = token;').length, 1);
});

test('a sessionStorage key the sign-out does not clear is refused, and so is the session', () => {
  assert.equal(scan("sessionStorage.setItem('hms.someDraft', v);").length, 1);
  assert.equal(scan("sessionStorage.setItem('auth_token', v);").length, 1);
  assert.equal(
    scan(
      [
        "const ORIGINAL = 'auth_profile_pre_impersonation';",
        'sessionStorage.setItem(ORIGINAL, p);',
      ].join('\n'),
    ).length,
    1,
  );
  assert.equal(scan("sessionStorage.setItem('hms_idle_locked', 'true');").length, 0);
});

test('device preferences and registered constants pass', () => {
  assert.deepEqual(scan("const STORAGE_KEY = 'lang';\nlocalStorage.setItem(STORAGE_KEY, l);"), []);
  assert.deepEqual(
    scan(
      "private static readonly IDLE_KEY = 'hms_idle_locked';\n" +
        "sessionStorage.setItem(IdleService.IDLE_KEY, 'true');",
    ),
    [],
  );
  assert.deepEqual(scan("const v = localStorage.getItem('auth_token');"), []);
  assert.deepEqual(scan('if (localStorage.length === 0) {}'), []);
});

test('the registry module itself may build keys at run time', () => {
  assert.deepEqual(
    scan('sessionStorage.setItem(patientNoteKey(id, s), t);', 'src/app/core/browser-storage.ts'),
    [],
  );
});
