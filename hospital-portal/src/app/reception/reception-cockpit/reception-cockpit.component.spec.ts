import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ReceptionCockpitComponent } from './reception-cockpit.component';
import { TranslateModule } from '@ngx-translate/core';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideHttpClient } from '@angular/common/http';
import { ReceptionService } from '../reception.service';
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
        provideHttpClient(),
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
});
