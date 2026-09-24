import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';

import { LabReleaseWorklistComponent } from './lab-release-worklist';
import { LabResultResponse } from '../../services/lab.service';
import { RoleContextService } from '../../core/role-context.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextStubState, roleContextStub } from '../../testing/role-context.stub';

/**
 * The release worklist (B14).
 *
 * Wave 1 made release a human act and gave it an endpoint; the portal called
 * nothing, so a result waiting for release was invisible on the web. These
 * specs pin the queue, who may act on it, and the two states a clinical
 * action must never blur: a release that happened and one that did not.
 */
describe('LabReleaseWorklistComponent', () => {
  let fixture: ComponentFixture<LabReleaseWorklistComponent>;
  let component: LabReleaseWorklistComponent;
  let httpMock: HttpTestingController;
  let toast: jasmine.SpyObj<ToastService>;
  let scope: RoleContextStubState;

  function result(overrides: Partial<LabResultResponse> = {}): LabResultResponse {
    return {
      id: 'result-1',
      labOrderId: 'order-1',
      labOrderCode: 'LAB-001',
      patientId: 'patient-1',
      patientFullName: 'John Doe',
      patientEmail: 'john@example.com',
      hospitalName: 'General Hospital',
      labTestName: 'Haemoglobin',
      resultValue: '9.1',
      resultUnit: 'g/dL',
      resultDate: '2026-09-20T08:00:00',
      notes: '',
      referenceRanges: [],
      severityFlag: 'LOW',
      acknowledged: false,
      acknowledgedAt: null,
      acknowledgedBy: null,
      released: false,
      releasedAt: null,
      releasedByFullName: null,
      signedAt: null,
      signedBy: null,
      signatureValue: null,
      signatureNotes: null,
      createdAt: '2026-09-20T08:00:00',
      updatedAt: '2026-09-20T08:00:00',
      ...overrides,
    } as LabResultResponse;
  }

  function setup(roles: string[]): void {
    scope = { superAdmin: false, hospitalId: 'h-1', roles: [...roles] };
    toast = jasmine.createSpyObj<ToastService>('ToastService', [
      'success',
      'error',
      'info',
      'warning',
    ]);

    TestBed.configureTestingModule({
      imports: [LabReleaseWorklistComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: RoleContextService, useValue: roleContextStub(scope) },
        { provide: ToastService, useValue: toast },
      ],
    });

    fixture = TestBed.createComponent(LabReleaseWorklistComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  }

  /** Answer the worklist fetch. Asserts the endpoint and its paging params. */
  function flushWorklist(rows: LabResultResponse[], totalElements = rows.length): void {
    const req = httpMock.expectOne(
      (r) => r.url === '/lab-results/pending-release' && r.method === 'GET',
    );
    expect(req.request.params.get('page')).toBe(String(component.pageIndex()));
    expect(req.request.params.get('size')).toBe(String(component.pageSize));
    req.flush({
      success: true,
      data: {
        content: rows,
        totalElements,
        totalPages: Math.max(1, Math.ceil(totalElements / component.pageSize)),
        number: component.pageIndex(),
      },
    });
    fixture.detectChanges();
  }

  function host(): HTMLElement {
    return fixture.nativeElement as HTMLElement;
  }

  afterEach(() => httpMock.verify());

  it('lists what is waiting, and offers release to a role that may release', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([result()]);

    expect(component.rows().length).toBe(1);
    expect(component.totalAwaiting()).toBe(1);

    const row = host().querySelector('[data-testid="release-row-result-1"]');
    expect(row?.textContent).toContain('John Doe');
    expect(row?.textContent).toContain('Haemoglobin');
    expect(row?.textContent).toContain('9.1');
    expect(row?.textContent).toContain('g/dL');
    expect(host().querySelector('[data-testid="release-action-result-1"]')).not.toBeNull();
  });

  it('withholds the release control from a role the release endpoint refuses', () => {
    // A technician and a quality manager are on the worklist's @PreAuthorize
    // but not on LabResultAuthority.RELEASE_ROLES: they read the queue and
    // cannot sign anything off. A control that 403s is worse than none.
    setup(['ROLE_LAB_TECHNICIAN']);
    fixture.detectChanges();
    flushWorklist([result()]);

    expect(component.canReleaseResult(result())).toBeFalse();
    expect(host().querySelector('[data-testid="release-action-result-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="release-none-result-1"]')).not.toBeNull();
    // And not the other refusal: this result is not released elsewhere, the
    // reader simply may not release it.
    expect(host().querySelector('[data-testid="release-elsewhere-result-1"]')).toBeNull();
  });

  it('names the other laboratory rather than the role when the row is theirs (B1)', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([result({ performingHospitalId: 'lab-b' })]);

    expect(component.releaseBelongsElsewhere(component.rows()[0])).toBeTrue();
    expect(host().querySelector('[data-testid="release-action-result-1"]')).toBeNull();
    expect(host().querySelector('[data-testid="release-elsewhere-result-1"]')).not.toBeNull();
  });

  it('re-reads the release authority after a hospital-scope change', () => {
    // The role snapshot defect: a authority captured at construction answers
    // for the hospital the user has left. The stub reads `state` live, as the
    // real RoleContextService does.
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([result()]);

    expect(component.canReleaseResult(result())).toBeTrue();

    scope.roles = ['ROLE_LAB_TECHNICIAN'];

    expect(component.canReleaseResult(result())).toBeFalse();
  });

  it('renders an empty state when nothing is waiting', () => {
    setup(['ROLE_LAB_MANAGER']);
    fixture.detectChanges();
    flushWorklist([]);

    expect(host().querySelector('[data-testid="release-empty"]')).not.toBeNull();
    expect(component.error()).toBeNull();
  });

  it('renders an error state rather than an empty queue when the read fails', () => {
    setup(['ROLE_LAB_MANAGER']);
    fixture.detectChanges();
    const req = httpMock.expectOne(
      (r) => r.url === '/lab-results/pending-release' && r.method === 'GET',
    );
    req.flush('boom', { status: 500, statusText: 'Server Error' });
    fixture.detectChanges();

    expect(component.error()).not.toBeNull();
    expect(host().querySelector('[data-testid="release-error"]')).not.toBeNull();
    // The distinction that matters: an outage must not read as "nothing to do".
    expect(host().querySelector('[data-testid="release-empty"]')).toBeNull();
  });

  it('confirms before releasing, and only then calls the endpoint', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([result()]);

    component.askRelease(result());
    fixture.detectChanges();
    expect(host().querySelector('[data-testid="release-confirm-modal"]')).not.toBeNull();
    // Opening the dialog is not the act: nothing has been sent yet.
    httpMock.expectNone((r) => r.method === 'POST');

    component.confirmRelease();
    const release = httpMock.expectOne(
      (r) => r.url === '/lab-results/result-1/release' && r.method === 'POST',
    );
    release.flush({ ...result(), released: true });
    flushWorklist([]);

    expect(component.rows().length).toBe(0);
    expect(toast.success).toHaveBeenCalled();
    expect(host().querySelector('[data-testid="release-just-released"]')).not.toBeNull();
  });

  it('keeps a row whose release failed, and says why', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([result()]);

    component.askRelease(result());
    component.confirmRelease();
    const release = httpMock.expectOne(
      (r) => r.url === '/lab-results/result-1/release' && r.method === 'POST',
    );
    release.flush(
      { message: 'This result is released by the performing laboratory.' },
      { status: 400, statusText: 'Bad Request' },
    );
    fixture.detectChanges();

    // No re-read, no optimistic removal: the result is not released, so the
    // row it is on must still be there to try again.
    expect(component.rows().length).toBe(1);
    expect(component.releasingId()).toBeNull();
    expect(component.releaseError()).toBe('This result is released by the performing laboratory.');
    expect(host().querySelector('[data-testid="release-confirm-error"]')?.textContent).toContain(
      'performing laboratory',
    );
    expect(toast.error).toHaveBeenCalled();
    expect(component.justReleased()).toBeNull();
  });

  it('flags a critical result on the queue', () => {
    setup(['ROLE_LAB_DIRECTOR']);
    fixture.detectChanges();
    flushWorklist([result({ criticalNotifiedAt: '2026-09-20T08:05:00' })]);

    expect(component.isCritical(component.rows()[0])).toBeTrue();
    expect(host().querySelector('[data-testid="release-critical-tag"]')).not.toBeNull();
  });
});
