import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Subject, of, throwError } from 'rxjs';

import { LabResultsInboxComponent } from './lab-results-inbox';
import { RoleContextService } from '../../core/role-context.service';
import { DashboardService, DoctorResultQueueItem } from '../../services/dashboard.service';

describe('LabResultsInboxComponent', () => {
  let fixture: ComponentFixture<LabResultsInboxComponent>;
  let component: LabResultsInboxComponent;
  let dashboardService: jasmine.SpyObj<DashboardService>;
  let roleContext: RoleContextService;

  function item(overrides: Partial<DoctorResultQueueItem> = {}): DoctorResultQueueItem {
    return {
      id: 'r-1',
      patientName: 'Awa Traoré',
      patientId: 'p-1',
      testName: 'Hémoglobine',
      resultValue: '9.2 g/dL',
      abnormalFlag: 'ABNORMAL',
      resultedAt: '2026-09-20T09:30:00Z',
      orderingContext: 'Anémie',
      ...overrides,
    };
  }

  /**
   * The REAL `RoleContextService`, not `roleContextStub`: this category now
   * reads once per SCOPE, and the stub's pick is a plain variable that no
   * effect can observe. Only the real service's signals re-run the reaction
   * these specs are about.
   *
   * `hospitalId` defaults to a pinned hospital because the queue is scoped
   * now — an unpinned caller is refused by the endpoint, and the component
   * declines to ask, which is its own test below.
   */
  function setup(
    queue: DoctorResultQueueItem[] | 'error',
    hospitalId: string | null = 'h-1',
  ): void {
    dashboardService = jasmine.createSpyObj<DashboardService>('DashboardService', [
      'getResultReviewQueue',
    ]);
    dashboardService.getResultReviewQueue.and.returnValue(
      queue === 'error' ? throwError(() => new Error('403')) : of(queue),
    );

    TestBed.configureTestingModule({
      imports: [LabResultsInboxComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: DashboardService, useValue: dashboardService },
      ],
    });

    roleContext = TestBed.inject(RoleContextService);
    roleContext.activeHospitalId = hospitalId;

    fixture = TestBed.createComponent(LabResultsInboxComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  it('reads the review queue on init and lists the released results', () => {
    setup([item()]);

    expect(dashboardService.getResultReviewQueue).toHaveBeenCalledTimes(1);
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Hémoglobine');
    expect(text).toContain('9.2 g/dL');
    expect(text).toContain('Awa Traoré');
  });

  it('groups by severity with critical first', () => {
    setup([
      item({ id: 'r-n', abnormalFlag: 'NORMAL', testName: 'Glycémie' }),
      item({ id: 'r-c', abnormalFlag: 'CRITICAL', testName: 'Potassium' }),
      item({ id: 'r-a', abnormalFlag: 'ABNORMAL', testName: 'Hémoglobine' }),
    ]);

    const groups = component.groups();
    // OTHER ahead of NORMAL: this order is also the order visibleGroups
    // fills its cap in, and an unknown grade must outrank a normal one.
    expect(groups.map((g) => g.key)).toEqual(['CRITICAL', 'ABNORMAL', 'OTHER', 'NORMAL']);
    expect(groups[0].items.length).toBe(1);
    expect(groups[0].items[0].testName).toBe('Potassium');
    expect(groups[2].items.length).toBe(0);

    const rendered = Array.from(
      fixture.nativeElement.querySelectorAll('tbody.severity-group tr td:first-child'),
    ).map((cell) => (cell as HTMLElement).textContent?.trim());
    expect(rendered).toEqual(['Potassium', 'Hémoglobine', 'Glycémie']);
  });

  it('never labels an unrecognised grade "Normal"', () => {
    // AbnormalFlag.severity() cannot produce this today, but a second
    // producer of DoctorResultQueueItemDTO could — and an out-of-range
    // result shown to the ordering physician as "Normal" is the one
    // failure this worklist exists to prevent.
    setup([item({ abnormalFlag: 'INDETERMINATE', testName: 'Potassium' })]);

    expect(component.flagKey(item({ abnormalFlag: 'INDETERMINATE' }))).toBe('inBasket.labUnknown');
    const groups = component.groups();
    expect(groups.find((g) => g.key === 'NORMAL')?.items.length).toBe(0);
    expect(groups.find((g) => g.key === 'OTHER')?.items.length).toBe(1);

    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('Potassium');
    expect(text).toContain('inBasket.labUnknown');
    expect(text).not.toContain('inBasket.labNormal');
  });

  it('disables refresh while a read is in flight, so a stale snapshot cannot land last', () => {
    setup([item()]);
    const button = fixture.nativeElement.querySelector('.btn-refresh') as HTMLButtonElement;
    expect(button.disabled).toBeFalse();

    component.loading.set(true);
    fixture.detectChanges();

    expect(button.disabled).toBeTrue();
  });

  it('reads the directional abnormal grades as abnormal, not as unknown', () => {
    // Only the bare ABNORMAL arrives today; if a producer ever sends the
    // AbnormalFlag directions raw they must not rank below plain ABNORMAL.
    setup([
      item({ id: 'h', abnormalFlag: 'ABNORMAL_HIGH' }),
      item({ id: 'l', abnormalFlag: 'ABNORMAL_LOW' }),
    ]);

    expect(component.flagKey(item({ abnormalFlag: 'ABNORMAL_HIGH' }))).toBe('inBasket.labAbnormal');
    expect(component.flagClass(item({ abnormalFlag: 'ABNORMAL_LOW' }))).toBe(
      'flag-badge flag-abnormal',
    );
    expect(component.groups().find((g) => g.key === 'ABNORMAL')?.items.length).toBe(2);
    expect(component.groups().find((g) => g.key === 'OTHER')?.items.length).toBe(0);
  });

  it('marks a critical row structurally, not by colour alone', () => {
    setup([item({ abnormalFlag: 'CRITICAL' })]);

    expect(fixture.nativeElement.querySelector('tr.row-critical')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.flag-critical')).not.toBeNull();
  });

  it('renders the abnormal direction when the backend recorded one', () => {
    setup([item({ abnormalDirection: 'LOW' })]);

    expect(fixture.nativeElement.textContent).toContain('inBasket.labDirectionLow');
  });

  it('renders no direction when the backend sent none', () => {
    setup([item()]);

    expect(component.directionKey(item())).toBeNull();
    expect(fixture.nativeElement.querySelector('.result-direction')).toBeNull();
  });

  it('renders the empty state when there is nothing to review', () => {
    setup([]);

    expect(fixture.nativeElement.textContent).toContain('inBasket.labResultsEmpty');
    expect(fixture.nativeElement.querySelector('.error-state')).toBeNull();
    expect(fixture.nativeElement.querySelector('table')).toBeNull();
  });

  it('renders an error state — not an empty queue — when the read fails', () => {
    setup('error');

    expect(component.loadError()).toBeTrue();
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('inBasket.labResultsError');
    expect(text).not.toContain('inBasket.labResultsEmpty');
  });

  it('retries from the error state', () => {
    setup('error');

    dashboardService.getResultReviewQueue.and.returnValue(of([item()]));
    component.load();
    fixture.detectChanges();

    expect(component.loadError()).toBeFalse();
    expect(fixture.nativeElement.textContent).toContain('Hémoglobine');
  });

  it('caps the drawn list and says what it cut, filling CRITICAL first', () => {
    // The endpoint has no date window and no reviewed state, so the queue
    // only grows; the cap must never be what drops a critical row.
    const queue = [
      ...Array.from({ length: 3 }, (_, i) =>
        item({ id: 'c-' + i, abnormalFlag: 'CRITICAL', testName: 'Potassium' }),
      ),
      ...Array.from({ length: 4 }, (_, i) =>
        item({ id: 'u-' + i, abnormalFlag: 'INDETERMINATE', testName: 'Calcium' }),
      ),
      ...Array.from({ length: 60 }, (_, i) =>
        item({ id: 'n-' + i, abnormalFlag: 'NORMAL', testName: 'Glycémie' }),
      ),
    ];
    setup(queue);

    expect(component.truncated()).toBeTrue();
    const drawn = component.visibleGroups();
    expect(drawn.reduce((total, g) => total + g.items.length, 0)).toBe(component.maxVisible);
    expect(drawn.find((g) => g.key === 'CRITICAL')?.items.length).toBe(3);
    // An unknown grade is drawn before a normal one, so the cap cannot be
    // what drops it.
    expect(drawn.map((g) => g.key)).toEqual(['CRITICAL', 'ABNORMAL', 'OTHER', 'NORMAL']);
    expect(drawn.find((g) => g.key === 'OTHER')?.items.length).toBe(4);
    expect(fixture.nativeElement.textContent).toContain('inBasket.labTruncated');
    expect(
      fixture.nativeElement.querySelectorAll('tbody.severity-group tr td:first-child').length,
    ).toBe(component.maxVisible);
  });

  it('never caps the CRITICAL group, which has no paging to fall back on', () => {
    const queue = [
      ...Array.from({ length: 60 }, (_, i) =>
        item({ id: 'c-' + i, abnormalFlag: 'CRITICAL', testName: 'Potassium' }),
      ),
      ...Array.from({ length: 10 }, (_, i) =>
        item({ id: 'n-' + i, abnormalFlag: 'NORMAL', testName: 'Glycémie' }),
      ),
    ];
    setup(queue);

    const drawn = component.visibleGroups();
    expect(drawn.find((g) => g.key === 'CRITICAL')?.items.length).toBe(60);
    expect(drawn.find((g) => g.key === 'NORMAL')?.items.length).toBe(0);
    expect(component.visibleCount()).toBe(60);
    expect(component.truncated()).toBeTrue();
  });

  it('never caps the unknown-grade group either, however many abnormals precede it', () => {
    const queue = [
      ...Array.from({ length: 60 }, (_, i) =>
        item({ id: 'a-' + i, abnormalFlag: 'ABNORMAL', testName: 'Hémoglobine' }),
      ),
      ...Array.from({ length: 5 }, (_, i) =>
        item({ id: 'u-' + i, abnormalFlag: 'INDETERMINATE', testName: 'Calcium' }),
      ),
    ];
    setup(queue);

    const drawn = component.visibleGroups();
    expect(drawn.find((g) => g.key === 'OTHER')?.items.length).toBe(5);
    expect(drawn.find((g) => g.key === 'ABNORMAL')?.items.length).toBe(50);
    expect(component.truncated()).toBeTrue();
  });

  it('keeps the rows on screen when a refresh of a populated list fails', () => {
    setup([item()]);
    expect(fixture.nativeElement.querySelector('table')).not.toBeNull();

    dashboardService.getResultReviewQueue.and.returnValue(throwError(() => new Error('500')));
    component.load();
    fixture.detectChanges();

    // The rows the physician was working from are still drawn, and the
    // failure is stated above them rather than replacing them.
    expect(component.loadError()).toBeTrue();
    expect(fixture.nativeElement.querySelector('table')).not.toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Hémoglobine');
    expect(fixture.nativeElement.querySelector('.stale-banner')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.error-state')).toBeNull();
  });

  it('ignores a slow read that lands after a newer one', () => {
    // Both Retry controls are reachable from the error states, so two reads
    // can overlap: a slow failure landing last drew the stale banner over
    // current rows.
    setup([]);
    const slow = new Subject<DoctorResultQueueItem[]>();
    dashboardService.getResultReviewQueue.and.returnValue(slow.asObservable());
    component.load();

    dashboardService.getResultReviewQueue.and.returnValue(of([item()]));
    component.load();
    fixture.detectChanges();
    expect(component.results().length).toBe(1);

    // The first read finally fails — and must write nothing.
    slow.error(new Error('504'));
    fixture.detectChanges();

    expect(component.loadError()).toBeFalse();
    expect(component.results().length).toBe(1);
  });

  it('draws no truncation line on a short queue', () => {
    setup([item()]);

    expect(component.truncated()).toBeFalse();
    expect(fixture.nativeElement.textContent).not.toContain('inBasket.labTruncated');
  });

  it('renders a dash, not a blank cell or "Invalid Date", for a missing timestamp', () => {
    setup([item({ resultedAt: '' })]);

    expect(component.formatDate('')).toBe('—');
    expect(component.formatDate(undefined)).toBe('—');
    expect(component.formatDate('not-a-date')).toBe('—');
  });
  /*
   * ── The queue is hospital-scoped (PR #742) ──────────────────────────────
   *
   * `GET /me/results/review-queue` filters by the hospital the caller is
   * acting in and answers 404 when none resolves. A category that read once
   * on mount therefore showed one tenant's released results under another
   * tenant's scope, and showed an error card to a caller who simply had not
   * picked a hospital.
   */

  it('re-reads the queue when the hospital scope changes', () => {
    setup([item({ id: 'a-1', testName: 'Potassium' })], 'h-a');
    expect(dashboardService.getResultReviewQueue).toHaveBeenCalledTimes(1);

    dashboardService.getResultReviewQueue.and.returnValue(
      of([item({ id: 'b-1', testName: 'Créatinine' })]),
    );
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.scopeToHospital('h-b');
    fixture.detectChanges();

    expect(dashboardService.getResultReviewQueue).toHaveBeenCalledTimes(2);
    expect(component.results().map((r) => r.id)).toEqual(['b-1']);
  });

  it('drops the other tenant rows the moment the scope changes, not when the new read lands', () => {
    // The clinical point of the whole change: a released result at hospital A
    // must never sit on screen labelled as hospital B's worklist. A slow — or
    // failing — read must not be what decides that.
    setup([item({ id: 'a-1', testName: 'Potassium' })], 'h-a');
    expect(component.results().length).toBe(1);

    const slow = new Subject<DoctorResultQueueItem[]>();
    dashboardService.getResultReviewQueue.and.returnValue(slow.asObservable());
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.scopeToHospital('h-b');
    fixture.detectChanges();

    expect(component.results()).toEqual([]);
    expect(fixture.nativeElement.textContent).not.toContain('Potassium');

    // And the read that was in flight under the OLD scope cannot write back.
    slow.error(new Error('504'));
    fixture.detectChanges();
    expect(component.results()).toEqual([]);
  });

  it('asks the user to pick a hospital instead of reading without a scope', () => {
    setup([item()], null);

    expect(dashboardService.getResultReviewQueue).not.toHaveBeenCalled();
    expect(fixture.nativeElement.querySelector('[data-testid="scope-hint"]')).not.toBeNull();
    // Not an error card, and not an empty state claiming there is nothing to
    // review — the queue was never read.
    expect(fixture.nativeElement.querySelector('.error-state')).toBeNull();
    expect(fixture.nativeElement.querySelector('.empty-state')).toBeNull();
    expect(fixture.nativeElement.querySelector('.btn-refresh')).toBeNull();
  });

  it('offers the picker beside the hint, because this page has no scope bar above it', () => {
    // `/in-basket` is not `requiresHospitalScope` — it renders for every
    // clinical role and for a super-admin in global view — so the shell draws
    // no chip above it. A hint naming a control that is nowhere on screen is
    // not a remedy.
    setup([item()], null);
    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.markSuperAdminGlobalDefaults();
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="scope-hint"]')).not.toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="hospital-scope-chip"]'),
    ).not.toBeNull();

    // And it survives the pick: unmounting the chip would leave no way to
    // switch hospital, or to return to global view, from this page.
    roleContext.scopeToHospital('h-b');
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('[data-testid="scope-hint"]')).toBeNull();
    expect(
      fixture.nativeElement.querySelector('[data-testid="hospital-scope-chip"]'),
    ).not.toBeNull();
  });

  it('reads as soon as a hospital is picked, and clears the hint', () => {
    setup([item({ testName: 'Potassium' })], null);
    expect(dashboardService.getResultReviewQueue).not.toHaveBeenCalled();

    roleContext.setRoles(['ROLE_SUPER_ADMIN']);
    roleContext.scopeToHospital('h-b');
    fixture.detectChanges();

    expect(dashboardService.getResultReviewQueue).toHaveBeenCalledTimes(1);
    expect(fixture.nativeElement.querySelector('[data-testid="scope-hint"]')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Potassium');
  });

  it('refuses a manual reload while no hospital is in scope', () => {
    setup([item()], null);

    component.load();

    expect(dashboardService.getResultReviewQueue).not.toHaveBeenCalled();
    expect(component.loadError()).toBeFalse();
  });
});
