import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { AuthService, type SessionBootstrapResponse } from './auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ApiTimeoutService } from '../core/api-timeout.service';
import {
  LEGACY_STORAGE_KEYS,
  LOCAL_STORAGE_ALLOWLIST,
  SESSION_SCOPED_KEYS,
  TAB_SESSION_KEY,
  readPatientNote,
  tabBindingFor,
  writePatientNote,
} from '../core/browser-storage';

describe('AuthService — S-01 refresh-token cookie behaviour', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('clears notices when the identity changes and preserves an uncertain action during a profile refresh', () => {
    const notice = TestBed.inject(ApiTimeoutService);
    const profile = {
      id: 'first',
      username: 'first',
      email: 'first@example.test',
      roles: ['ROLE_STAFF'],
      active: true,
    };
    service.setUserProfile(profile);
    notice.noteTimeout('POST');
    const previousEpoch = notice.sessionEpoch;
    service.setUserProfile({ ...profile, firstName: 'Updated' });
    expect(notice.failure()).toBe('write');
    service.setUserProfile({ ...profile, id: 'second' });
    expect(notice.failure()).toBeNull();
    notice.noteTimeout('POST', previousEpoch);
    expect(notice.failure()).toBeNull();
  });

  it('setRefreshToken is a no-op — does NOT persist the token to localStorage or sessionStorage', () => {
    service.setRefreshToken('should-not-be-stored', true);
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
    expect(sessionStorage.getItem('auth_refresh_token')).toBeNull();

    service.setRefreshToken('should-not-be-stored-here-either', false);
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
    expect(sessionStorage.getItem('auth_refresh_token')).toBeNull();
  });

  it('setRefreshToken purges any legacy refresh token left over in storage', () => {
    localStorage.setItem('auth_refresh_token', 'legacy-token');
    service.setRefreshToken('new-token');
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
  });

  it('refreshTokenRequest sends an empty body and credentials when no legacy token exists', () => {
    service.refreshTokenRequest().subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/auth/token/refresh'));
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({});
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ accessToken: 'a', refreshToken: 'b' });
  });

  it('refreshTokenRequest forwards a legacy refresh token in the body to migrate it server-side', () => {
    localStorage.setItem('auth_refresh_token', 'legacy.refresh.token');

    service.refreshTokenRequest().subscribe();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/auth/token/refresh'));
    expect(req.request.body).toEqual({ refreshToken: 'legacy.refresh.token' });
    expect(req.request.withCredentials).toBeTrue();
    req.flush({ accessToken: 'a', refreshToken: 'b' });
  });

  it('clearRefreshToken removes any legacy storage entries', () => {
    localStorage.setItem('auth_refresh_token', 'x');
    sessionStorage.setItem('auth_refresh_token', 'y');
    service.clearRefreshToken();
    expect(localStorage.getItem('auth_refresh_token')).toBeNull();
    expect(sessionStorage.getItem('auth_refresh_token')).toBeNull();
  });
});

describe('AuthService — sessionBootstrap', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  const mockBootstrapResponse: SessionBootstrapResponse = {
    userId: 'user-uuid-1',
    username: 'john.doe',
    email: 'john.doe@hms.test',
    firstName: 'John',
    lastName: 'Doe',
    authSource: 'internal',
    roles: ['ROLE_NURSE'],
    superAdmin: false,
    hospitalAdmin: false,
    primaryHospitalId: 'hosp-uuid-1',
    primaryHospitalName: 'General Hospital',
    permittedHospitalIds: ['hosp-uuid-1'],
    staffId: 'staff-uuid-1',
    staffRoleCode: 'ROLE_NURSE',
    departmentId: 'dept-uuid-1',
    departmentName: 'Cardiology',
  };

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('sends GET to the relative session bootstrap endpoint', () => {
    service.sessionBootstrap().subscribe();

    const req = httpMock.expectOne((r) => r.url === 'auth/session/bootstrap');
    expect(req.request.method).toBe('GET');
    req.flush(mockBootstrapResponse);
  });

  it('returns the bootstrap response from the server', (done) => {
    service.sessionBootstrap().subscribe((result) => {
      expect(result.userId).toBe('user-uuid-1');
      expect(result.username).toBe('john.doe');
      expect(result.roles).toEqual(['ROLE_NURSE']);
      expect(result.primaryHospitalId).toBe('hosp-uuid-1');
      expect(result.primaryHospitalName).toBe('General Hospital');
      expect(result.staffId).toBe('staff-uuid-1');
      expect(result.superAdmin).toBeFalse();
      expect(result.hospitalAdmin).toBeFalse();
      done();
    });

    httpMock.expectOne((r) => r.url === 'auth/session/bootstrap').flush(mockBootstrapResponse);
  });

  it('propagates an HTTP error so the caller can handle it', (done) => {
    service.sessionBootstrap().subscribe({
      error: (err) => {
        expect(err.status).toBe(401);
        done();
      },
    });

    httpMock
      .expectOne((r) => r.url === 'auth/session/bootstrap')
      .flush({ message: 'Unauthorized' }, { status: 401, statusText: 'Unauthorized' });
  });
});

