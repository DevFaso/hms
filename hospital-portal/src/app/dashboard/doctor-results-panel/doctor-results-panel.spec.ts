import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';

import { DoctorResultsPanelComponent } from './doctor-results-panel';
import { DoctorResultQueueItem } from '../../services/dashboard.service';

/**
 * `DashboardService.getResultReviewQueue` no longer turns a failure into an
 * empty array, because a 403 or an outage drawn as "all results reviewed" is
 * how a released result reaches nobody. This panel is the copy of that queue
 * most physicians actually look at, so it needs the explicit state too.
 */
describe('DoctorResultsPanelComponent', () => {
  let fixture: ComponentFixture<DoctorResultsPanelComponent>;

  function item(): DoctorResultQueueItem {
    return {
      id: 'r-1',
      patientName: 'Awa Traoré',
      patientId: 'p-1',
      testName: 'Hémoglobine',
      resultValue: '9.2 g/dL',
      abnormalFlag: 'CRITICAL',
      resultedAt: '2026-09-20T09:30:00Z',
    };
  }

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [DoctorResultsPanelComponent, TranslateModule.forRoot()],
      providers: [provideRouter([])],
    }).compileComponents();

    fixture = TestBed.createComponent(DoctorResultsPanelComponent);
  });

  it('renders the empty state when the queue is genuinely empty', () => {
    fixture.componentRef.setInput('results', []);
    fixture.componentRef.setInput('loadError', false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.rp-empty')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.rp-error')).toBeNull();
  });

  it('renders an error state instead of "all reviewed" when the read failed', () => {
    fixture.componentRef.setInput('results', []);
    fixture.componentRef.setInput('loadError', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.rp-error')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.rp-empty')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('DASHBOARD.RESULTS_LOAD_ERROR');
    expect(fixture.nativeElement.textContent).not.toContain('DASHBOARD.ALL_RESULTS_REVIEWED');
  });

  it('asks the host to re-read from the error state', () => {
    fixture.componentRef.setInput('results', []);
    fixture.componentRef.setInput('loadError', true);
    fixture.detectChanges();

    const emitted = jasmine.createSpy('reloadRequested');
    fixture.componentInstance.reloadRequested.subscribe(emitted);
    (fixture.nativeElement.querySelector('.rp-retry') as HTMLButtonElement).click();

    expect(emitted).toHaveBeenCalled();
  });

  it('shows a spinner rather than "all reviewed" while a read is in flight', () => {
    // Retry clears the error while `results` is still empty, so without this
    // the panel flipped to the green empty card for the whole request window.
    fixture.componentRef.setInput('results', []);
    fixture.componentRef.setInput('loadError', false);
    fixture.componentRef.setInput('loading', true);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.rp-loading')).not.toBeNull();
    expect(fixture.nativeElement.querySelector('.rp-empty')).toBeNull();
    expect(fixture.nativeElement.querySelector('.rp-error')).toBeNull();
  });

  it('renders the rows when there is no error', () => {
    fixture.componentRef.setInput('results', [item()]);
    fixture.componentRef.setInput('loadError', false);
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('.rp-error')).toBeNull();
    expect(fixture.nativeElement.textContent).toContain('Hémoglobine');
  });
});
