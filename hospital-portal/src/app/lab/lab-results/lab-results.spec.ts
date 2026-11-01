import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { signal } from '@angular/core';
import {
  LabResultsComponent,
  shortOrderCode,
  toLocalInputValue,
  toServerDateTime,
} from './lab-results';
import { AuthService } from '../../auth/auth.service';
import { LabOrderResponse, LabResultResponse } from '../../services/lab.service';
import { RoleContextService } from '../../core/role-context.service';
import { expandRoleEquivalents } from '../../core/role-equivalence';

function mockResult(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'result-1',
    labOrderId: 'order-1',
    labOrderCode: 'LAB-001',
    patientId: 'patient-1',
    patientFullName: 'John Doe',
    patientEmail: 'john@example.com',
    hospitalName: 'General Hospital',
    labTestName: 'Complete Blood Count',
    resultValue: '5.2',
    resultUnit: 'mg/dL',
    resultDate: '2025-01-15T10:00:00',
    notes: '',
    referenceRanges: [],
    trendHistory: [],
    severityFlag: 'NORMAL',
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
    createdAt: '2025-01-15T10:00:00',
    updatedAt: '2025-01-15T10:00:00',
    ...overrides,
  };
}

describe('LabResultsComponent', () => {
  let fixture: ComponentFixture<LabResultsComponent>;
  let component: LabResultsComponent;
  let httpMock: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LabResultsComponent, TranslateModule.forRoot()],
      providers: [provideHttpClient(withXhr()), provideHttpClientTesting(), provideRouter([])],
    });

    fixture = TestBed.createComponent(LabResultsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function flushInit(results: Record<string, unknown>[] = [mockResult()]): void {
    // listResults
    const resultsReq = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'GET');
    resultsReq.flush({
      data: { content: results, totalElements: results.length, totalPages: 1, number: 0 },
      success: true,
    });

    // listOrders
    const ordersReq = httpMock.expectOne((r) => r.url === '/lab-orders' && r.method === 'GET');
    ordersReq.flush({
      data: { content: [], totalElements: 0, totalPages: 0, number: 0 },
      success: true,
    });

    // getAssignments
    const assignReq = httpMock.expectOne((r) => r.url.includes('/assignments'));
    assignReq.flush([{ id: 'assign-1', active: true }]);
  }

  it('should create the component', () => {
    expect(component).toBeTruthy();
    fixture.detectChanges();
    flushInit();
  });

  it('marks a result no range in its unit could grade as "not graded: units differ"', () => {
    fixture.detectChanges();
    flushInit([mockResult({ severityFlag: 'UNSPECIFIED', unitMismatch: true })]);
    fixture.detectChanges();

    const marker = (fixture.nativeElement as HTMLElement).querySelector(
      '[data-testid="unit-mismatch-result-1"]',
    );
    expect(marker?.textContent).toContain('LAB_RESULTS.NOT_GRADED_UNITS_DIFFER');
  });

  it('shows the severity, not the marker, for a result graded in its own unit', () => {
    fixture.detectChanges();
    flushInit([mockResult({ severityFlag: 'HIGH', unitMismatch: false })]);
    fixture.detectChanges();

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="unit-mismatch-result-1"]')).toBeNull();
    // D61: the flag is a label (enumLabel), never the raw wire token.
    expect(host.querySelector('.severity-high')?.textContent?.trim()).toBe('High');
  });

  it('should load results on init', () => {
    fixture.detectChanges();
    flushInit();

    expect(component.loading()).toBeFalse();
    expect(component.results().length).toBe(1);
    expect(component.stats().total).toBe(1);
  });

  it('should compute stats correctly', () => {
    fixture.detectChanges();
    flushInit([
      mockResult({ id: 'r-1', released: true }),
      mockResult({ id: 'r-2', released: false }),
      mockResult({ id: 'r-3', released: false }),
    ]);

    expect(component.stats().total).toBe(3);
    expect(component.stats().released).toBe(1);
    expect(component.stats().pending).toBe(2);
  });

  it('should filter by tab', () => {
    fixture.detectChanges();
    flushInit([
      mockResult({ id: 'r-1', released: true }),
      mockResult({ id: 'r-2', released: false }),
    ]);

    component.setTab('released');
    expect(component.filtered().length).toBe(1);

    component.setTab('pending');
    expect(component.filtered().length).toBe(1);

    component.setTab('all');
    expect(component.filtered().length).toBe(2);
  });

  it('should filter by search term', () => {
    fixture.detectChanges();
    flushInit([
      mockResult({ id: 'r-1', patientFullName: 'Alice Smith' }),
      mockResult({ id: 'r-2', patientFullName: 'Bob Jones' }),
    ]);

    component.searchTerm = 'alice';
    component.applyFilter();
    expect(component.filtered().length).toBe(1);
    expect(component.filtered()[0].patientFullName).toBe('Alice Smith');
  });

  it('QA D968: the detail panel takes focus, keeps Tab inside, closes on Escape and gives focus back', async () => {
    fixture.detectChanges();
    flushInit();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const trigger = host.querySelector<HTMLButtonElement>('.view-link')!;
    trigger.focus();
    trigger.click();
    fixture.detectChanges();
    await fixture.whenStable();
    await new Promise((resolve) => setTimeout(resolve));

    const dialog = host.querySelector<HTMLElement>('.detail-panel[role="dialog"]')!;
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-labelledby')).toBeTruthy();
    expect(dialog.contains(document.activeElement)).withContext('focus moved in').toBeTrue();
    // Tab from the last element comes back to the first instead of walking to
    // the page behind (round 22: the shared appModalDialog trap replaced
    // cdkTrapFocus, so the dialog also keeps typed text on Escape).
    const focusables = Array.from(
      dialog.querySelectorAll<HTMLElement>(
        'button:not([disabled]), a[href], input, select, textarea',
      ),
    );
    const last = focusables[focusables.length - 1];
    last.focus();
    const tab = new KeyboardEvent('keydown', { key: 'Tab', bubbles: true, cancelable: true });
    last.dispatchEvent(tab);
    expect(tab.defaultPrevented).withContext('Tab is kept inside the dialog').toBeTrue();
    expect(document.activeElement).toBe(focusables[0]);

    (document.activeElement as HTMLElement).dispatchEvent(
      new KeyboardEvent('keydown', { key: 'Escape', bubbles: true }),
    );
    fixture.detectChanges();
    await fixture.whenStable();
    expect(component.selectedResult()).toBeNull();
    expect(document.activeElement).withContext('focus returned to the trigger').toBe(trigger);
  });

  describe('D81: released results', () => {
    function allowEntry(): void {
      spyOn(
        component as unknown as { canEnterResults: () => boolean },
        'canEnterResults',
      ).and.returnValue(true);
    }

    it('hides Edit and Delete on a released result and offers a correction instead', () => {
      allowEntry();
      fixture.detectChanges();
      flushInit([mockResult({ released: true })]);
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('.delete-link')).toBeNull();
      expect(host.querySelector('[data-testid="correct-result-1"]')).not.toBeNull();
    });

    it('opens the create modal on the same order, naming the released result', () => {
      allowEntry();
      fixture.detectChanges();
      flushInit([mockResult({ released: true })]);

      component.openCorrection(component.results()[0]);

      expect(component.showModal()).toBeTrue();
      expect(component.editing()).toBeFalse();
      expect(component.form.labOrderId).toBe('order-1');
      expect(component.form.supersedesResultId).toBe('result-1');
      expect(component.form.resultValue).toBe('');
    });

    it('marks a superseded result and offers no second correction of it', () => {
      allowEntry();
      fixture.detectChanges();
      flushInit([mockResult({ released: true, supersededByResultId: 'result-2' })]);
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="superseded-result-1"]')).not.toBeNull();
      expect(host.querySelector('[data-testid="correct-result-1"]')).toBeNull();
    });

    it('QA D931: keeps a released correction marked "Corrected", in the list and the detail', () => {
      fixture.detectChanges();
      flushInit([mockResult({ released: true, supersedesResultId: 'result-0' })]);
      fixture.detectChanges();

      const host = fixture.nativeElement as HTMLElement;
      expect(host.querySelector('[data-testid="corrected-result-1"]')?.textContent).toContain(
        'LAB_RESULTS.CORRECTED',
      );
      component.viewResult(component.results()[0]);
      fixture.detectChanges();
      expect(host.querySelector('[data-testid="detail-corrected"]')).not.toBeNull();
    });
  });

  // QA D1205 / D1167 / D1137 (round 22): "RectificationEn attente de publication".
  it('QA D1205: every badge pair of the status cell is separated by a word space', () => {
    fixture.detectChanges();
    flushInit([
      mockResult({ id: 'r-pend', released: false, supersedesResultId: 'result-0' }),
      mockResult({
        id: 'r-rel',
        released: true,
        supersedesResultId: 'result-0',
        supersededByResultId: 'result-9',
      }),
    ]);
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    const cells = Array.from(host.querySelectorAll<HTMLElement>('tbody tr')).map((tr) =>
      Array.from(tr.querySelectorAll('td')).find((td) => td.querySelector('.status-badge')),
    );
    const pending = cells[0]!.textContent!.replace(/\s+/g, ' ').trim();
    // QA D1222: the row pill has its own singular key (es "Pendiente de publicación").
    expect(pending).toBe('LAB_RESULTS.CORRECTION LAB_RESULTS.ROW_PENDING_RELEASE');
    const released = cells[1]!.textContent!.replace(/\s+/g, ' ').trim();
    expect(released).toContain('LAB_RESULTS.RELEASED LAB_RESULTS.SUPERSEDED LAB_RESULTS.CORRECTED');
  });

  it('should open create modal', () => {
    fixture.detectChanges();
    flushInit();

    component.openCreate();
    expect(component.showModal()).toBeTrue();
    expect(component.editing()).toBeFalse();
    expect(component.editingId()).toBeNull();
  });

  it('should open edit modal', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.openEdit(result);

    expect(component.showModal()).toBeTrue();
    expect(component.editing()).toBeTrue();
    expect(component.editingId()).toBe('result-1');
    expect(component.form.resultValue).toBe('5.2');
  });

  it('should close modal', () => {
    fixture.detectChanges();
    flushInit();

    component.openCreate();
    expect(component.showModal()).toBeTrue();

    component.closeModal();
    expect(component.showModal()).toBeFalse();
  });

  it('should block submit when labOrderId is missing', () => {
    fixture.detectChanges();
    flushInit();

    component.openCreate();
    component.form.labOrderId = '';
    component.form.resultValue = '7.0';
    component.submitForm();

    // saving should not be set and no HTTP request should be made
    expect(component.saving()).toBeFalse();
  });

  it('should submit create form', () => {
    fixture.detectChanges();
    flushInit();

    // Populate orders so submitForm guard can derive patientId
    component.orders.set([{ id: 'order-1', patientId: 'patient-1' } as LabOrderResponse]);

    component.openCreate();
    component.form.labOrderId = 'order-1';
    component.form.resultValue = '7.0';
    component.form.resultDate = '2025-01-15T10:00';
    component.submitForm();

    const req = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'POST');
    req.flush({ id: 'new-result', resultValue: '7.0' });

    // reload triggered
    const reloadReq = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'GET');
    reloadReq.flush({
      data: { content: [mockResult({ id: 'new-result', resultValue: '7.0' })], totalElements: 1 },
      success: true,
    });

    expect(component.showModal()).toBeFalse();
    expect(component.saving()).toBeFalse();
  });

  it('should confirm and execute delete', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.confirmDelete(result);
    expect(component.showDeleteConfirm()).toBeTrue();
    expect(component.deletingResult()).toBe(result);

    component.executeDelete();
    const req = httpMock.expectOne('/lab-results/result-1');
    expect(req.request.method).toBe('DELETE');
    req.flush('Deleted');

    // reload triggered
    const reloadReq = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'GET');
    reloadReq.flush({ data: { content: [], totalElements: 0 }, success: true });

    expect(component.showDeleteConfirm()).toBeFalse();
    expect(component.deleting()).toBeFalse();
  });

  it('should cancel delete', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.confirmDelete(result);
    component.cancelDelete();

    expect(component.showDeleteConfirm()).toBeFalse();
    expect(component.deletingResult()).toBeNull();
  });

  it('should return correct severity class', () => {
    expect(component.getSeverityClass('CRITICAL')).toBe('severity-badge severity-critical');
    expect(component.getSeverityClass('HIGH')).toBe('severity-badge severity-high');
    expect(component.getSeverityClass('NORMAL')).toBe('severity-badge severity-normal');
    expect(component.getSeverityClass('UNKNOWN')).toBe('severity-badge');
  });

  it('should view and close detail panel', () => {
    fixture.detectChanges();
    flushInit();

    const result = component.results()[0];
    component.viewResult(result);
    expect(component.selectedResult()).toBe(result);

    component.closeDetail();
    expect(component.selectedResult()).toBeNull();
  });

  /* ── Critical-value read-back (P0 #5) ── */

  describe('critical-value read-back', () => {
    it('asks a critical result to be read back rather than merely acknowledged', () => {
      // canReadBack is role-derived and false in this harness; overriding it
      // is what makes the assertion about CRITICAL-vs-normal rather than about
      // whether the actions column renders at all.
      spyOn(component, 'canReadBack').and.returnValue(true);
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL' })]);
      fixture.detectChanges();

      expect(
        fixture.nativeElement.querySelector('[data-testid="read-back-result-1"]'),
      ).not.toBeNull();
    });

    it('renders persisted read-back evidence — including a mismatch — in the detail panel', () => {
      fixture.detectChanges();
      flushInit();

      // A repeated value with no read-back stamp is a persisted MISMATCH:
      // the record the REQUIRES_NEW write exists to preserve, previously
      // invisible to every UI.
      component.selectedResult.set(
        mockResult({
          criticalNotifiedAt: '2026-08-22T08:00:00',
          criticalReadBackValue: '9.1',
          criticalReadBackBy: 'Dr. Awa Traoré',
          criticalReadBackAt: null,
        }) as never,
      );
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('[data-testid="read-back-mismatch"]'),
      ).not.toBeNull();

      component.selectedResult.set(
        mockResult({
          criticalNotifiedAt: '2026-08-22T08:00:00',
          criticalReadBackValue: '8.9',
          criticalReadBackBy: 'Dr. Awa Traoré',
          criticalReadBackAt: '2026-08-22T08:05:00',
          acknowledged: true,
          acknowledgedAt: '2026-08-22T08:05:00',
          acknowledgedBy: 'Dr. Awa Traoré',
        }) as never,
      );
      fixture.detectChanges();
      expect(
        fixture.nativeElement.querySelector('[data-testid="read-back-evidence"]'),
      ).not.toBeNull();
      expect(fixture.nativeElement.querySelector('[data-testid="read-back-mismatch"]')).toBeNull();
    });

    it('translates the severity in the detail panel instead of the raw enum (QA D674)', () => {
      fixture.detectChanges();
      flushInit();
      component.selectedResult.set(mockResult({ severityFlag: 'CRITICAL' }) as never);
      fixture.detectChanges();
      const cell = fixture.nativeElement.querySelector('[data-testid="detail-severity"]');
      expect(cell).not.toBeNull();
      expect(cell.textContent).not.toContain('CRITICAL');
      expect(cell.textContent.trim()).toBe('Critical');
    });

    it('leaves a normal result with a plain acknowledge', () => {
      spyOn(component, 'canAcknowledge').and.returnValue(true);
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'NORMAL' })]);
      fixture.detectChanges();

      expect(fixture.nativeElement.querySelector('[data-testid="read-back-result-1"]')).toBeNull();
      expect(component.isCritical(component.results()[0])).toBeFalse();
    });

    it('never prefills the reported value into the read-back field', () => {
      // Prefilling would let the clinician confirm the number by copying it,
      // which defeats the entire check.
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL', resultValue: '7.1' })]);
      component.openReadBack(component.results()[0]);
      fixture.detectChanges();

      expect(component.readBackValue()).toBe('');
    });

    it('posts the repeated value and resolves the row on a match', () => {
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL', resultValue: '7.1' })]);
      component.openReadBack(component.results()[0]);
      component.readBackValue.set('7.1');
      component.submitReadBack();

      const req = httpMock.expectOne('/lab-results/result-1/critical-read-back');
      expect(req.request.method).toBe('POST');
      expect(req.request.body).toEqual({ repeatedValue: '7.1' });
      req.flush(mockResult({ severityFlag: 'CRITICAL', acknowledged: true }));

      expect(component.readBackTarget()).toBeNull();
    });

    it('surfaces the server message on a mismatch and keeps the dialog open', () => {
      fixture.detectChanges();
      flushInit([mockResult({ severityFlag: 'CRITICAL', resultValue: '7.1' })]);
      component.openReadBack(component.results()[0]);
      component.readBackValue.set('1.7');
      component.submitReadBack();

      httpMock
        .expectOne('/lab-results/result-1/critical-read-back')
        .flush(
          { message: 'Read-back does not match the reported result.' },
          { status: 400, statusText: 'Bad Request' },
        );

      // The clinician has to try again — silently closing would leave a
      // critical value unresolved and look like success.
      expect(component.readBackTarget()).not.toBeNull();
      expect(component.readBackSubmitting()).toBeFalse();
    });
  });
});