describe('AuthService — resolveLandingPath', () => {
  let service: AuthService;
  let roleContext: RoleContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    roleContext = TestBed.inject(RoleContextService);
  });

  afterEach(() => TestBed.resetTestingModule());

  it('lands a super admin (active role ROLE_SUPER_ADMIN) on /super-admin', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.activeRole = 'ROLE_SUPER_ADMIN';
    expect(service.resolveLandingPath()).toBe('/super-admin');
  });

  it('lands every other role on /dashboard', () => {
    roleContext.setRoles(['ROLE_DOCTOR']);
    roleContext.activeRole = 'ROLE_DOCTOR';
    expect(service.resolveLandingPath()).toBe('/dashboard');
  });

  it('lands a multi-role user who picked a non-super role on /dashboard', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN', 'ROLE_DOCTOR']);
    roleContext.activeRole = 'ROLE_DOCTOR';
    expect(service.resolveLandingPath()).toBe('/dashboard');
  });
});

describe('AuthService — where the session is kept', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;
  const KEY = 'auth_token';

  function jwt(payload: Record<string, unknown>): string {
    const b64 = (v: string) =>
      btoa(v).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
    return `${b64('{"alg":"none"}')}.${b64(JSON.stringify(payload))}.sig`;
  }

  function create(): AuthService {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
    return TestBed.inject(AuthService);
  }

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
  });

  afterEach(() => {
    httpMock?.verify();
    localStorage.clear();
    sessionStorage.clear();
    TestBed.resetTestingModule();
  });

  function storedValues(): string {
    const values: string[] = [];
    for (const storage of [localStorage, sessionStorage]) {
      for (let i = 0; i < storage.length; i++) values.push(storage.getItem(storage.key(i)!) ?? '');
    }
    return values.join('|');
  }

  /** Lets the digest of the tab binding settle. */
  const settle = () => new Promise((resolve) => setTimeout(resolve, 20));

  it('keeps the token in memory only, and binds the tab with an opaque digest', async () => {
    service = create();
    const token = jwt({ uid: 'user-123', sub: 'dr' });
    service.setToken(token);
    await settle();
    expect(service.getToken()).toBe(token);
    expect(storedValues()).not.toContain(token);
    expect(storedValues()).not.toContain('user-123');
    expect(localStorage.getItem(KEY)).toBeNull();
    expect(sessionStorage.getItem(KEY)).toBeNull();
    expect(sessionStorage.getItem(TAB_SESSION_KEY)).toBe(await tabBindingFor('user-123'));
    expect(sessionStorage.getItem(TAB_SESSION_KEY)).toMatch(/^[0-9a-f]{64}$/);
    expect(service.hasTabSession()).toBeTrue();
  });

  it('a reload starts without a token (the start-up restore asks the refresh cookie)', async () => {
    sessionStorage.setItem(TAB_SESSION_KEY, await tabBindingFor('user-123'));
    service = create();
    expect(service.getToken()).toBeNull();
    expect(service.hasTabSession()).toBeTrue();
  });

  it('after a reload, accepts a token for the bound account only', async () => {
    sessionStorage.setItem(TAB_SESSION_KEY, await tabBindingFor('user-a'));
    service = create();
    expect(await service.isTokenForThisTab(jwt({ uid: 'user-a' }))).toBeTrue();
    expect(await service.isTokenForThisTab(jwt({ uid: 'user-b' }))).toBeFalse();
    sessionStorage.removeItem(TAB_SESSION_KEY);
    expect(await service.isTokenForThisTab(jwt({ uid: 'user-a' }))).toBeFalse();
  });

  it('while impersonating, the tab stays bound to the super admin, so a reload brings the admin back', async () => {
    service = create();
    const admin = jwt({ uid: 'admin-1' });
    service.setToken(admin);
    await settle();
    service.holdOriginalSession(admin, null);
    service.setToken(jwt({ uid: 'target-9' }));
    await settle();
    expect(sessionStorage.getItem(TAB_SESSION_KEY)).toBe(await tabBindingFor('admin-1'));
    expect(await service.isTokenForThisTab(admin)).toBeTrue();
  });

  it('a new token never clears the idle lock (only a fresh sign-in does)', () => {
    sessionStorage.setItem('hms_idle_locked', 'true');
    sessionStorage.setItem('hms_lock_ts', '1');
    service = create();
    service.setToken(jwt({ uid: 'user-a' }));
    expect(sessionStorage.getItem('hms_idle_locked')).toBe('true');
    expect(sessionStorage.getItem('hms_lock_ts')).toBe('1');
  });

  it('keeps the profile in memory only', () => {
    service = create();
    service.setUserProfile({ id: 'u1', username: 'dr', email: '', roles: [], active: true });
    expect(service.getUserProfile()?.id).toBe('u1');
    expect(storedValues()).not.toContain('"u1"');
    expect(sessionStorage.getItem('user_profile')).toBeNull();
    expect(localStorage.getItem('user_profile')).toBeNull();
  });

  it('clearing the token drops the tab binding', async () => {
    service = create();
    service.setToken(jwt({ uid: 'user-a' }));
    await settle();
    service.clearToken();
    await settle();
    expect(service.getToken()).toBeNull();
    expect(service.hasTabSession()).toBeFalse();
  });

  it('removes the session an earlier build left in web storage at start-up', () => {
    for (const storage of [localStorage, sessionStorage]) {
      storage.setItem('auth_token', 'old.jwt');
      storage.setItem('auth_refresh_token', 'old.refresh');
      storage.setItem('user_profile', '{"id":"u0"}');
      storage.setItem('auth_token_pre_impersonation', 'old.jwt');
      storage.setItem('auth_profile_pre_impersonation', '{"id":"u0"}');
    }
    localStorage.setItem('portal-notes-medical', '{"c":"x"}');
    sessionStorage.setItem('hms.patientNotes.u0.medical', 'x');
    localStorage.setItem('lang', 'fr');

    service = create();

    expect(storedValues()).not.toContain('old.');
    expect(storedValues()).not.toContain('u0');
    expect(localStorage.getItem('portal-notes-medical')).toBeNull();
    expect(sessionStorage.getItem('hms.patientNotes.u0.medical')).toBeNull();
    expect(localStorage.getItem('lang')).toBe('fr');
    expect(service.getToken()).toBeNull();
    expect(service.getUserProfile()).toBeNull();
  });

  it("drops the previous user's drafts when another account takes over the tab", () => {
    service = create();
    service.setUserProfile({ id: 'u1', username: 'a', email: '', roles: [], active: true });
    writePatientNote('u1', 'medical', 'note');
    service.setUserProfile({ id: 'u1', username: 'a', email: '', roles: [], active: true });
    expect(readPatientNote('u1', 'medical')).toBe('note');

    service.setUserProfile({ id: 'u2', username: 'b', email: '', roles: [], active: true });
    expect(readPatientNote('u1', 'medical')).toBe('');
  });

  it('tells whether a refreshed token is for the account the tab shows', () => {
    service = create();
    service.setUserProfile({ id: 'u1', username: 'a', email: '', roles: [], active: true });
    expect(service.isTokenForShownAccount(jwt({ uid: 'u1' }))).toBeTrue();
    expect(service.isTokenForShownAccount(jwt({ uid: 'u2' }))).toBeFalse();
    // Nothing to compare against: accepted.
    expect(service.isTokenForShownAccount(jwt({ sub: 'x' }))).toBeTrue();
  });

  it('the impersonation snapshot is handed back once, and never outlives the session', () => {
    service = create();
    service.holdOriginalSession('original.jwt', null);
    expect(service.releaseOriginalSession().token).toBe('original.jwt');
    expect(service.releaseOriginalSession().token).toBeNull();

    service.holdOriginalSession('original.jwt', null);
    service.endLocalSession();
    expect(service.releaseOriginalSession().token).toBeNull();
  });

  it('endLocalSession clears the tab without a server sign-out', () => {
    service = create();
    service.setToken('a.jwt');
    service.setUserProfile({ id: 'u1', username: 'a', email: '', roles: [], active: true });
    service.endLocalSession();
    httpMock.expectNone((r) => r.url.endsWith('/api/auth/logout'));
    expect(service.getToken()).toBeNull();
    expect(service.getUserProfile()).toBeNull();
    expect(sessionStorage.length).toBe(0);
  });
});

