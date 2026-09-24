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

  function setup(roles: string[], superAdmin = false): void {
    scope = { superAdmin, hospitalId: 'h-1', roles: [...roles] };
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

  it('shows the result date and when the row actually landed as two columns', () => {
    // An analyzer's OBX-14 can be hours before the ORU reaches us, and a
    // hand-entered result can be backdated: calling the result date an
    // arrival time misstates how long the row has been waiting.
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([
      result({ resultDate: '2026-09-20T08:00:00', createdAt: '2026-09-20T14:30:00' }),
    ]);

    const cells = Array.from(
      host().querySelectorAll('[data-testid="release-row-result-1"] td'),
    ).map((c) => c.textContent ?? '');
    expect(cells.some((t) => t.includes('8:00'))).toBeTrue();
    expect(cells.some((t) => t.includes('2:30'))).toBeTrue();
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
    // The localized key, not the backend's English sentence — the refusals
    // this endpoint produces are hard-coded English and one is a raw message
    // key, neither of which belongs in a French modal.
    expect(component.releaseError()).toBe('LAB_RELEASE.RELEASE_ERROR');
    expect(host().querySelector('[data-testid="release-confirm-error"]')).not.toBeNull();
    expect(toast.error).toHaveBeenCalled();
    expect(component.justReleased()).toBeNull();
  });

  it('drops the last release banner when the reader asks for a fresh look', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([result()]);

    component.askRelease(result());
    component.confirmRelease();
    httpMock
      .expectOne((r) => r.url === '/lab-results/result-1/release' && r.method === 'POST')
      .flush({ ...result(), released: true });
    flushWorklist([]);
    expect(component.justReleased()).not.toBeNull();

    // A refresh is a new look at the queue; a green "X released" left sitting
    // above it claims something just happened.
    component.refresh();
    flushWorklist([]);

    expect(component.justReleased()).toBeNull();
    expect(host().querySelector('[data-testid="release-just-released"]')).toBeNull();
  });

  it('does not let a read started before the release resurrect the released row', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    fixture.detectChanges();
    flushWorklist([result()]);

    // A refresh is in flight — its snapshot still holds the row — when the
    // release succeeds and triggers its own read. Believing whichever answer
    // arrives last put the released row back, with a live Release button.
    component.load();
    const stale = httpMock.expectOne(
      (r) => r.url === '/lab-results/pending-release' && r.method === 'GET',
    );

    component.askRelease(result());
    component.confirmRelease();
    httpMock
      .expectOne((r) => r.url === '/lab-results/result-1/release' && r.method === 'POST')
      .flush({ ...result(), released: true });
    flushWorklist([]);

    expect(stale.cancelled).toBeTrue();
    expect(component.rows().length).toBe(0);
  });

  it('serves a super-admin, whom the backend expands into the laboratory', () => {
    // RoleExpansion.SUPER_ADMIN_INHERITS grants ROLE_LAB_SCIENTIST, so the
    // worklist @PreAuthorize passes although it never names SUPER_ADMIN —
    // and SUPER_ADMIN is in RELEASE_ROLES, the one role that may always
    // release. Locking it out of its own queue was the first draft's bug.
    setup(['ROLE_SUPER_ADMIN'], true);
    fixture.detectChanges();
    flushWorklist([result({ performingHospitalId: 'lab-b' })]);

    expect(component.canReleaseResult(component.rows()[0])).toBeTrue();
  });

  it('flags a critical result on the queue', () => {
    setup(['ROLE_LAB_DIRECTOR']);
    fixture.detectChanges();
    flushWorklist([result({ criticalNotifiedAt: '2026-09-20T08:05:00' })]);

    expect(component.isCritical(component.rows()[0])).toBeTrue();
    expect(host().querySelector('[data-testid="release-critical-tag"]')).not.toBeNull();
  });
});