/**
 * Read-back last mile (P0 #5 re-verification findings, 2026-08-22): the
 * persisted read-back — above all a persisted MISMATCH — had no reader
 * surface anywhere, and the read-back button was shown to two lab roles the
 * backend 403s while the authorized admin roles never got it.
 */
describe('LabResultsComponent — read-back role gate', () => {
  function createWithRoles(activeRoles: string[]): LabResultsComponent {
    TestBed.configureTestingModule({
      imports: [LabResultsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            effectiveHospitalIdForRequest: () => 'h-1',
            // The real service's two rules: doctor equivalence by default,
            // and the exact variant the isDoctor-backed gates use.
            hasAnyActiveRole: (roles: string[]) =>
              expandRoleEquivalents(activeRoles).some((r) => roles.includes(r)),
            hasAnyActiveRoleExactly: (roles: string[]) =>
              roles.some((r) => activeRoles.includes(r)),
          },
        },
      ],
    });
    return TestBed.createComponent(LabResultsComponent).componentInstance;
  }

  it('QA D616: offers result delete only to the roles DELETE /lab-results admits', () => {
    expect(createWithRoles(['ROLE_LAB_TECHNICIAN']).canDeleteResult()).toBeFalse();
    TestBed.resetTestingModule();
    expect(createWithRoles(['ROLE_LAB_DIRECTOR']).canDeleteResult()).toBeFalse();
    TestBed.resetTestingModule();
    expect(createWithRoles(['ROLE_LAB_SCIENTIST']).canDeleteResult()).toBeTrue();
  });

  it('offers neither read-back nor acknowledge to a hospital admin (E9 #67)', () => {
    // Read-back is the ordering clinician's act and acknowledge is the
    // lab's; a hospital admin is administrative on the chart and gets no
    // control that would 403 on submit.
    const component = createWithRoles(['ROLE_HOSPITAL_ADMIN']);
    expect(component.canReadBack()).toBeFalse();
    expect(component.canAcknowledge()).toBeFalse();
  });

  it('offers the release control only where the backend accepts it (B1)', () => {
    // Releasing is the running laboratory's attestation of its own work.
    // Hospital h-1 sending an order out must not be offered the control for
    // it — the endpoint answers 400 — while the laboratory that ran it is.
    const component = createWithRoles(['ROLE_LAB_SCIENTIST']);
    const inHouse = { id: 'r1', released: false } as LabResultResponse;
    const sentOut = {
      id: 'r2',
      released: false,
      performingHospitalId: 'lab-b',
    } as LabResultResponse;
    const ranHere = {
      id: 'r3',
      released: false,
      performingHospitalId: 'h-1',
    } as LabResultResponse;

    expect(component.canReleaseResult(inHouse)).toBeTrue();
    expect(component.canReleaseResult(sentOut)).toBeFalse();
    expect(component.canReleaseResult(ranHere)).toBeTrue();
    expect(component.canReleaseResult({ ...inHouse, released: true })).toBeFalse();
  });

  it('keeps the release control for a super-admin in global view', () => {
    // The backend bypasses the hospital check for a super-admin, and in
    // global view there is no effective hospital id to compare — hiding the
    // control there would withhold it from the one role that always has it.
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [LabResultsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => true,
            globalView: signal(true),
            activeHospitalId: null,
            effectiveHospitalIdForRequest: () => null,
            hasAnyActiveRole: () => true,
          },
        },
      ],
    });
    const component = TestBed.createComponent(LabResultsComponent).componentInstance;

    expect(
      component.canReleaseResult({
        id: 'r1',
        released: false,
        performingHospitalId: 'lab-b',
      } as LabResultResponse),
    ).toBeTrue();
  });

  it('offers the release control to no one the release endpoint refuses', () => {
    const doctor = createWithRoles(['ROLE_DOCTOR']);
    expect(doctor.canReleaseResult({ id: 'r1', released: false } as LabResultResponse)).toBeFalse();
  });

  it('re-reads the sign authority after a hospital-scope change', () => {
    // `canSign` was a field computed once at construction, so a user who
    // switched hospital kept the sign button they had at the hospital they
    // left — and lost it where they did have it. The role context is the
    // live answer; reading it once is reading the wrong hospital's.
    const roles = ['ROLE_DOCTOR'];
    const component = createWithRoles(roles);
    expect(component.canSign()).toBeTrue();

    // The scope change: the active role set becomes the one held at the
    // newly selected hospital, where this user is a technician.
    roles.length = 0;
    roles.push('ROLE_LAB_TECHNICIAN');

    expect(component.canSign()).toBeFalse();
  });

  it('does NOT offer signing to a surgeon, whom the endpoint would refuse', () => {
    // RoleExpansion maps SURGEON onto ROLE_DOCTOR before the @PreAuthorize
    // runs, so the annotation passes — and then validateSignPermissions calls
    // RoleValidator.isDoctor, which matches the stored ASSIGNMENT ROLE CODE
    // against {DOCTOR, ROLE_DOCTOR} and knows no such equivalence. Offering
    // the button here means a filled-in signature modal answered 400 with no
    // way to succeed. Clearing the annotation is not clearing the endpoint.
    expect(createWithRoles(['ROLE_SURGEON']).canSign()).toBeFalse();
  });

  it('does NOT offer signing to a physician for the same reason', () => {
    expect(createWithRoles(['ROLE_PHYSICIAN']).canSign()).toBeFalse();
  });

  it('offers signing to a super-admin, who inherits both signing roles', () => {
    // SUPER_ADMIN_INHERITS grants ROLE_DOCTOR and ROLE_LAB_SCIENTIST, so the
    // sign endpoint accepts them — they were being offered Release on a row
    // and refused Sign on the same row.
    expect(createWithRoles(['ROLE_SUPER_ADMIN']).canSign()).toBeTrue();
  });

  it('does not offer read-back to the lab roles the backend refuses', () => {
    // Read-back is the ordering clinician confirming what they were told;
    // lab attestation is a different act. A button that 403s teaches the
    // lab to ignore controls.
    const component = createWithRoles(['ROLE_LAB_SCIENTIST']);
    expect(component.canReadBack()).toBeFalse();
    expect(component.canAcknowledge()).toBeTrue();
  });

  it('re-reads the critical-queue, acknowledge and read-back authority live (not a construction snapshot)', () => {
    // The three used to be fields evaluated once in the constructor — the
    // shape #722/#724 removed from release and sign on this screen.
    const roles = ['ROLE_LAB_SCIENTIST'];
    const component = createWithRoles(roles);
    expect(component.canSeeCritical()).toBeTrue();
    expect(component.canAcknowledge()).toBeTrue();
    expect(component.canReadBack()).toBeFalse();

    roles.length = 0;
    roles.push('ROLE_NURSE');
    expect(component.canReadBack()).toBeTrue();

    roles.length = 0;
    roles.push('ROLE_HOSPITAL_ADMIN');
    expect(component.canSeeCritical()).toBeFalse();
    expect(component.canAcknowledge()).toBeFalse();
    expect(component.canReadBack()).toBeFalse();
  });

  it('offers a surgeon the read-back a doctor gets, through the shared doctor equivalence', () => {
    expect(createWithRoles(['ROLE_SURGEON']).canReadBack()).toBeTrue();
  });

  it('does NOT offer result entry to a surgeon: validateLabResultAuthor is isDoctor-only', () => {
    // canEnterResults is protected; read it the way the template does.
    const component = createWithRoles(['ROLE_SURGEON']) as unknown as {
      canEnterResults(): boolean;
    };
    expect(component.canEnterResults()).toBeFalse();
  });
});

