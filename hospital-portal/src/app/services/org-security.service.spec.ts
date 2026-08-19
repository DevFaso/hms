import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { OrgSecurityService } from './org-security.service';

describe('OrgSecurityService', () => {
  let service: OrgSecurityService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), OrgSecurityService],
    });
    service = TestBed.inject(OrgSecurityService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('GETs policies as a bare array', () => {
    service.listPolicies().subscribe((list) => expect(list.length).toBe(1));
    const req = httpMock.expectOne('/security-policies');
    expect(req.request.method).toBe('GET');
    req.flush([
      {
        id: 'p1',
        name: 'MFA',
        code: 'MFA-1',
        policyType: 'MULTI_FACTOR_AUTH',
        active: true,
        enforceStrict: false,
        organizationId: 'o1',
        rules: [],
      },
    ]);
  });

  it('PUTs the full policy object on update (full-replace semantics)', () => {
    service
      .updatePolicy('p1', {
        name: 'MFA',
        code: 'MFA-1',
        description: 'kept',
        policyType: 'MULTI_FACTOR_AUTH',
        organizationId: 'o1',
        priority: 5,
        active: true,
        enforceStrict: true,
      })
      .subscribe();
    const req = httpMock.expectOne('/security-policies/p1');
    expect(req.request.method).toBe('PUT');
    expect(req.request.body.description).toBe('kept');
    expect(req.request.body.enforceStrict).toBeTrue();
    req.flush({
      id: 'p1',
      name: 'MFA',
      code: 'MFA-1',
      policyType: 'MULTI_FACTOR_AUTH',
      active: true,
      enforceStrict: true,
      organizationId: 'o1',
      rules: [],
    });
  });

  it('POSTs a rule tied to its policy', () => {
    service
      .createRule({
        name: 'Session timeout',
        code: 'SESSION-30',
        ruleType: 'SESSION_TIMEOUT',
        ruleValue: '{"minutes":30}',
        securityPolicyId: 'p1',
      })
      .subscribe();
    const req = httpMock.expectOne('/security-rules');
    expect(req.request.method).toBe('POST');
    expect(req.request.body.securityPolicyId).toBe('p1');
    req.flush({
      id: 'r1',
      name: 'Session timeout',
      code: 'SESSION-30',
      ruleType: 'SESSION_TIMEOUT',
      active: true,
      securityPolicyId: 'p1',
    });
  });

  it('DELETEs a policy (hard delete)', () => {
    service.deletePolicy('p1').subscribe();
    const req = httpMock.expectOne('/security-policies/p1');
    expect(req.request.method).toBe('DELETE');
    req.flush(null, { status: 204, statusText: 'No Content' });
  });
});
