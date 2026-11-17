import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule, TranslateService } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';
import { ReceptionCockpitComponent } from './reception-cockpit.component';
import {
  ReceptionService,
  ReceptionQueueItem,
  ReceptionDashboardSummary,
  InsuranceIssue,
  FlowBoard,
} from '../reception.service';
import { ToastService } from '../../core/toast.service';

describe('Reception dashboard', () => {
  let service: jasmine.SpyObj<ReceptionService>;
  const row = (name: string, status = 'ARRIVED'): ReceptionQueueItem => ({
    patientId: name,
    patientName: name,
    appointmentId: name,
    encounterId: null,
    mrn: 'CDX-1',
    dateOfBirth: null,
    appointmentTime: '09:00',
    providerName: 'Cdx Doctor',
    departmentName: 'Cdx Service',
    appointmentReason: 'Cdx Follow-up',
    status,
    waitMinutes: 20,
    hasInsuranceIssue: true,
    hasOutstandingBalance: false,
  });
  const summary = (date: string) =>
    ({
      date,
      scheduledToday: 1,
      arrivedCount: 1,
      inProgressCount: 0,
      walkInCount: 0,
    }) as ReceptionDashboardSummary;
  const flow = {
    scheduled: [],
    confirmed: [],
    arrived: [],
    inProgress: [],
    noShow: [],
    completed: [],
    walkIn: [],
  } as FlowBoard;
  beforeEach(() => {
    service = jasmine.createSpyObj('ReceptionService', [
      'getDashboardSummary',
      'getQueue',
      'getInsuranceIssues',
      'getPaymentsPending',
      'getFlowBoard',
    ]);
    service.getDashboardSummary.and.returnValue(of(summary('2026-11-07')));
    service.getQueue.and.returnValue(of([row('Cdx One'), row('Cdx Two', 'SCHEDULED')]));
    service.getInsuranceIssues.and.returnValue(of([]));
    service.getPaymentsPending.and.returnValue(of([]));
    service.getFlowBoard.and.returnValue(of(flow));
    TestBed.configureTestingModule({
      imports: [ReceptionCockpitComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: service },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error']),
        },
      ],
    });
  });
  it('formats long waits readably and never renders a missing value as NaN', () => {
    const fixture = TestBed.createComponent(ReceptionCockpitComponent);
    const translate = TestBed.inject(TranslateService);
    translate.setTranslation('en', {
      TRACKER: { DURATION_HM: '{{hours}}h {{minutes}}m', DURATION_M: '{{minutes}}m' },
    });
    translate.use('en');
    expect(fixture.componentInstance.formatWait(7089)).toBe('118h 9m');
    expect(fixture.componentInstance.formatWait(20)).toBe('20m');
    expect(fixture.componentInstance.formatWait(Number.NaN)).toBe('—');
  });
  it('combines local search, status and administrative alerts without changing loaded totals', () => {
    const fixture = TestBed.createComponent(ReceptionCockpitComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    c.searchTerm.set('two');
    c.statusFilter.set('SCHEDULED');
    c.attentionFilter.set('insurance');
    expect(c.filteredQueue().map((r) => r.patientName)).toEqual(['Cdx Two']);
    expect(c.queueItems().length).toBe(2);
    expect(service.getQueue.calls.count()).toBe(1);
    c.clearQueueFilters();
    expect(c.filteredQueue().length).toBe(2);
  });
  it('QA D1591: a row the patient pre-checked in from the portal says so', () => {
    service.getQueue.and.returnValue(
      of([
        { ...row('Cdx One'), preCheckedIn: true, preCheckInConsent: true },
        row('Cdx Two', 'SCHEDULED'),
      ]),
    );
    const fixture = TestBed.createComponent(ReceptionCockpitComponent);
    fixture.detectChanges();
    const chips = (fixture.nativeElement as HTMLElement).querySelectorAll(
      '[data-testid="queue-prechecked-in"]',
    );
    expect(chips.length).toBe(1);
    expect(chips[0].closest('tr')?.textContent).toContain('Cdx One');
  });
  it('keeps the selected calendar day in the overview heading', () => {
    const fixture = TestBed.createComponent(ReceptionCockpitComponent);
    fixture.componentInstance.selectedDate.set('2026-11-07');
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelector('.overview-heading span').textContent).toContain(
      'Nov 7, 2026',
    );
  });
  it('refuses late responses from an earlier date across all dashboard sources', () => {
    const oldQueue = new Subject<ReceptionQueueItem[]>(),
      oldSummary = new Subject<ReceptionDashboardSummary>(),
      oldInsurance = new Subject<InsuranceIssue[]>(),
      oldPayments = new Subject<ReceptionQueueItem[]>(),
      oldFlow = new Subject<FlowBoard>();
    service.getQueue.and.returnValue(oldQueue);
    service.getDashboardSummary.and.returnValue(oldSummary);
    service.getInsuranceIssues.and.returnValue(oldInsurance);
    service.getPaymentsPending.and.returnValue(oldPayments);
    service.getFlowBoard.and.returnValue(oldFlow);
    const fixture = TestBed.createComponent(ReceptionCockpitComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    service.getQueue.and.returnValue(of([row('Current')]));
    service.getDashboardSummary.and.returnValue(of(summary('2026-11-08')));
    service.getInsuranceIssues.and.returnValue(of([]));
    service.getPaymentsPending.and.returnValue(of([]));
    service.getFlowBoard.and.returnValue(of(flow));
    c.selectedDate.set('2026-11-08');
    c.onDateChange();
    oldQueue.next([row('Old')]);
    oldSummary.next(summary('2026-11-07'));
    oldInsurance.error(new Error('old'));
    oldPayments.next([row('Old')]);
    oldFlow.next({ ...flow, arrived: [row('Old')] });
    expect(c.summary()?.date).toBe('2026-11-08');
    expect(c.queueItems()[0].patientName).toBe('Current');
    expect(c.insuranceFailed()).toBeFalse();
    expect(c.paymentsPending()).toEqual([]);
    expect(c.flowBoard()?.arrived).toEqual([]);
  });
  it('renders an outage rather than an empty queue and recovers through retry', () => {
    service.getQueue.and.returnValue(throwError(() => new Error('offline')));
    const fixture = TestBed.createComponent(ReceptionCockpitComponent);
    fixture.detectChanges();
    const root: HTMLElement = fixture.nativeElement;
    expect(root.querySelector('[role="alert"]')).toBeTruthy();
    expect(root.querySelector('.empty-state')).toBeNull();
    service.getQueue.and.returnValue(of([row('Recovered')]));
    root.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    fixture.detectChanges();
    expect(root.querySelectorAll('tbody tr').length).toBe(1);
    fixture.componentInstance.searchTerm.set('missing');
    fixture.detectChanges();
    expect(root.querySelector('.empty-state')?.textContent).toContain('RECEPTION.NO_MATCHES');
  });
});
