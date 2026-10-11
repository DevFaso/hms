import { TestBed } from '@angular/core/testing';
import { HttpErrorResponse, provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';

import {
  emptyEvidence,
  evidenceOf,
  isEvidenceComplete,
  isNotAvailable,
  isProviderFacilityType,
  normaliseEvidence,
  serverMessage,
} from './provider.model';
import { HospitalLifecycleService } from './hospital-lifecycle.service';
import { ProviderPortalService } from './provider-portal.service';
import { STEP_UP_REQUEST, isStepUpAction, isStepUpRefusal } from './step-up';
import { providerFixture } from '../testing/provider-fixtures';
import { SuperAdminProviderService } from './super-admin-provider.service';

describe('provider model helpers', () => {
  it('evidenceOf copies the current evidence with optional blanks as empty strings', () => {
    const evidence = evidenceOf(providerFixture());
    expect(evidence.business.tradeName).toBe('');
    expect(evidence.business.address.section).toBe('');
    expect(evidence.business.address.secteur).toBe('12');
    expect(evidence.professional.licenceExpiresOn).toBe('');
    expect(isEvidenceComplete(evidence)).toBeTrue();
  });

  it('evidenceOf of a provider with no evidence is empty, and empty evidence is not complete', () => {
    const evidence = evidenceOf(providerFixture({ business: null, professional: null }));
    expect(evidence).toEqual(emptyEvidence());
    expect(isEvidenceComplete(evidence)).toBeFalse();
  });

  it('normaliseEvidence trims and sends optional blanks as null, never an expiry it was not given', () => {
    const evidence = evidenceOf(providerFixture());
    evidence.business.legalName = '  Pharmacie  ';
    const body = normaliseEvidence(evidence);
    expect(body.business.legalName).toBe('Pharmacie');
    expect(body.business.tradeName).toBeNull();
    expect(body.business.address.section).toBeNull();
    expect(body.professional.licenceExpiresOn).toBeNull();
    expect(body.professional.licenceIssuedOn).toBeNull();
  });

  it('classifies facility types and server answers', () => {
    expect(isProviderFacilityType('PHARMACY')).toBeTrue();
    expect(isProviderFacilityType('LABORATORY')).toBeTrue();
    expect(isProviderFacilityType('HOSPITAL')).toBeFalse();
    expect(isProviderFacilityType(null)).toBeFalse();
    expect(isNotAvailable(new HttpErrorResponse({ status: 404 }))).toBeTrue();
    expect(isNotAvailable(new HttpErrorResponse({ status: 500 }))).toBeFalse();
    expect(serverMessage({ error: { message: 'Refused' } })).toBe('Refused');
    expect(serverMessage({ error: { message: '  ' } })).toBeNull();
    expect(serverMessage({ error: 'text' })).toBeNull();
    expect(serverMessage(null)).toBeNull();
  });
});

describe('SuperAdminProviderService', () => {
  let service: SuperAdminProviderService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(SuperAdminProviderService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists with the filters it is given, and none it is not', () => {
    service.list({ type: 'LABORATORY', status: 'VERIFIED' }, 2, 20).subscribe();
    const filtered = http.expectOne((r) => r.url === '/super-admin/providers');
    expect(filtered.request.params.get('type')).toBe('LABORATORY');
    expect(filtered.request.params.get('status')).toBe('VERIFIED');
    expect(filtered.request.params.get('page')).toBe('2');
    filtered.flush({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 2 });

    service.list({ type: '', status: '' }).subscribe();
    const all = http.expectOne((r) => r.url === '/super-admin/providers');
    expect(all.request.params.has('type')).toBeFalse();
    expect(all.request.params.has('status')).toBeFalse();
    all.flush({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 });
  });

  it('reaches every provider endpoint', () => {
    const evidence = normaliseEvidence(emptyEvidence());
    service.get('a b').subscribe();
    http.expectOne('/super-admin/providers/a%20b').flush(providerFixture());
    service.history('p-1').subscribe();
    http.expectOne('/super-admin/providers/p-1/verifications').flush([]);
    service.create({ facilityType: 'PHARMACY', code: 'PH', ...evidence }).subscribe();
    expect(http.expectOne('/super-admin/providers').request.method).toBe('POST');
    service.verify('p-1', { ifuMatchesRccm: true, cnssMatchesRccm: true }).subscribe();
    expect(
      http.expectOne('/super-admin/providers/p-1/verify').request.body.ifuMatchesRccm,
    ).toBeTrue();
    service.reject('p-1', { reason: 'r' }).subscribe();
    http.expectOne('/super-admin/providers/p-1/reject').flush(providerFixture());
    service.resubmit('p-1', evidence).subscribe();
    http.expectOne('/super-admin/providers/p-1/resubmit').flush(providerFixture());
    service.revoke('p-1', { reason: 'r' }).subscribe();
    http.expectOne('/super-admin/providers/p-1/revoke').flush(providerFixture());
  });
});