/** QA run 2026-10-17: result times (D49), the order picker (D57), self-release (D58). */
describe('LabResultsComponent — QA lab-results defects', () => {
  let fixture: ComponentFixture<LabResultsComponent>;
  let component: LabResultsComponent;
  let httpMock: HttpTestingController;

  const order = {
    id: '3f6c2a9e-1b2c-4d5e-8f90-123456789abc',
    labOrderCode: '3f6c2a9e-1b2c-4d5e-8f90-123456789abc',
    patientId: 'patient-1',
    patientFullName: 'Awa Traore',
    labTestName: 'Glucose',
    labTestCode: 'GLU',
    orderDatetime: '2026-10-17T14:00:00',
  } as LabOrderResponse;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [LabResultsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { getUserId: () => 'user-1' } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            effectiveHospitalIdForRequest: () => 'h-1',
            hasAnyActiveRole: () => true,
            hasAnyActiveRoleExactly: () => true,
          },
        },
      ],
    });
    fixture = TestBed.createComponent(LabResultsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => httpMock.verify());

  function flushInit(results: Record<string, unknown>[] = []): void {
    httpMock
      .expectOne((r) => r.url === '/lab-results' && r.method === 'GET')
      .flush({
        data: { content: results, totalElements: results.length, totalPages: 1, number: 0 },
        success: true,
      });
    httpMock
      .expectOne((r) => r.url === '/lab-orders' && r.method === 'GET')
      .flush({
        data: { content: [order], totalElements: 1, totalPages: 1, number: 0 },
        success: true,
      });
    httpMock
      .expectOne((r) => r.url.includes('/assignments'))
      .flush([{ id: 'assign-1', active: true }]);
  }

  it('D49: sends the entered wall-clock time as the server (UTC) clock, and reads it back', () => {
    const local = '2026-10-17T14:39';
    expect(toServerDateTime(local)).toBe(new Date(local).toISOString().slice(0, 19));
    expect(toLocalInputValue(toServerDateTime(local))).toBe(local);
    expect(toLocalInputValue('2026-10-17T18:32:52Z')).toBe(
      toLocalInputValue('2026-10-17T18:32:52'),
    );
  });

  it('D49: the create request carries the converted result time', () => {
    fixture.detectChanges();
    flushInit();
    component.openCreate();
    component.form.labOrderId = order.id;
    component.form.resultValue = '5.4';
    component.form.resultDate = '2026-10-17T14:39';

    component.submitForm();

    const req = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'POST');
    expect(req.request.body.resultDate).toBe(toServerDateTime('2026-10-17T14:39'));
    expect(component.form.resultDate).toBe('2026-10-17T14:39');
    req.flush({ id: 'r-new' });
    httpMock
      .expectOne((r) => r.url === '/lab-results' && r.method === 'GET')
      .flush({
        data: { content: [], totalElements: 0, totalPages: 1, number: 0 },
        success: true,
      });
  });

  it('D57: the order picker names the patient and the test, never the bare UUID', () => {
    fixture.detectChanges();
    flushInit();
    component.openCreate();
    fixture.detectChanges();

    const option = (fixture.nativeElement as HTMLElement).querySelector(
      `#lr-orderId option[value="${order.id}"]`,
    );
    expect(option).not.toBeNull();
    // TranslateModule.forRoot() echoes the key; the label is built from the key
    // and its params, so assert the key is used, not the raw UUID alone.
    expect(option?.textContent).toContain('LAB_RESULTS.ORDER_OPTION');
    expect(option?.textContent).not.toContain(order.labOrderCode);
    expect(shortOrderCode(order.labOrderCode)).toBe('#3F6C2A9E');
    expect(shortOrderCode(null)).toBe('—');
  });

  it('D58: releasing a result you entered asks first, then sends it as a self-release', () => {
    fixture.detectChanges();
    const mine = mockResult({ enteredByUserId: 'user-1' });
    flushInit([mine]);

    component.releaseResult(mine as unknown as LabResultResponse);
    fixture.detectChanges();
    httpMock.expectNone((r) => r.method === 'POST');
    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="self-release-modal"]'),
    ).not.toBeNull();

    component.confirmSelfRelease();
    const req = httpMock.expectOne(
      (r) => r.url === '/lab-results/result-1/release' && r.method === 'POST',
    );
    expect(req.request.params.get('selfRelease')).toBe('true');
    req.flush({ ...mine, released: true, selfReleased: true });
    httpMock
      .expectOne((r) => r.url === '/lab-results' && r.method === 'GET')
      .flush({
        data: { content: [], totalElements: 0, totalPages: 1, number: 0 },
        success: true,
      });
  });

  it("D58: a colleague's result is released at once, and a 409 turns into the confirmation", () => {
    fixture.detectChanges();
    const theirs = mockResult({ enteredByUserId: 'user-2' });
    flushInit([theirs]);

    component.releaseResult(theirs as unknown as LabResultResponse);
    const req = httpMock.expectOne(
      (r) => r.url === '/lab-results/result-1/release' && r.method === 'POST',
    );
    expect(req.request.params.has('selfRelease')).toBeFalse();
    req.flush({ message: 'self' }, { status: 409, statusText: 'Conflict' });

    expect(component.selfReleaseTarget()?.id).toBe('result-1');
  });

  it('D58: shows a self-released result as one', () => {
    fixture.detectChanges();
    flushInit([mockResult({ released: true, selfReleased: true })]);
    fixture.detectChanges();

    expect(
      (fixture.nativeElement as HTMLElement).querySelector('[data-testid="self-released-badge"]'),
    ).not.toBeNull();
  });
});

