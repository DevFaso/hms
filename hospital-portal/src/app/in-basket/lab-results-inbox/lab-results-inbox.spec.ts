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
    expect(groups.map((g) => g.key)).toEqual(['CRITICAL', 'ABNORMAL', 'NORMAL']);
    expect(groups[0].items.length).toBe(1);
    expect(groups[0].items[0].testName).toBe('Potassium');

    const rendered = Array.from(
      fixture.nativeElement.querySelectorAll('tbody.severity-group tr td:first-child'),
    ).map((cell) => (cell as HTMLElement).textContent?.trim());
    expect(rendered).toEqual(['Potassium', 'Hémoglobine', 'Glycémie']);
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

  it('returns an empty string rather than "Invalid Date" for a missing timestamp', () => {
    setup([item({ resultedAt: '' })]);

    expect(component.formatDate('')).toBe('');
    expect(component.formatDate(undefined)).toBe('');
    expect(component.formatDate('not-a-date')).toBe('');
  });
});
