import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { LockScreenComponent } from './lock-screen';
import { AuthService } from '../auth/auth.service';
import { IdleService } from '../core/idle.service';
import { OidcAuthService } from '../auth/oidc-auth.service';
import { SSO_TAB_KEY } from '../core/browser-storage';
import { signal, WritableSignal } from '@angular/core';

/**
 * Regression cover for the unlock path.
 *
 * The screen locks after 10 minutes idle; the access token lives 15
 * (`app.jwt.access-token-expiration-ms` defaults to 900000). Any break longer
 * than that left an expired token behind, and `/auth/verify-password` is an
 * authenticated endpoint — the JWT filter 401'd before the password was ever
 * checked, and the component reported every 401 as "Incorrect password". A
 * clinician back from a ward round was told their correct password was wrong
 * on every attempt, with no route out but typing /login by hand.
 */
describe('LockScreenComponent — unlock', () => {
  let fixture: ComponentFixture<LockScreenComponent>;
  let component: LockScreenComponent;
  let auth: jasmine.SpyObj<AuthService>;
  let idle: jasmine.SpyObj<IdleService>;
  let httpMock: HttpTestingController;

  const FRESH_TOKENS = { accessToken: 'new-access', refreshToken: 'new-refresh' };

  function setUp(opts: { tokenExpired: boolean; hasToken?: boolean } = { tokenExpired: false }) {
    auth.getToken.and.returnValue(opts.hasToken === false ? null : 'stored-token');
    auth.isExpired.and.returnValue(opts.tokenExpired);
    fixture.detectChanges();
    component.password = 'correct-horse';
  }

  /** Answers the verify-password POST, asserting it was actually issued. */
  function expectVerify() {
    return httpMock.expectOne('/auth/verify-password');
  }

  beforeEach(async () => {
    auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getToken',
      'isExpired',
      'refreshTokenRequest',
      'setToken',
      'setRefreshToken',
      'getUserProfile',
      'getSubject',
      'isTokenForShownAccount',
    ]);
    auth.isTokenForShownAccount.and.returnValue(true);
    auth.getUserProfile.and.returnValue(null);
    auth.getSubject.and.returnValue('doctor_b');
    auth.refreshTokenRequest.and.returnValue(of(FRESH_TOKENS));

    idle = jasmine.createSpyObj<IdleService>('IdleService', [
      'unlock',
      'release',
      'ensureServerLock',
      'lock',
      'start',
      'stop',
    ]);

    await TestBed.configureTestingModule({
      imports: [LockScreenComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: IdleService, useValue: idle },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(LockScreenComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  // ── A still-valid token goes straight through ───────────────

  it('verifies the password directly when the token is still good', () => {
    setUp({ tokenExpired: false });
    component.unlock();

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    const req = expectVerify();
    expect(req.request.body).toEqual({ username: 'doctor_b', password: 'correct-horse' });
    req.flush({ message: 'Password verified.' });

    expect(idle.unlock).toHaveBeenCalled();
    expect(component.password).toBe('');
    expect(component.error).toBe('');
  });

  // ── The bug: an expired token must be renewed, not blamed on the user ──

  it('renews an expired access token before verifying', () => {
    setUp({ tokenExpired: true });
    component.unlock();

    expect(auth.refreshTokenRequest).toHaveBeenCalled();
    expect(auth.setToken).toHaveBeenCalledWith('new-access');
    expect(auth.setRefreshToken).toHaveBeenCalledWith('new-refresh');

    expectVerify().flush({ message: 'Password verified.' });
    expect(idle.unlock).toHaveBeenCalled();
  });

  it('a renewal that mints another account signs the tab out locally, never verifying the password', () => {
    setUp({ tokenExpired: true });
    auth.isTokenForShownAccount.and.returnValue(false);
    let changed = false;
    component.accountChanged.subscribe(() => (changed = true));
    component.unlock();

    httpMock.expectNone('/auth/verify-password');
    expect(auth.setToken).not.toHaveBeenCalled();
    // The lock is forgotten, the page behind it is never put back.
    expect(idle.release).toHaveBeenCalled();
    expect(idle.unlock).not.toHaveBeenCalled();
    expect(changed).toBeTrue();
  });

  it('renews when no access token is stored at all', () => {
    setUp({ tokenExpired: false, hasToken: false });
    component.unlock();

    expect(auth.refreshTokenRequest).toHaveBeenCalled();
    expectVerify().flush({ message: 'Password verified.' });
    expect(idle.unlock).toHaveBeenCalled();
  });

  it('does not spend a refresh on a mistyped password — that would keep a dead session alive', () => {
    setUp({ tokenExpired: false });
    component.unlock();

    expectVerify().flush(
      { message: 'Invalid password.' },
      { status: 401, statusText: 'Unauthorized' },
    );

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
  });

  // ── Error messages must name the real cause ─────────────────

  it('reports a wrong password when the token was known good', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(component.error).toBe('LOCK.ERROR_WRONG_PASSWORD');
    expect(idle.unlock).not.toHaveBeenCalled();
  });

  it('reports an expired session — not a bad password — when renewal is refused', () => {
    auth.refreshTokenRequest.and.returnValue(
      throwError(() => ({ status: 401, statusText: 'Unauthorized' })),
    );
    setUp({ tokenExpired: true });
    component.unlock();

    // The password was never even sent, so calling it wrong would be a lie.
    httpMock.expectNone('/auth/verify-password');
    expect(component.error).toBe('LOCK.ERROR_SESSION_EXPIRED');
    expect(idle.unlock).not.toHaveBeenCalled();
  });

  it('reports a retryable failure when renewal fails for a non-auth reason', () => {
    auth.refreshTokenRequest.and.returnValue(
      throwError(() => ({ status: 0, statusText: 'Unknown Error' })),
    );
    setUp({ tokenExpired: true });
    component.unlock();

    httpMock.expectNone('/auth/verify-password');
    expect(component.error).toBe('LOCK.ERROR_VERIFY_FAILED');
  });

  it('reports a session mismatch on 403', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 403, statusText: 'Forbidden' });

    expect(component.error).toBe('LOCK.ERROR_SESSION_MISMATCH');
  });

  // ── QA D2011: the lock screen takes the keyboard ────────────

  it('focuses the password field when the lock screen opens', () => {
    setUp({ tokenExpired: false });
    const input = fixture.nativeElement.querySelector('#unlock-password') as HTMLInputElement;
    expect(document.activeElement).toBe(input);
    const card = fixture.nativeElement.querySelector('.lock-card') as HTMLElement;
    expect(card.getAttribute('role')).toBe('dialog');
    expect(card.getAttribute('aria-modal')).toBe('true');
  });

  it('keeps Tab inside the lock screen and Escape does not unlock', () => {
    setUp({ tokenExpired: false });
    const card = fixture.nativeElement.querySelector('.lock-card') as HTMLElement;
    const signOut = fixture.nativeElement.querySelector('.btn-signout') as HTMLButtonElement;
    const input = fixture.nativeElement.querySelector('#unlock-password') as HTMLInputElement;
    signOut.focus();
    card.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(input);

    input.focus();
    card.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Tab', shiftKey: true, bubbles: true, cancelable: true }),
    );
    expect(document.activeElement).toBe(signOut);

    card.dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true }),
    );
    expect(idle.unlock).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('.lock-card')).not.toBeNull();
  });

  // ── D2001: the account lockout ends the session ─────────────

  it('signs out on 423 (account locked) instead of offering another guess', () => {
    const lockedOut = jasmine.createSpy('lockedOut');
    component.lockedOut.subscribe(lockedOut);
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(
      { message: 'Account is temporarily locked. Try again in 15 minutes.' },
      { status: 423, statusText: 'Locked' },
    );

    expect(lockedOut).toHaveBeenCalledTimes(1);
    expect(idle.release).toHaveBeenCalled();
    expect(idle.unlock).not.toHaveBeenCalled();
    expect(component.error).toBe('');
    expect(component.password).toBe('');
    expect(component.loading()).toBeFalse();
  });

  it('does not sign out on a plain wrong password (401)', () => {
    const lockedOut = jasmine.createSpy('lockedOut');
    component.lockedOut.subscribe(lockedOut);
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(lockedOut).not.toHaveBeenCalled();
  });

  it('reports a retryable failure on a server error', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 500, statusText: 'Server Error' });

    expect(component.error).toBe('LOCK.ERROR_VERIFY_FAILED');
  });

  // ── Housekeeping ────────────────────────────────────────────

  it('clears the typed password after a failure so the next attempt starts clean', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    expectVerify().flush(null, { status: 401, statusText: 'Unauthorized' });

    expect(component.password).toBe('');
    expect(component.loading()).toBeFalse();
  });

  it('ignores a submit with no password typed', () => {
    setUp({ tokenExpired: true });
    component.password = '';
    component.unlock();

    expect(auth.refreshTokenRequest).not.toHaveBeenCalled();
    httpMock.expectNone('/auth/verify-password');
  });

  it('ignores a second submit while one is still in flight', () => {
    setUp({ tokenExpired: false });
    component.unlock();
    component.unlock();

    // expectOne throws if the component issued the request twice.
    expectVerify().flush({ message: 'Password verified.' });
  });
  // ── r81 D4262: only the server's acceptance of the password opens the screen ──

  it('tells the server about the lock again when the lock screen opens', () => {
    setUp({ tokenExpired: false });
    expect(idle.ensureServerLock).toHaveBeenCalled();
  });

  it('signing out or switching user forgets the lock without putting the page back', () => {
    setUp({ tokenExpired: false });
    component.signOut();
    component.switchUser();

    expect(idle.release).toHaveBeenCalledTimes(2);
    expect(idle.unlock).not.toHaveBeenCalled();
  });

  it('a password tab does not ask the identity provider to unlock', () => {
    setUp({ tokenExpired: false });
    expect(component.sso).toBeFalse();
    httpMock.expectNone('/auth/session/unlock');
    expect(fixture.nativeElement.querySelector('#unlock-password')).not.toBeNull();
  });
});

