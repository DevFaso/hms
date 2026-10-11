import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import { EmergencyControlService } from './emergency-control.service';
import { HospitalLifecycleService } from './hospital-lifecycle.service';
import { OrganizationService } from './organization.service';
import {
  MFA_TOKEN_HEADER,
  STEP_UP_REQUEST,
  isStepUpRefusal,
  stepUpRequestOptions,
} from './step-up';

/**
 * Every request that sends an MFA step-up carries the STEP_UP_REQUEST mark,
 * so a refused code comes back to its dialog instead of refreshing the session
 * or signing the user out. One test per service that sends `X-Mfa-Token`.
 * (Impersonation start uses the same helper; its service needs the auth
 * stack, so it is covered by the helper test below and its own spec.)
 */
describe('step-up requests', () => {
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function expectMarked(url: string): void {
    const req = httpMock.expectOne(url);
    expect(req.request.context.get(STEP_UP_REQUEST)).withContext(url).toBeTrue();
    expect(req.request.headers.get(MFA_TOKEN_HEADER)).withContext(url).toBe('123456');
    req.flush({});
  }

  it('stepUpRequestOptions marks the request, and sends the header only when a code is given', () => {
    const withCode = stepUpRequestOptions('123456');
    expect(withCode.context.get(STEP_UP_REQUEST)).toBeTrue();
    expect(withCode.headers.get(MFA_TOKEN_HEADER)).toBe('123456');

    const withoutCode = stepUpRequestOptions(undefined);
    expect(withoutCode.context.get(STEP_UP_REQUEST)).toBeTrue();
    expect(withoutCode.headers.has(MFA_TOKEN_HEADER)).toBeFalse();
  });

  it('the emergency controls are marked', () => {
    const emergency = TestBed.inject(EmergencyControlService);
    emergency.forceLogoutAll({ reason: 'incident' } as never, '123456').subscribe();
    expectMarked('/api/super-admin/emergency/force-logout-all');
  });

  it('the organization lifecycle actions are marked', () => {
    const organizations = TestBed.inject(OrganizationService);
    organizations.suspend('org-1', { reason: 'audit' }, '123456').subscribe();
    expectMarked('/super-admin/organizations/org-1/suspend');
  });

  it('the hospital lifecycle step-up actions are marked', () => {
    const hospitals = TestBed.inject(HospitalLifecycleService);
    hospitals.suspend('h-1', { reason: 'audit' }, '123456').subscribe();
    const req = httpMock.expectOne((r) => r.url.endsWith('/h-1/suspend'));
    expect(req.request.context.get(STEP_UP_REQUEST)).toBeTrue();
    req.flush({});
  });

  describe('isStepUpRefusal', () => {
    it('a 403 with an mfa.stepup reason is one; any other 403 is not', () => {
      const stepUp = new HttpErrorResponse({
        status: 403,
        error: { reason: 'mfa.stepup.invalid' },
      });
      const scope = new HttpErrorResponse({ status: 403, error: { message: 'Access denied' } });
      expect(isStepUpRefusal(stepUp)).toBeTrue();
      expect(isStepUpRefusal(scope)).toBeFalse();
    });

    it('a 401 naming MFA is one; an expired session is not', () => {
      const mfa = new HttpErrorResponse({
        status: 401,
        error: { message: 'mfa_required: invalid code' },
      });
      const expired = new HttpErrorResponse({ status: 401, error: { message: 'Token expired' } });
      expect(isStepUpRefusal(mfa)).toBeTrue();
      expect(isStepUpRefusal(expired)).toBeFalse();
    });
  });
});