describe('HospitalLifecycleService step-up', () => {
  it('sends the current code as X-Mfa-Token and marks the step-up; restore sends neither', () => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    const service = TestBed.inject(HospitalLifecycleService);
    const http = TestBed.inject(HttpTestingController);

    service.suspend('h 1', { reason: 'audit' }, ' 123456 ').subscribe();
    const suspend = http.expectOne('/super-admin/hospitals/h%201/suspend');
    expect(suspend.request.headers.get('X-Mfa-Token')).toBe('123456');
    expect(suspend.request.context.get(STEP_UP_REQUEST)).toBeTrue();
    suspend.flush({});

    service.archive('h', { reason: 'audit' }).subscribe();
    const archive = http.expectOne('/super-admin/hospitals/h/archive');
    expect(archive.request.headers.has('X-Mfa-Token')).toBeFalse();
    expect(archive.request.context.get(STEP_UP_REQUEST)).toBeTrue();
    archive.flush({});

    service.schedulePurge('h', { reason: 'r', scheduledFor: '2026-12-01' }, '999999').subscribe();
    expect(
      http.expectOne('/super-admin/hospitals/h/schedule-purge').request.headers.get('X-Mfa-Token'),
    ).toBe('999999');

    service.restore('h').subscribe();
    const restore = http.expectOne('/super-admin/hospitals/h/restore');
    expect(restore.request.context.get(STEP_UP_REQUEST)).toBeFalse();
    http.verify();
  });

  it('only a step-up refusal is exempt: a 403 with an mfa.stepup.* reason, a 401 naming MFA', () => {
    expect(
      isStepUpRefusal(
        new HttpErrorResponse({ status: 403, error: { reason: 'mfa.stepup.invalid-code' } }),
      ),
    ).toBeTrue();
    expect(
      isStepUpRefusal(
        new HttpErrorResponse({ status: 403, error: { code: 'mfa.stepup.required' } }),
      ),
    ).toBeTrue();
    // Any other 403 (a hospital-scope or a role refusal) is not one.
    expect(isStepUpRefusal(new HttpErrorResponse({ status: 403 }))).toBeFalse();
    expect(
      isStepUpRefusal(
        new HttpErrorResponse({
          status: 403,
          error: { code: 'hospital_scope_refused', reason: 'NOT_PERMITTED' },
        }),
      ),
    ).toBeFalse();
    expect(isStepUpAction('suspend')).toBeTrue();
    expect(isStepUpAction('restore')).toBeFalse();
    expect(
      isStepUpRefusal(
        new HttpErrorResponse({ status: 401, error: { message: 'mfa_required: missing' } }),
      ),
    ).toBeTrue();
    expect(
      isStepUpRefusal(new HttpErrorResponse({ status: 401, error: { code: 'MFA_STEP_UP' } })),
    ).toBeTrue();
    expect(
      isStepUpRefusal(new HttpErrorResponse({ status: 401, error: 'mfa required' })),
    ).toBeTrue();
    expect(
      isStepUpRefusal(new HttpErrorResponse({ status: 401, error: { message: 'expired' } })),
    ).toBeFalse();
    expect(isStepUpRefusal(new HttpErrorResponse({ status: 500 }))).toBeFalse();
  });
});

describe('ProviderPortalService', () => {
  let service: ProviderPortalService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(ProviderPortalService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('reaches every provider page endpoint with relative URLs', () => {
    service.settings().subscribe();
    http.expectOne('/provider/settings').flush({});
    service.profile().subscribe();
    http.expectOne('/provider/profile').flush({});
    service.updateProfile({ phoneNumber: '+22670000000' }).subscribe();
    expect(http.expectOne('/provider/profile').request.method).toBe('PUT');
    service.staff().subscribe();
    http.expectOne('/provider/staff').flush([]);
    service.deactivate('u-1').subscribe();
    expect(http.expectOne('/provider/staff/u-1/deactivate').request.method).toBe('POST');
    service.activate('u-1').subscribe();
    expect(http.expectOne('/provider/staff/u-1/activate').request.method).toBe('POST');
    service.audit(3, 50).subscribe();
    const audit = http.expectOne((r) => r.url === '/provider/audit');
    expect(audit.request.params.get('page')).toBe('3');
    expect(audit.request.params.get('size')).toBe('50');
    audit.flush({ entries: [], page: 3, size: 50, totalElements: 0, hasMore: false });
  });
});
