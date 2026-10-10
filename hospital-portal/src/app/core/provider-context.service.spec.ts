import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse } from '@angular/common/http';
import { Observable, of, throwError } from 'rxjs';

import { AuthService } from '../auth/auth.service';
import { ProviderSettings } from '../services/provider.model';
import { ProviderPortalService } from '../services/provider-portal.service';
import { settingsFixture } from '../testing/provider-fixtures';
import { ProviderContextService } from './provider-context.service';

describe('ProviderContextService', () => {
  let userId: string | null;
  let roles: string[];
  let answer: () => Observable<ProviderSettings>;
  let calls: number;

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
      ],
    });
    return TestBed.inject(ProviderContextService);
  }

  function value(context: ProviderContextService): ProviderSettings | null | undefined {
    let result: ProviderSettings | null | undefined;
    context.load().subscribe((s) => (result = s));
    return result;
  }

  beforeEach(() => {
    userId = 'u-1';
    roles = ['ROLE_PROVIDER_ADMIN'];
    answer = () => of(settingsFixture());
    calls = 0;
  });

  afterEach(() => TestBed.resetTestingModule());

  it('asks the server once per user and keeps the provider settings', () => {
    const context = setup();

    expect(value(context)?.facilityType).toBe('PHARMACY');
    expect(value(context)?.facilityId).toBe('f-1');
    expect(calls).toBe(1);
    expect(context.isProvider()).toBeTrue();
  });

  it('never asks for a user who holds no role a provider may hold', () => {
    roles = ['ROLE_DOCTOR'];
    const context = setup();

    expect(value(context)).toBeNull();
    expect(calls).toBe(0);
    expect(context.isProvider()).toBeFalse();
  });

  it('a 404 (no seat at a provider) is a final answer: no provider shell, not asked again', () => {
    roles = ['ROLE_PHARMACIST'];
    answer = () => throwError(() => new HttpErrorResponse({ status: 404 }));
    const context = setup();

    expect(value(context)).toBeNull();
    expect(value(context)).toBeNull();
    expect(calls).toBe(1);
  });

  it('another failure is not cached: the next load asks again', () => {
    answer = () => throwError(() => new HttpErrorResponse({ status: 503 }));
    const context = setup();

    expect(value(context)).toBeNull();
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
    roles = ['ROLE_DOCTOR'];
    expect(value(context)).toBeNull();
    expect(context.settings()).toBeNull();

    userId = null;
    expect(value(context)).toBeNull();
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
