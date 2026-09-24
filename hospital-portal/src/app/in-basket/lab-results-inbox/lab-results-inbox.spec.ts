import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { LabResultsInboxComponent } from './lab-results-inbox';
import { DashboardService, DoctorResultQueueItem } from '../../services/dashboard.service';

describe('LabResultsInboxComponent', () => {
  let fixture: ComponentFixture<LabResultsInboxComponent>;
  let component: LabResultsInboxComponent;
  let dashboardService: jasmine.SpyObj<DashboardService>;

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

  function setup(queue: DoctorResultQueueItem[] | 'error'): void {
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
    setup([item({ abnormalFlag: 'ABNORMAL_HIGH', testName: 'Potassium' })]);

    expect(component.flagKey(item({ abnormalFlag: 'ABNORMAL_HIGH' }))).toBe('inBasket.labUnknown');
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
        item({ id: 'u-' + i, abnormalFlag: 'ABNORMAL_HIGH', testName: 'Calcium' }),
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

  it('draws no truncation line on a short queue', () => {
    setup([item()]);

    expect(component.truncated()).toBeFalse();
    expect(fixture.nativeElement.textContent).not.toContain('inBasket.labTruncated');
  });

  it('returns an empty string rather than "Invalid Date" for a missing timestamp', () => {
    setup([item({ resultedAt: '' })]);

    expect(component.formatDate('')).toBe('');
    expect(component.formatDate(undefined)).toBe('');
    expect(component.formatDate('not-a-date')).toBe('');
  });
});