/**
 * QA D616 / D645 against the rendered page, not the computed alone: a spec that
 * only calls canDeleteResult() passes with the template gate removed.
 */
describe('LabResultsComponent — rendered delete gate and order picker', () => {
  let fixture: ComponentFixture<LabResultsComponent>;
  let component: LabResultsComponent;
  let httpMock: HttpTestingController;

  function setup(activeRoles: string[]): void {
    TestBed.configureTestingModule({
      imports: [LabResultsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: { getUserId: () => 'user-1' } },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: signal(false),
            globalView: signal(false),
            activeHospitalId: 'h-1',
            effectiveHospitalIdForRequest: () => 'h-1',
            hasAnyActiveRole: (roles: string[]) =>
              expandRoleEquivalents(activeRoles).some((r) => roles.includes(r)),
            hasAnyActiveRoleExactly: (roles: string[]) =>
              roles.some((r) => activeRoles.includes(r)),
          },
        },
      ],
    });
    fixture = TestBed.createComponent(LabResultsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  }

  afterEach(() => httpMock.verify());

  function flushInit(
    results: Record<string, unknown>[],
    orders: Partial<LabOrderResponse>[],
  ): void {
    fixture.detectChanges();
    httpMock
      .expectOne((r) => r.url === '/lab-results' && r.method === 'GET')
      .flush({
        data: { content: results, totalElements: results.length, totalPages: 1, number: 0 },
        success: true,
      });
    httpMock
      .expectOne((r) => r.url === '/lab-orders' && r.method === 'GET')
      .flush({
        data: { content: orders, totalElements: orders.length, totalPages: 1, number: 0 },
        success: true,
      });
    httpMock
      .expectOne((r) => r.url.includes('/assignments'))
      .flush([{ id: 'assign-1', active: true }]);
    fixture.detectChanges();
  }

  it('QA D616: a lab technician sees no delete button on an unreleased result', () => {
    setup(['ROLE_LAB_TECHNICIAN']);
    flushInit([mockResult({ id: 'r-1', released: false })], []);

    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('.compare-link')).withContext('the row is rendered').not.toBeNull();
    expect(host.querySelector('.delete-link')).toBeNull();
  });

  it('QA D616: a lab scientist sees the delete button on the same unreleased result', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    flushInit([mockResult({ id: 'r-1', released: false })], []);

    expect((fixture.nativeElement as HTMLElement).querySelector('.delete-link')).not.toBeNull();
  });

  it('QA D645: an order whose test no longer resolves is not offered in the result picker', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    flushInit(
      [],
      [
        { id: 'o-orphan', labOrderCode: 'o-orphan', patientId: 'p-1' },
        {
          id: 'o-glu',
          labOrderCode: 'o-glu',
          patientId: 'p-1',
          labTestName: 'Glucose',
          labTestCode: 'GLU',
        },
      ],
    );

    expect(component.orders().map((o) => o.id)).toEqual(['o-glu']);
  });

  it('QA D1204: "New result" offers no order whose specimen was never taken or was rejected', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    const order = (id: string, status: string) => ({
      id,
      labOrderCode: id,
      patientId: 'p-1',
      labTestName: 'NFS',
      labTestCode: 'CBC',
      status,
    });
    flushInit(
      [],
      [
        order('LAB-000029', 'ORDERED'), // never collected
        order('LAB-000032', 'PENDING'), // specimen rejected, back to awaiting collection
        order('o-collected', 'COLLECTED'),
        order('o-received', 'RECEIVED'),
        order('o-running', 'IN_PROGRESS'),
      ],
    );
    component.openCreate();
    fixture.detectChanges();
    const picker = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLOptionElement>(
        '#lr-orderId option',
      ),
    ).map((o) => o.value);
    expect(picker).toEqual(['', 'o-collected', 'o-received', 'o-running']);
  });

  it('QA D1176: "New result" offers no order that already has a result or is closed; a correction keeps its own', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    const order = (id: string, status: string) => ({
      id,
      labOrderCode: id,
      patientId: 'p-1',
      labTestName: 'Glucose',
      labTestCode: 'GLU',
      status,
    });
    flushInit(
      [],
      [
        order('o-new', 'COLLECTED'),
        order('o-resulted', 'RESULTED'),
        order('o-done', 'COMPLETED'),
        order('o-verified', 'VERIFIED'),
        order('o-cancelled', 'CANCELLED'),
      ],
    );
    expect(component.orders().map((o) => o.id)).toEqual(['o-new']);
    const options = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll('#lr-orderId option'),
    );
    // Rendered only once the modal is open.
    component.openCreate();
    fixture.detectChanges();
    const picker = Array.from(
      (fixture.nativeElement as HTMLElement).querySelectorAll<HTMLOptionElement>(
        '#lr-orderId option',
      ),
    ).map((o) => o.value);
    expect(options.length).toBe(0);
    expect(picker).toEqual(['', 'o-new']);
    component.closeModal();

    // Correcting the released result of a completed order keeps that order selectable.
    component.openCorrection(
      mockResult({ id: 'r-1', labOrderId: 'o-done', released: true }) as never,
    );
    expect(component.orders().map((o) => o.id)).toEqual(['o-new', 'o-done']);
    component.closeModal();
    expect(component.orders().map((o) => o.id)).toEqual(['o-new']);
  });

  it('QA D648: an order flagged as missing its definition stays out of the picker despite its label', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    flushInit(
      [],
      [
        {
          id: 'o-deleted',
          labOrderCode: 'o-deleted',
          patientId: 'p-1',
          labTestName: 'Examen supprimé',
          labTestDefinitionMissing: true,
        },
        {
          id: 'o-glu',
          labOrderCode: 'o-glu',
          patientId: 'p-1',
          labTestName: 'Glucose',
          labTestCode: 'GLU',
        },
      ],
    );

    expect(component.orders().map((o) => o.id)).toEqual(['o-glu']);
  });

  it('QA D1095: choosing an order prefills the unit from its test, and a blank unit is filled on save', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    flushInit(
      [],
      [
        {
          id: 'o-glu',
          labOrderCode: 'o-glu',
          patientId: 'patient-1',
          labTestName: 'Glucose',
          labTestCode: 'GLU',
          labTestUnit: 'g/L',
        },
      ],
    );

    component.form.labOrderId = 'o-glu';
    component.onOrderChange('o-glu');
    expect(component.form.resultUnit).toBe('g/L');
    expect(component.selectedTestUnit()).toBe('g/L');

    component.form.resultUnit = '   ';
    component.form.resultValue = '0,40';
    component.submitForm();
    const req = httpMock.expectOne((r) => r.url === '/lab-results' && r.method === 'POST');
    expect(req.request.body.resultUnit).toBe('g/L');
  });

  it('QA D1092: a result no range can grade is released only after the warning is confirmed', () => {
    setup(['ROLE_LAB_SCIENTIST']);
    const ungraded = mockResult({
      unitMismatch: true,
      enteredByUserId: 'user-2',
      resultUnit: 'mEq/L',
    });
    flushInit([ungraded], []);

    component.releaseResult(ungraded as unknown as LabResultResponse);
    fixture.detectChanges();
    httpMock.expectNone((r) => r.method === 'POST');
    const host = fixture.nativeElement as HTMLElement;
    expect(host.querySelector('[data-testid="release-ungraded-note"]')).not.toBeNull();

    component.confirmSelfRelease();
    const req = httpMock.expectOne(
      (r) => r.url === '/lab-results/result-1/release' && r.method === 'POST',
    );
    expect(req.request.params.get('ungradedConfirmed')).toBe('true');
    expect(req.request.params.has('selfRelease')).toBeFalse();
    req.flush({ ...ungraded, released: true });
    httpMock
      .expectOne((r) => r.url === '/lab-results' && r.method === 'GET')
      .flush({
        data: { content: [], totalElements: 0, totalPages: 1, number: 0 },
        success: true,
      });
  });
});
