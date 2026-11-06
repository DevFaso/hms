import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReceptionCockpitComponent } from './reception-cockpit.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import {
  InsuranceIssue,
  ReceptionDashboardSummary,
  ReceptionQueueItem,
  ReceptionService,
} from '../reception.service';
import { ToastService } from '../../core/toast.service';
import { of } from 'rxjs';

describe('ReceptionCockpitComponent', () => {
  let component: ReceptionCockpitComponent;
  let fixture: ComponentFixture<ReceptionCockpitComponent>;

  const mockReceptionService = {
    getDashboardSummary: () =>
      of({
        totalAppointments: 5,
        arrived: 2,
        inProgress: 1,
        waiting: 1,
        completed: 0,
        noShow: 0,
        walkInsToday: 1,
        avgWaitMinutes: 12,
        insuranceIssueCount: 0,
        pendingPayments: 0,
      }),
    getQueue: () => of([]),
    getInsuranceIssues: () => of([]),
    getPaymentsPending: () => of([]),
    getFlowBoard: () => of({ columns: [] }),
    updateEncounterStatus: () => of({}),
  };

  const mockToastService = {
    success: jasmine.createSpy('success'),
    error: jasmine.createSpy('error'),
  };

  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [ReceptionCockpitComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ReceptionService, useValue: mockReceptionService },
        { provide: ToastService, useValue: mockToastService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(ReceptionCockpitComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  describe('QA D1400 + D1401: every card opens rows equal to its number', () => {
    // A mixed day as the server answers it: each row carries the filters that open it.
    const row = (status: string, walkIn: boolean, filters: string[]): ReceptionQueueItem =>
      ({
        appointmentId: walkIn ? null : status + filters.length,
        patientId: status + (walkIn ? '-w' : '-a') + filters.length,
        patientName: status,
        status,
        queueFilters: filters,
        encounterId: 'e-' + status + filters.length,
        hasInsuranceIssue: true,
        hasOutstandingBalance: false,
        waitMinutes: 0,
      }) as unknown as ReceptionQueueItem;
    const rows = [
      row('SCHEDULED', false, ['SCHEDULED']),
      row('CONFIRMED', false, ['CONFIRMED', 'SCHEDULED']),
      row('ARRIVED', false, ['ARRIVED']),
      row('ARRIVED', true, ['ARRIVED', 'WALK_IN']),
      row('IN_PROGRESS', true, ['IN_PROGRESS', 'WALK_IN']),
      row('COMPLETED', true, ['COMPLETED', 'WALK_IN']),
    ];
    const issues = rows.map((r) => ({
      appointmentId: r.appointmentId,
      encounterId: r.encounterId,
      patientId: r.patientId,
      patientName: r.patientName,
      mrn: null,
      appointmentTime: null,
      issueType: 'MISSING_INSURANCE',
      providerName: null,
      departmentName: null,
    })) as InsuranceIssue[];

    beforeEach(() => {
      component.summary.set({
        scheduledToday: 2,
        arrivedCount: 2,
        inProgressCount: 1,
        walkInCount: 3,
      } as ReceptionDashboardSummary);
      component.queueItems.set(rows);
      component.insuranceIssues.set(issues);
      component.paymentsPending.set(rows.slice(0, 1));
      fixture.detectChanges();
    });

    it('walks every card on the strip, so a new card has to keep the rule too', () => {
      const root = fixture.nativeElement as HTMLElement;
      const cards = Array.from(root.querySelectorAll<HTMLButtonElement>('.sum-card'));
      expect(cards.length).toBe(6);
      for (const card of cards) {
        const shown = Number(card.querySelector('.sum-num')?.textContent);
        card.click();
        fixture.detectChanges();
        const opened = root.querySelectorAll('.tab-content tbody tr').length;
        expect(opened)
          .withContext(card.querySelector('.sum-label')?.textContent ?? '')
          .toBe(shown);
      }
    });

    it('Planifie opens its own filter, not Tous', () => {
      const root = fixture.nativeElement as HTMLElement;
      root.querySelector<HTMLButtonElement>('.sum-card')?.click();
      expect(component.statusFilter()).toBe('SCHEDULED');
    });

    it('Sans rendez-vous opens the visits with no appointment, whatever their status', () => {
      component.statusFilter.set('WALK_IN');
      expect(component.filteredQueue().map((i) => i.appointmentId)).toEqual([null, null, null]);
      expect(component.filteredQueue().map((i) => i.status)).toEqual([
        'ARRIVED',
        'IN_PROGRESS',
        'COMPLETED',
      ]);
    });
  });

  it('QA D261: every input rendered on the cockpit has an accessible name', () => {
    const root = fixture.nativeElement as HTMLElement;
    const inputs = Array.from(root.querySelectorAll('input, select, textarea'));
    expect(inputs.length).toBeGreaterThan(0);
    for (const el of inputs) {
      const id = el.getAttribute('id');
      const labelled =
        !!el.getAttribute('aria-label') ||
        !!el.getAttribute('aria-labelledby') ||
        !!el.closest('label') ||
        (!!id && !!root.querySelector(`label[for="${id}"]`));
      expect(labelled).withContext(el.outerHTML.slice(0, 120)).toBeTrue();
    }
  });

  it('should load all data on init', () => {
    expect(component.summary()).toBeTruthy();
    expect(component.queueItems()).toEqual([]);
  });

  it('should set default tab to queue', () => {
    expect(component.activeTab()).toBe('queue');
  });

  it('should return correct status class', () => {
    expect(component.statusClass('SCHEDULED')).toContain('badge-scheduled');
    expect(component.statusClass('ARRIVED')).toContain('badge-arrived');
    expect(component.statusClass('UNKNOWN')).toContain('badge-default');
  });

  it('should filter queue items by status', () => {
    component.queueItems.set([
      { appointmentId: '1', patientId: 'p1', patientName: 'A', status: 'SCHEDULED' } as any,
      { appointmentId: '2', patientId: 'p2', patientName: 'B', status: 'ARRIVED' } as any,
    ]);
    component.statusFilter.set('ARRIVED');
    expect(component.filteredQueue().length).toBe(1);
    expect(component.filteredQueue()[0].status).toBe('ARRIVED');
  });

  it('should show all items when filter is ALL', () => {
    component.queueItems.set([
      { appointmentId: '1', patientId: 'p1', patientName: 'A', status: 'SCHEDULED' } as any,
      { appointmentId: '2', patientId: 'p2', patientName: 'B', status: 'ARRIVED' } as any,
    ]);
    component.statusFilter.set('ALL');
    expect(component.filteredQueue().length).toBe(2);
  });

  // ── Check-In Dialog Tests ─────────────────────────────────────

  it('should allow check-in for SCHEDULED items with an appointmentId', () => {
    const item = {
      appointmentId: 'a1',
      patientId: 'p1',
      patientName: 'A',
      status: 'SCHEDULED',
    } as any;
    expect(component.canCheckIn(item)).toBeTrue();
  });

  it('should allow check-in for CONFIRMED items with an appointmentId', () => {
    const item = {
      appointmentId: 'a1',
      patientId: 'p1',
      patientName: 'A',
      status: 'CONFIRMED',
    } as any;
    expect(component.canCheckIn(item)).toBeTrue();
  });

  it('should not allow check-in for ARRIVED items', () => {
    const item = {
      appointmentId: 'a1',
      patientId: 'p1',
      patientName: 'A',
      status: 'ARRIVED',
    } as any;
    expect(component.canCheckIn(item)).toBeFalse();
  });

  it('should not allow check-in when appointmentId is null', () => {
    const item = {
      appointmentId: null,
      patientId: 'p1',
      patientName: 'A',
      status: 'SCHEDULED',
    } as any;
    expect(component.canCheckIn(item)).toBeFalse();
  });

  it('openCheckIn should set checkinQueueItem signal', () => {
    const item = {
      appointmentId: 'a1',
      patientId: 'p1',
      patientName: 'A',
      status: 'SCHEDULED',
    } as any;
    component.openCheckIn(item);
    expect(component.checkinQueueItem()).toEqual(item);
  });

  it('onCheckedIn should clear checkinQueueItem signal', () => {
    const item = {
      appointmentId: 'a1',
      patientId: 'p1',
      patientName: 'A',
      status: 'SCHEDULED',
    } as any;
    component.openCheckIn(item);
    expect(component.checkinQueueItem()).toBeTruthy();
    component.onCheckedIn();
    expect(component.checkinQueueItem()).toBeNull();
  });
});
