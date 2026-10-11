import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, Subject, of, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { ProviderSettings } from '../services/provider.model';
import { ProviderPortalService } from '../services/provider-portal.service';
import { settingsFixture } from '../testing/provider-fixtures';
import { ProviderContextService } from './provider-context.service';
import { RoleContextService } from './role-context.service';

describe('ProviderContextService', () => {
  let userId: string | null;
  let roles: string[];
  let answer: () => Observable<ProviderSettings>;
  let calls: number;
  let scope: string | null;
  let resolved: boolean;
  let providerUser: boolean;

  function setup(): ProviderContextService {
    const auth = {
      getUserProfile: () => (userId ? { id: userId } : null),
      getSubject: () => null,
      hasAnyRole: (expected: string[]) => expected.some((r) => roles.includes(r)),
    };
    const portal = {
      settings: () => {
        calls++;
        return answer();
      },
    };
    TestBed.configureTestingModule({
      providers: [
        { provide: AuthService, useValue: auth },
        { provide: ProviderPortalService, useValue: portal },
        {
          provide: RoleContextService,
          useValue: {
            effectiveHospitalIdForRequest: () => scope,
            sessionResolved: () => resolved,
            providerUser: () => providerUser,
          },
        },
      ],
    });
    return TestBed.inject(ProviderContextService);
  }

  function value(context: ProviderContextService): ProviderSettings | null | undefined {
    let result: ProviderSettings | null | undefined;
    context.load().subscribe({ next: (s) => (result = s), error: () => (result = undefined) });
    return result;
  }

  beforeEach(() => {
    userId = 'u-1';
    roles = ['ROLE_PROVIDER_ADMIN'];
    answer = () => of(settingsFixture());
    calls = 0;
    scope = 'f-1';
    resolved = true;
    providerUser = true;
  });

  afterEach(() => TestBed.resetTestingModule());

  it('asks the server once per user and keeps the provider settings', () => {
    const context = setup();

    expect(value(context)?.facilityType).toBe('PHARMACY');
    expect(value(context)?.facilityId).toBe('f-1');
    expect(calls).toBe(1);
    expect(context.status()).toBe('provider');
  });

  it('never asks for a user the session bootstrap did not mark as a provider user', () => {
    // A hospital pharmacist: a provider-capable role, but no provider facility.
    roles = ['ROLE_PHARMACIST'];
    providerUser = false;
    const context = setup();

    expect(value(context)).toBeNull();
    expect(calls).toBe(0);
    expect(context.status()).toBe('none');
  });

  it('a 404 (no seat at a provider) is a final answer: no provider shell, not asked again', () => {
    roles = ['ROLE_PHARMACIST'];
    answer = () => throwError(() => new HttpErrorResponse({ status: 404 }));
    const context = setup();

    expect(value(context)).toBeNull();
    expect(value(context)).toBeNull();
    expect(calls).toBe(1);
  });

  it('a transient failure is an error, never a "not a provider" answer, and is not cached', () => {
    answer = () => throwError(() => new HttpErrorResponse({ status: 503 }));
    const context = setup();

    let failed = false;
    context.load().subscribe({ error: () => (failed = true) });
    expect(failed).toBeTrue();
    expect(context.status()).toBe('error');
    answer = () => of(settingsFixture());
    expect(value(context)?.facilityType).toBe('PHARMACY');
    expect(calls).toBe(2);
  });

  it('a HOSPITAL answer is not a provider', () => {
    answer = () => of(settingsFixture({ facilityType: 'HOSPITAL' }));
    const context = setup();

    expect(value(context)).toBeNull();
  });

  it('another user is asked afresh, and a signed-out session has no provider', () => {
    const context = setup();
    expect(value(context)).not.toBeNull();

    userId = 'u-2';
    providerUser = false;
    expect(value(context)).toBeNull();
    expect(context.settings()).toBeNull();

    userId = null;
    expect(value(context)).toBeNull();
  });

  it('asks again when the scope moves to another facility, and never shows the old one meanwhile', () => {
    const context = setup();
    expect(value(context)?.facilityId).toBe('f-1');

    scope = 'f-2';
    answer = () => of(settingsFixture({ facilityId: 'f-2', providerAdmin: false }));
    expect(value(context)?.facilityId).toBe('f-2');
    expect(context.settings()?.providerAdmin).toBeFalse();
    expect(calls).toBe(2);

    // Back to the first branch: asked again, never the second branch's answer.
    scope = 'f-1';
    answer = () => of(settingsFixture());
    expect(value(context)?.facilityId).toBe('f-1');
    expect(calls).toBe(3);
  });

  it('nothing is asked or cached before the session bootstrap has resolved', () => {
    resolved = false;
    const context = setup();

    expect(value(context)).toBeNull();
    expect(calls).toBe(0);
    resolved = true;
    expect(value(context)?.facilityType).toBe('PHARMACY');
    expect(calls).toBe(1);
  });

  it('invalidate() drops the answer and asks again: a lost seat reaches the nav', () => {
    const context = setup();
    expect(value(context)).not.toBeNull();

    answer = () => throwError(() => new HttpErrorResponse({ status: 404 }));
    context.invalidate();

    expect(calls).toBe(2);
    expect(context.settings()).toBeNull();
    expect(context.status()).toBe('none');
  });

  it('a late answer for a scope the session has left is dropped', () => {
    const pending = new Subject<ProviderSettings>();
    answer = () => pending;
    const context = setup();
    context.load().subscribe();

    scope = 'f-2';
    pending.next(settingsFixture({ facilityId: 'f-1' }));
    pending.complete();

    expect(context.settings()).toBeNull();
  });

  it('a "none" cached while the bootstrap said non-provider does not outlive a re-bootstrap saying provider', () => {
    providerUser = false;
    const context = setup();
    expect(value(context)).toBeNull();
    expect(calls).toBe(0);

    providerUser = true;
    expect(value(context)?.facilityId).toBe('f-1');
    expect(calls).toBe(1);
    expect(context.status()).toBe('provider');
  });

  it('invalidate() while a request is pending: the superseded answer is dropped and never clears its successor', () => {
    const first = new Subject<ProviderSettings>();
    const second = new Subject<ProviderSettings>();
    const answers = [first, second];
    answer = () => answers.shift()!;
    const context = setup();
    context.load().subscribe();
    context.invalidate();
    expect(calls).toBe(2);

    first.next(settingsFixture({ facilityId: 'stale' }));
    first.complete();
    expect(context.settings()).toBeNull();
    expect(context.status()).toBe('pending');
    // The successor is still the one in flight: no third request.
    context.load().subscribe();
    expect(calls).toBe(2);

    second.next(settingsFixture());
    second.complete();
    expect(context.settings()?.facilityId).toBe('f-1');
    expect(context.status()).toBe('provider');
  });

  it('a scope switch mid-flight: the waiting subscriber (a guard) gets the answer for the NEW facility', () => {
    const forA = new Subject<ProviderSettings>();
    const forB = new Subject<ProviderSettings>();
    answer = () => (scope === 'f-2' ? forB : forA);
    const context = setup();
    let decided: ProviderSettings | null | undefined;
    context.load().subscribe((s) => (decided = s));

    scope = 'f-2';
    forA.next(settingsFixture({ facilityId: 'f-1' }));
    forA.complete();
    // A's answer never reaches the subscriber; B is asked instead.
    expect(decided).toBeUndefined();
    expect(calls).toBe(2);

    forB.next(settingsFixture({ facilityId: 'f-2' }));
    forB.complete();
    expect(decided?.facilityId).toBe('f-2');
    expect(context.settings()?.facilityId).toBe('f-2');
  });

  it('a non-provider answer in between forgets the older request: going back asks afresh, never joins it', () => {
    const stale = new Subject<ProviderSettings>();
    answer = () => (calls === 1 ? stale : of(settingsFixture()));
    const context = setup();
    context.load().subscribe();
    expect(calls).toBe(1);

    providerUser = false; // a re-bootstrap says non-provider: another key
    expect(value(context)).toBeNull();

    providerUser = true; // and back: the old request must not be joined
    expect(value(context)?.facilityId).toBe('f-1');
    expect(calls).toBe(2);
    expect(context.status()).toBe('provider');

    stale.next(settingsFixture({ facilityId: 'stale' }));
    stale.complete();
    expect(context.settings()?.facilityId).toBe('f-1');
  });

  it('reportUnavailable() shows the retry state and caches nothing', () => {
    const context = setup();
    value(context);
    context.reportUnavailable();
    expect(context.status()).toBe('error');
    expect(context.settings()).toBeNull();
    value(context);
    expect(calls).toBe(2);
  });

  it('forget() drops the answer', () => {
    const context = setup();
    value(context);
    context.forget();
    expect(context.settings()).toBeNull();
    value(context);
    expect(calls).toBe(2);
  });
});