/**
 * r81 D4262, a tab signed in at the identity provider. The lock screen asks
 * the server whether it may open only when the tab has just come back from
 * the sign-in it asked for, and opens only on the server's 204.
 */
describe('LockScreenComponent — a tab signed in at the identity provider (r81 F1)', () => {
  let idle: jasmine.SpyObj<IdleService>;
  let httpMock: HttpTestingController;
  let reauthenticated: WritableSignal<boolean>;
  let reauthenticate: jasmine.Spy;

  function open(back: boolean): ComponentFixture<LockScreenComponent> {
    reauthenticated.set(back);
    const fixture = TestBed.createComponent(LockScreenComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(async () => {
    sessionStorage.setItem(SSO_TAB_KEY, 'bound');
    const auth = jasmine.createSpyObj<AuthService>('AuthService', [
      'getToken',
      'isExpired',
      'getUserProfile',
      'getSubject',
    ]);
    auth.getUserProfile.and.returnValue(null);
    auth.getSubject.and.returnValue('dr.alice');
    idle = jasmine.createSpyObj<IdleService>('IdleService', [
      'unlock',
      'release',
      'ensureServerLock',
    ]);
    reauthenticated = signal(false);
    reauthenticate = jasmine.createSpy('reauthenticate');
    await TestBed.configureTestingModule({
      imports: [LockScreenComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: AuthService, useValue: auth },
        { provide: IdleService, useValue: idle },
        { provide: OidcAuthService, useValue: { reauthenticated, reauthenticate } },
      ],
    }).compileComponents();
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
    sessionStorage.removeItem(SSO_TAB_KEY);
  });

  it('opening the lock screen asks the server nothing about unlocking: it stays locked', () => {
    const fixture = open(false);

    httpMock.expectNone('/auth/session/unlock');
    expect(idle.unlock).not.toHaveBeenCalled();
    expect(idle.ensureServerLock).toHaveBeenCalledTimes(1);
    // No password here: the way in is the provider.
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('#unlock-password')).toBeNull();
    host.querySelector<HTMLButtonElement>('.btn-unlock')!.click();
    expect(reauthenticate).toHaveBeenCalledTimes(1);
    expect(idle.unlock).not.toHaveBeenCalled();
  });

  it("back from the sign-in it asked for, the server's 204 opens the screen, once", () => {
    open(true);

    httpMock
      .expectOne('/auth/session/unlock')
      .flush(null, { status: 204, statusText: 'No Content' });
    expect(idle.unlock).toHaveBeenCalledTimes(1);
    expect(reauthenticated()).toBeFalse();

    // The next lock of the same page asks nothing again.
    open(false);
    httpMock.expectNone('/auth/session/unlock');
    expect(idle.unlock).toHaveBeenCalledTimes(1);
  });

  for (const refusal of [
    { status: 403, statusText: 'Forbidden' },
    { status: 500, statusText: 'Server Error' },
  ]) {
    it(`back from the sign-in but refused (${refusal.status}): the screen stays locked and the lock is reported`, () => {
      open(true);

      httpMock.expectOne('/auth/session/unlock').flush({ code: 'session_locked' }, refusal);
      expect(idle.unlock).not.toHaveBeenCalled();
      expect(idle.ensureServerLock).toHaveBeenCalledTimes(1);
    });
  }

  it('an answer that is not the 204 does not open the screen', () => {
    open(true);

    httpMock.expectOne('/auth/session/unlock').flush({}, { status: 200, statusText: 'OK' });
    expect(idle.unlock).not.toHaveBeenCalled();
  });
});
