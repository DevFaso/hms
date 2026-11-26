import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthService } from './auth.service';
import { SESSION_RESTORE_HEADER, restoreSessionOnStartup } from './session-restore';
import { TAB_SESSION_KEY } from '../core/browser-storage';

function fakeJwt(payload: Record<string, unknown>): string {
  const b64 = (v: string) => btoa(v).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
  return `${b64('{"alg":"none"}')}.${b64(JSON.stringify(payload))}.sig`;
}

/** Lets the promise chain of the initializer reach its next request. */
async function settle(): Promise<void> {
  for (let i = 0; i < 5; i++) await Promise.resolve();
}

describe('restoreSessionOnStartup — the session after a reload comes from the refresh cookie', () => {
  let http: HttpTestingController;
  let auth: AuthService;
  const isRefresh = (url: string) => url.endsWith('/api/auth/token/refresh');

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

  it('restores the token and the profile, before the first route', async () => {
    sessionStorage.setItem(TAB_SESSION_KEY, '1');
    const token = fakeJwt({ uid: 'u1', sub: 'dr', roles: ['ROLE_DOCTOR'], exp: 9999999999 });
    const done = run();

    const refresh = http.expectOne((r) => isRefresh(r.url));
    expect(refresh.request.method).toBe('POST');
    expect(refresh.request.withCredentials).toBeTrue();
    expect(refresh.request.headers.get(SESSION_RESTORE_HEADER)).toBe('1');
    refresh.flush({ accessToken: token });
    await settle();

    http
      .expectOne((r) => r.url.endsWith('auth/session/bootstrap'))
      .flush({
        userId: 'u1',
        username: 'dr',
        email: '',
        authSource: 'internal',
        roles: ['ROLE_DOCTOR'],
        superAdmin: false,
        hospitalAdmin: false,
        permittedHospitalIds: ['h1'],
        primaryHospitalId: 'h1',
      });
    await done;

    expect(auth.getToken()).toBe(token);
    expect(auth.getUserProfile()?.id).toBe('u1');
    expect(auth.hasTabSession()).toBeTrue();
  });

  it('a refused cookie clears the tab locally, without a server sign-out', async () => {
    sessionStorage.setItem(TAB_SESSION_KEY, '1');
    const done = run();
    http
      .expectOne((r) => isRefresh(r.url))
      .flush({ message: 'expired' }, { status: 401, statusText: 'Unauthorized' });
    await done;

    http.expectNone((r) => r.url.endsWith('/api/auth/logout'));
    expect(auth.getToken()).toBeNull();
    expect(auth.hasTabSession()).toBeFalse();
  });
});
