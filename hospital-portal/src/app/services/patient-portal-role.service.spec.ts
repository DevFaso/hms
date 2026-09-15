import { TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';

import { PatientPortalService, bareRole } from './patient-portal.service';

/**
 * Role tokens reach the portal in two spellings and one sentence.
 *
 * `security.roles.name` carries the `ROLE_` prefix, and the two writers of
 * `audit_event_logs.role_name` disagree: `WriteAuditInterceptor` strips it on
 * purpose, `AuditEventLogServiceImpl` stores it as it comes. Both forms are in
 * the column on every environment and old rows keep theirs, so the portal
 * normalises at the boundary — one bare token for `PORTAL.ENUM.ROLE` to key,
 * or null where the badge should not appear at all.
 */
describe('bareRole', () => {
  it('strips the prefix the RBAC table carries', () => {
    expect(bareRole('ROLE_DOCTOR')).toBe('DOCTOR');
    expect(bareRole('ROLE_LAB_SCIENTIST')).toBe('LAB_SCIENTIST');
  });

  it('leaves the bare form the write-audit interceptor already writes', () => {
    expect(bareRole('NURSE')).toBe('NURSE');
  });

  it('strips only the leading prefix', () => {
    // A role someone created called ROLE_ROLE_REVIEWER is still one strip.
    expect(bareRole('ROLE_ROLE_REVIEWER')).toBe('ROLE_REVIEWER');
  });

  it('keeps a custom role an admin created, for the prettifier to Title-Case', () => {
    expect(bareRole('ROLE_BLOOD_BANK_OFFICER')).toBe('BLOOD_BANK_OFFICER');
  });

  it('turns the "Unknown Role" sentence into no role at all', () => {
    // AuditEventLogServiceImpl stamps this literal when it cannot resolve one.
    // It is a sentence, not a token, so no key can cover it and the Title-Case
    // prettifier would render it in English on a French page.
    expect(bareRole('Unknown Role')).toBeNull();
  });

  it('treats blank and missing as no role', () => {
    expect(bareRole(null)).toBeNull();
    expect(bareRole(undefined)).toBeNull();
    expect(bareRole('   ')).toBeNull();
  });
});

describe('PatientPortalService — role tokens at the boundary', () => {
  let service: PatientPortalService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [PatientPortalService, provideHttpClient(withXhr()), provideHttpClientTesting()],
    });
    service = TestBed.inject(PatientPortalService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('normalises the actor role on the disclosure accounting', (done) => {
    service.getMyDisclosures().subscribe((accounting) => {
      expect(accounting.entries.map((e) => e.actorRole)).toEqual(['DOCTOR', 'NURSE', null]);
      // Nothing else on the entry is touched.
      expect(accounting.entries[0].actor).toBe('Dr Ouédraogo');
      done();
    });

    httpMock
      .expectOne((r) => r.url.endsWith('/me/patient/disclosures'))
      .flush({
        data: {
          entries: [
            { id: 'a-1', actor: 'Dr Ouédraogo', actorRole: 'ROLE_DOCTOR' },
            { id: 'a-2', actor: 'Awa Sawadogo', actorRole: 'NURSE' },
            { id: 'a-3', actor: 'système', actorRole: 'Unknown Role' },
          ],
          counts: {},
        },
      });
  });

  it('normalises the actor role on the access log', (done) => {
    service.getMyAccessLog().subscribe((entries) => {
      expect(entries.map((e) => e.actorRole)).toEqual(['DOCTOR']);
      done();
    });

    httpMock
      .expectOne((r) => r.url.endsWith('/me/patient/access-log'))
      .flush({ data: { content: [{ id: 'a-1', actorRole: 'ROLE_DOCTOR' }] } });
  });

  it('normalises the role on a bookable provider', (done) => {
    // getProvidersForDepartment sends assignment.getRole().getName(), which
    // always carries the prefix, and omits the key entirely when the provider
    // has no assignment.
    service.getSchedulingProviders('h-1', 'd-1').subscribe((providers) => {
      expect(providers.map((p) => p.role)).toEqual(['DOCTOR', undefined]);
      expect(providers[0].name).toBe('Dr Kaboré');
      done();
    });

    httpMock
      .expectOne((r) => r.url.includes('/departments/d-1/providers'))
      .flush({
        data: [
          { id: 'p-1', name: 'Dr Kaboré', role: 'ROLE_DOCTOR' },
          { id: 'p-2', name: 'Awa Sawadogo' },
        ],
      });
  });
});
