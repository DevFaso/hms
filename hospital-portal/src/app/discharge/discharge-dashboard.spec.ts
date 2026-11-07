import { TestBed } from '@angular/core/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, Subject, throwError } from 'rxjs';
import { DischargeComponent } from './discharge';
import {
  DischargeApproval,
  DischargeService,
  DischargeSummary,
} from '../services/discharge.service';
import { EncounterService } from '../services/encounter.service';
import { PatientService } from '../services/patient.service';
import { ProfileService } from '../services/profile.service';
import { AuthService } from '../auth/auth.service';
import { RoleContextService } from '../core/role-context.service';
import { ToastService } from '../core/toast.service';

describe('Discharge dashboard', () => {
  let service: jasmine.SpyObj<DischargeService>;
  const row = (id: string, name: string, status = 'PENDING') =>
    ({
      id,
      patientName: name,
      nurseName: 'Cdx Nurse',
      status,
      nurseSummary: 'Cdx note',
    }) as DischargeApproval;
  beforeEach(() => {
    service = jasmine.createSpyObj('DischargeService', [
      'approvalsByHospital',
      'unfinalizedSummaries',
      'summariesWithPendingResults',
      'summariesByHospital',
    ]);
    service.approvalsByHospital.and.returnValue(of([row('a1', 'Cdx Patient')]));
    service.unfinalizedSummaries.and.returnValue(of([]));
    service.summariesWithPendingResults.and.returnValue(of([]));
    TestBed.configureTestingModule({
      imports: [DischargeComponent, TranslateModule.forRoot()],
      providers: [
        { provide: DischargeService, useValue: service },
        { provide: EncounterService, useValue: {} },
        { provide: PatientService, useValue: {} },
        { provide: ProfileService, useValue: { getAssignments: () => of([]) } },
        { provide: AuthService, useValue: { getUserProfile: () => ({ staffId: 'cdx-staff' }) } },
        {
          provide: RoleContextService,
          useValue: { effectiveHospitalIdForRequest: () => 'h2', hasAnyActiveRole: () => true },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['error', 'success']),
        },
      ],
    });
  });
  it('uses effective hospital scope and filters locally without changing loaded counters', () => {
    service.approvalsByHospital.and.returnValue(
      of([row('a1', 'Cdx First'), row('a2', 'Cdx Second', 'APPROVED')]),
    );
    const fixture = TestBed.createComponent(DischargeComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    expect(service.approvalsByHospital).toHaveBeenCalledWith('h2', 'PENDING');
    c.searchTerm.set('second');
    expect(c.visibleApprovals().map((a) => a.id)).toEqual(['a2']);
    expect(c.approvals().length).toBe(2);
    expect(c.pendingLoaded()).toBe(1);
    expect(service.approvalsByHospital.calls.count()).toBe(1);
  });
  it('ignores old filter responses and separates unavailability from an empty queue', () => {
    const old = new Subject<DischargeApproval[]>();
    service.approvalsByHospital.and.returnValue(old);
    const fixture = TestBed.createComponent(DischargeComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    service.approvalsByHospital.and.returnValue(of([row('new', 'Cdx Approved', 'APPROVED')]));
    c.setApprovalFilter('APPROVED');
    old.next([row('old', 'Cdx Old')]);
    expect(c.approvals()[0].id).toBe('new');
    service.approvalsByHospital.and.returnValue(throwError(() => new Error('offline')));
    c.loadApprovals();
    fixture.detectChanges();
    expect(c.approvals()).toEqual([]);
    expect(c.approvalsFailed()).toBeTrue();
    expect(fixture.nativeElement.querySelector('[role="alert"]')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('.empty-state')).toBeNull();
  });
  it('guards overlapping summary loads and offers search recovery', () => {
    const old = new Subject<DischargeSummary[]>();
    service.unfinalizedSummaries.and.returnValue(old);
    const fixture = TestBed.createComponent(DischargeComponent);
    fixture.detectChanges();
    const c = fixture.componentInstance;
    service.summariesWithPendingResults.and.returnValue(
      of([{ id: 'current', patientName: 'Cdx Patient' } as DischargeSummary]),
    );
    c.setSummaryFilter('pending-results');
    old.next([{ id: 'old' } as DischargeSummary]);
    expect(c.summaries()[0].id).toBe('current');
    c.setTab('summaries');
    c.searchTerm.set('missing');
    fixture.detectChanges();
    const empty: HTMLElement = fixture.nativeElement.querySelector('.empty-state');
    expect(empty.textContent).toContain('DISCHARGE.NO_MATCHES');
    empty.querySelector('button')!.click();
    fixture.detectChanges();
    expect(fixture.nativeElement.querySelectorAll('tbody tr').length).toBe(1);
  });
});
