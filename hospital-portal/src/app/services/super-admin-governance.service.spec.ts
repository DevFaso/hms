import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { SuperAdminGovernanceService } from './super-admin-governance.service';

describe('SuperAdminGovernanceService', () => {
  let service: SuperAdminGovernanceService;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), SuperAdminGovernanceService],
    });
    service = TestBed.inject(SuperAdminGovernanceService);
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  it('POSTs a user import as JSON with embedded CSV', () => {
    service
      .importUsers({ csvContent: 'username,email\nj,j@x.org', forcePasswordChange: true })
      .subscribe((res) => expect(res.imported).toBe(1));
    const req = httpMock.expectOne('/super-admin/users/import');
    expect(req.request.method).toBe('POST');
    expect(typeof req.request.body.csvContent).toBe('string');
    req.flush({ processed: 1, imported: 1, failed: 0, results: [] });
  });

  it('POSTs a force password reset with identifier lists', () => {
    service
      .forcePasswordReset({ emails: ['a@x.org'], usernames: ['bob'], sendEmail: true })
      .subscribe();
    const req = httpMock.expectOne('/super-admin/users/force-password-reset');
    expect(req.request.body.emails).toEqual(['a@x.org']);
    expect(req.request.body.usernames).toEqual(['bob']);
    req.flush({ requested: 2, succeeded: 2, results: [] });
  });

  it('GETs credential health as a bare array', () => {
    service.credentialHealth().subscribe((rows) => expect(rows.length).toBe(1));
    const req = httpMock.expectOne('/super-admin/credentials/health');
    expect(req.request.method).toBe('GET');
    req.flush([
      {
        userId: 'u1',
        active: true,
        forcePasswordChange: false,
        forceUsernameChange: false,
        mfaEnrolledCount: 0,
        verifiedMfaCount: 0,
        hasPrimaryMfa: false,
        recoveryContactCount: 0,
        verifiedRecoveryContacts: 0,
        hasPrimaryRecoveryContact: false,
        mfaEnrollments: [],
        recoveryContacts: [],
      },
    ]);
  });

  it('GETs the latest baseline export as a base64 envelope', () => {
    service.exportLatestBaseline().subscribe((exp) => {
      expect(exp.fileName).toBe('security-policy-baseline-v1.json');
      expect(exp.base64Content).toBe('e30=');
    });
    const req = httpMock.expectOne('/super-admin/security/policies/export/latest');
    expect(req.request.method).toBe('GET');
    req.flush({
      baselineVersion: 'v1',
      fileName: 'security-policy-baseline-v1.json',
      contentType: 'application/json',
      base64Content: 'e30=',
      generatedAt: '2026-01-01T00:00:00Z',
    });
  });

  it('POSTs a template import with only the templateCode', () => {
    service.importTemplate('RBAC_GLOBAL').subscribe();
    const req = httpMock.expectOne('/super-admin/security/rules/templates/import');
    expect(req.request.body).toEqual({ templateCode: 'RBAC_GLOBAL' });
    req.flush({
      templateCode: 'RBAC_GLOBAL',
      templateTitle: 'Global RBAC persona controls',
      importedRuleCount: 2,
      ruleSet: { id: 'rs1', code: 'RBAC', name: 'RBAC', enforcementScope: 'GLOBAL', rules: [] },
      importedRules: [],
      importedAt: '2026-01-01T00:00:00Z',
    });
  });

  it('POSTs a simulation with scenario and rules', () => {
    service
      .simulate({
        scenario: 'MFA rollout',
        rules: [{ name: 'Adaptive MFA', code: 'SESSION-MFA', ruleType: 'TWO_FACTOR_AUTH' }],
      })
      .subscribe((res) => expect(res.impactScore).toBe(3.75));
    const req = httpMock.expectOne('/super-admin/security/rules/simulations');
    expect(req.request.body.rules.length).toBe(1);
    req.flush({
      scenario: 'MFA rollout',
      evaluatedRuleCount: 1,
      impactScore: 3.75,
      impactedControllers: ['AuthController'],
      recommendedActions: [],
      evaluatedAt: '2026-01-01T00:00:00Z',
    });
  });
});