describe('AuthService — getHospitalId follows the effective scope', () => {
  let service: AuthService;
  let roleContext: RoleContextService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    roleContext = TestBed.inject(RoleContextService);
  });

  it('is the assignment for staff', () => {
    roleContext.setRoles(['ROLE_NURSE']);
    roleContext.activeHospitalId = 'h1';
    expect(service.getHospitalId()).toBe('h1');
  });

  it('is null for a super-admin in global view, not their primary hospital', () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.activeHospitalId = 'primary';
    roleContext.markSuperAdminGlobalDefaults();
    expect(service.getHospitalId()).toBeNull();
  });

  it("is the chip's pick once a super-admin has scoped", () => {
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.activeHospitalId = 'primary';
    roleContext.markSuperAdminGlobalDefaults();
    roleContext.scopeToHospital('h2');
    expect(service.getHospitalId()).toBe('h2');
  });
});

/**
 * Sign-out cleared local storage and nothing else: the server session, and
 * the HttpOnly refresh cookie that can mint a new access token, stayed live.
 */
describe('AuthService — logout revokes the server session', () => {
  let service: AuthService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(AuthService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    localStorage.clear();
    sessionStorage.clear();
  });

  it('ends session-owned background work when the application injector is destroyed', () => {
    const notice = TestBed.inject(ApiTimeoutService);
    notice.noteTimeout('POST');
    const ended = jasmine.createSpy('session ended');
    service.sessionEnded$.subscribe(ended);
    TestBed.resetTestingModule();
    expect(ended).toHaveBeenCalledTimes(1);
    expect(notice.failure()).toBeNull();
  });

  it('POSTs /auth/logout with the current bearer and the refresh cookie', () => {
    const notice = TestBed.inject(ApiTimeoutService);
    notice.noteTimeout('POST');
    const ended = jasmine.createSpy('session ended');
    service.sessionEnded$.subscribe(ended);
    service.setToken('current.jwt.token');

    service.logout();
    expect(ended).toHaveBeenCalledTimes(1);
    expect(notice.failure()).toBeNull();

    const req = httpMock.expectOne((r) => r.url.endsWith('/api/auth/logout'));
    expect(req.request.method).toBe('POST');
    expect(req.request.headers.get('Authorization')).toBe('Bearer current.jwt.token');
    expect(req.request.withCredentials).toBeTrue();
    // Local state is gone at once; nothing waits on the server.
    expect(service.getToken()).toBeNull();
    req.flush(null, { status: 204, statusText: 'No Content' });
  });

  it('clears session flags and chunk recovery state on logout', () => {
    sessionStorage.setItem('hms-chunk-reload', 'legacy-private-route');
    service.setToken('current.jwt.token');
    const roleContext = TestBed.inject(RoleContextService);
    roleContext.setProviderUser(true);
    roleContext.markSessionResolved();

    service.logout();
    httpMock.expectOne((r) => r.url.endsWith('/api/auth/logout')).flush(null);

    expect(sessionStorage.getItem('hms-chunk-reload')).toBeNull();
    expect(roleContext.providerUser()).toBeFalse();
    expect(roleContext.sessionResolved()).toBeFalse();
  });

  it('clears every session-scoped key, including the patient notes, and keeps only device preferences', () => {
    for (const key of SESSION_SCOPED_KEYS) sessionStorage.setItem(key, 'x');
    writePatientNote('u1', 'medical', 'note');
    for (const key of LEGACY_STORAGE_KEYS) {
      localStorage.setItem(key, 'x');
      sessionStorage.setItem(key, 'x');
    }
    localStorage.setItem('portal-notes-family', 'x');
    for (const key of LOCAL_STORAGE_ALLOWLIST) localStorage.setItem(key, 'pref');

    service.logout();
    httpMock.expectOne((r) => r.url.endsWith('/api/auth/logout')).flush(null);

    expect(sessionStorage.length).toBe(0);
    expect(readPatientNote('u1', 'medical')).toBe('');
    const left = Array.from({ length: localStorage.length }, (_, i) => localStorage.key(i));
    expect(left.sort()).toEqual([...LOCAL_STORAGE_ALLOWLIST].sort());
  });

  it('still signs out locally when the server call fails', () => {
    service.setToken('current.jwt.token');
    let thrown: unknown = null;

    try {
      service.logout();
      httpMock
        .expectOne((r) => r.url.endsWith('/api/auth/logout'))
        .flush('down', { status: 503, statusText: 'Service Unavailable' });
    } catch (e) {
      thrown = e;
    }

    expect(thrown).toBeNull();
    expect(service.getToken()).toBeNull();
    expect(service.getUserProfile()).toBeNull();
  });

  it('sends no Authorization header when there is no token to revoke', () => {
    service.logout();
    const req = httpMock.expectOne((r) => r.url.endsWith('/api/auth/logout'));
    expect(req.request.headers.has('Authorization')).toBeFalse();
    expect(req.request.withCredentials).toBeTrue();
    req.flush(null);
  });
});
