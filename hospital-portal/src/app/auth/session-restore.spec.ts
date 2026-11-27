import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthService } from './auth.service';
import { IdleService } from '../core/idle.service';
import { SESSION_RESTORE_HEADER, restoreSessionOnStartup } from './session-restore';
import { SSO_TAB_KEY, TAB_SESSION_KEY, tabBindingFor } from '../core/browser-storage';

function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (v: string) => btoa(v).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  return `${b64('{"alg":"none"}')}.${b64(JSON.stringify(payload))}.sig`;
}

/** Lets the promise chain of the initializer (digests included) reach its next step. */
const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

describe('restoreSessionOnStartup — the session after a reload comes from the refresh cookie', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  const isRefresh = (url: string) => url.endsWith('/api/auth/token/refresh');
  const tokenFor = (uid: string) =>
    fakeJwt({ uid, sub: 'dr', roles: ['ROLE_DOCTOR'], exp: 9999999999 });
  const bootstrap = {
    userId: 'u1',
    username: 'dr',
    email: '',
    authSource: 'internal',
    roles: ['ROLE_DOCTOR'],
    superAdmin: false,
    hospitalAdmin: false,
    permittedHospitalIds: ['h1'],
    primaryHospitalId: 'h1',
  };

  async function bindTo(uid: string): Promise<void> {
    sessionStorage.setItem(TAB_SESSION_KEY, await tabBindingFor(uid));
  }

  /** Answers the refresh, the credential status and the bootstrap of a successful restore. */
  async function restoreAs(
    uid: string,
    status: { forcePasswordChange?: boolean; forceUsernameChange?: boolean } = {},
  ): Promise<void> {
    const done = run();
    const refresh = http.expectOne((r) => isRefresh(r.url));
    expect(refresh.request.method).toBe('POST');
    expect(refresh.request.withCredentials).toBeTrue();
    expect(refresh.request.headers.get(SESSION_RESTORE_HEADER)).toBe('1');
    refresh.flush({ accessToken: tokenFor(uid) });
    await settle();
    http.expectOne((r) => r.url.endsWith('/auth/credentials/me')).flush(status);
    await settle();
    http.expectOne((r) => r.url.endsWith('auth/session/bootstrap')).flush(bootstrap);
    await done;
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
    auth = TestBed.inject(AuthService);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  const run = () => TestBed.runInInjectionContext(() => restoreSessionOnStartup());

  it('does not ask when this tab never signed in', async () => {
    await run();
    http.expectNone((r) => isRefresh(r.url));
    expect(auth.getToken()).toBeNull();
  });

  it('a tab signed in by SSO is not restored through the native refresh (the identity provider restores it)', async () => {
    await bindTo('u1');
    sessionStorage.setItem(SSO_TAB_KEY, 'bound');

    await run();

    http.expectNone((r) => isRefresh(r.url));
    expect(auth.hasTabSession()).toBeTrue();
  });

  it('restores the token and the profile of the account the tab is bound to', async () => {
    await bindTo('u1');
    await restoreAs('u1');

    expect(auth.getToken()).toBe(tokenFor('u1'));
    expect(auth.getUserProfile()?.id).toBe('u1');
    expect(auth.hasTabSession()).toBeTrue();
  });

  it('a token for another account (signed in from another tab) ends this tab locally', async () => {
    await bindTo('user-a');
    const done = run();
    http.expectOne((r) => isRefresh(r.url)).flush({ accessToken: tokenFor('user-b') });
    await done;

    http.expectNone((r) => r.url.endsWith('auth/session/bootstrap'));
    http.expectNone((r) => r.url.endsWith('/api/auth/logout'));
    expect(auth.getToken()).toBeNull();
    expect(auth.getUserProfile()).toBeNull();
    expect(auth.hasTabSession()).toBeFalse();
  });

  it('a tab locked before the reload stays locked', async () => {
    await bindTo('u1');
    sessionStorage.setItem('hms_idle_locked', 'true');
    sessionStorage.setItem('hms_lock_ts', '123');
    await restoreAs('u1');

    expect(auth.getToken()).toBe(tokenFor('u1'));
    expect(sessionStorage.getItem('hms_idle_locked')).toBe('true');
    expect(sessionStorage.getItem('hms_lock_ts')).toBe('123');
  });

  it('keeps the forced password and username change after the reload', async () => {
    await bindTo('u1');
    await restoreAs('u1', { forcePasswordChange: true, forceUsernameChange: true });

    expect(auth.getUserProfile()?.forcePasswordChange).toBeTrue();
    expect(auth.getUserProfile()?.forceUsernameChange).toBeTrue();
  });

  it('fails closed when the credential status cannot be read: the tab signs in again', async () => {
    await bindTo('u1');
    const done = run();
    http.expectOne((r) => isRefresh(r.url)).flush({ accessToken: tokenFor('u1') });
    await settle();
    http
      .expectOne((r) => r.url.endsWith('/auth/credentials/me'))
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await done;

    http.expectNone((r) => r.url.endsWith('auth/session/bootstrap'));
    http.expectNone((r) => r.url.endsWith('/api/auth/logout'));
    expect(auth.getToken()).toBeNull();
    expect(auth.getUserProfile()).toBeNull();
    expect(auth.hasTabSession()).toBeFalse();
  });

  it('fails closed when the session bootstrap cannot be read', async () => {
    await bindTo('u1');
    const done = run();
    http.expectOne((r) => isRefresh(r.url)).flush({ accessToken: tokenFor('u1') });
    await settle();
    http.expectOne((r) => r.url.endsWith('/auth/credentials/me')).flush({});
    await settle();
    http
      .expectOne((r) => r.url.endsWith('auth/session/bootstrap'))
      .flush(null, { status: 503, statusText: 'Service Unavailable' });
    await done;

    http.expectNone((r) => r.url.endsWith('/api/auth/logout'));
    expect(auth.getToken()).toBeNull();
    expect(auth.getUserProfile()).toBeNull();
    expect(auth.hasTabSession()).toBeFalse();
  });

  it('a refused cookie clears the tab locally, without a server sign-out', async () => {
    await bindTo('u1');
    const done = run();
    http
      .expectOne((r) => isRefresh(r.url))
      .flush({ message: 'expired' }, { status: 401, statusText: 'Unauthorized' });
    await done;

    http.expectNone((r) => r.url.endsWith('/api/auth/logout'));
    expect(auth.getToken()).toBeNull();
    expect(auth.hasTabSession()).toBeFalse();
  });
  it('a session the server holds locked shows the lock screen though the tab forgot it (r81 D4262)', async () => {
    const idle = TestBed.inject(IdleService);
    await bindTo('u1');
    expect(sessionStorage.getItem('hms_idle_locked')).toBeNull();

    const done = run();
    http
      .expectOne((r) => isRefresh(r.url))
      .flush({ accessToken: tokenFor('u1'), sessionLocked: true });
    await settle();
    http.expectOne((r) => r.url.endsWith('/auth/credentials/me')).flush({});
    await settle();
    http.expectOne((r) => r.url.endsWith('auth/session/bootstrap')).flush(bootstrap);
    await done;

    expect(idle.locked()).toBeTrue();
    expect(idle.contentHidden()).toBeTrue();
    expect(sessionStorage.getItem('hms_idle_locked')).toBe('true');
    idle.release();
  });
});
