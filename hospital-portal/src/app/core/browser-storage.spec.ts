import {
  PortalOAuthStorage,
  SESSION_SCOPED_KEYS,
  clearSessionScopedStorage,
} from './browser-storage';

describe('PortalOAuthStorage — the OIDC library keeps its tokens in memory', () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  it('keeps tokens and claims out of web storage', () => {
    const storage = new PortalOAuthStorage();
    for (const key of ['access_token', 'id_token', 'refresh_token', 'id_token_claims_obj']) {
      storage.setItem(key, `value-of-${key}`);
      expect(storage.getItem(key)).toBe(`value-of-${key}`);
    }
    expect(localStorage.length).toBe(0);
    expect(sessionStorage.length).toBe(0);
    storage.removeItem('access_token');
    expect(storage.getItem('access_token')).toBeNull();
  });

  it('lets only the redirect values survive the round trip, and the sign-out clears them', () => {
    const storage = new PortalOAuthStorage();
    storage.setItem('PKCE_verifier', 'verifier');
    storage.setItem('nonce', 'n-1');
    expect(new PortalOAuthStorage().getItem('PKCE_verifier')).toBe('verifier');
    expect(SESSION_SCOPED_KEYS).toContain('PKCE_verifier');
    expect(SESSION_SCOPED_KEYS).toContain('nonce');

    clearSessionScopedStorage();
    expect(storage.getItem('PKCE_verifier')).toBeNull();
    expect(storage.getItem('nonce')).toBeNull();
  });
});
