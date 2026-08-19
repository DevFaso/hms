import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ConsultationsComponent } from './consultations';
import {
  ConsultationService,
  ConsultationResponse,
  ConsultationStats,
} from '../services/consultation.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService, PatientResponse } from '../services/patient.service';
import { StaffService, StaffResponse } from '../services/staff.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { HospitalScopeUrlService } from '../core/hospital-scope-url.service';

function mockConsult(overrides: Partial<ConsultationResponse> = {}): ConsultationResponse {
  return {
    id: 'c1',
    patientId: 'p1',
    patientName: 'John Doe',
    hospitalId: 'h1',
    consultationType: 'OUTPATIENT_CONSULT',
    specialtyRequested: 'Cardiology',
    reasonForConsult: 'Chest pain',
    urgency: 'ROUTINE',
    status: 'REQUESTED',
    requestedAt: '2026-08-10T08:00:00',
    ...overrides,
  } as ConsultationResponse;
}

describe('ConsultationsComponent', () => {
  let component: ConsultationsComponent;
  let consultSpy: jasmine.SpyObj<ConsultationService>;
  let staffSpy: jasmine.SpyObj<StaffService>;
  let toastSpy: jasmine.SpyObj<ToastService>;

  beforeEach(async () => {
    consultSpy = jasmine.createSpyObj('ConsultationService', [
      'getAll',
      'getMine',
      'getOverdue',
      'getStats',
      'create',
      'cancel',
      'assign',
      'reassign',
      'schedule',
      'acknowledge',
      'start',
      'complete',
      'decline',
    ]);
    consultSpy.getAll.and.returnValue(
      of([
        mockConsult(),
        mockConsult({ id: 'c2', status: 'IN_PROGRESS' }),
        mockConsult({ id: 'c3', status: 'COMPLETED' }),
      ]),
    );
    consultSpy.getStats.and.returnValue(
      of({ total: 3, requested: 1, active: 1, completed: 1, overdue: 0 } as ConsultationStats),
    );
    staffSpy = jasmine.createSpyObj('StaffService', ['list']);
    staffSpy.list.and.returnValue(
      of([
        { id: 'st1', name: 'Dr A', active: true, specialization: 'Cardiology' } as StaffResponse,
        { id: 'st2', name: 'Dr B', active: false } as StaffResponse,
      ]),
    );
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    const hospitalSpy = jasmine.createSpyObj('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    hospitalSpy.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'City Hospital' } as HospitalResponse),
    );
    const patientSpy = jasmine.createSpyObj('PatientService', ['list']);
    patientSpy.list.and.returnValue(of([]));
    const scopeUrlSpy = jasmine.createSpyObj('HospitalScopeUrlService', ['applyUrlScopeSync']);
    const roleCtx = {
      isSuperAdmin: () => false,
      globalView: () => false,
      activeHospitalId: 'h1',
    } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [ConsultationsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ConsultationService, useValue: consultSpy },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: PatientService, useValue: patientSpy },
        { provide: StaffService, useValue: staffSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: RoleContextService, useValue: roleCtx },
        { provide: HospitalScopeUrlService, useValue: scopeUrlSpy },
        { provide: ActivatedRoute, useValue: { snapshot: { queryParamMap: new Map() } } },
      ],
    }).compileComponents();

    component = TestBed.createComponent(ConsultationsComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads consultations and stats on init', () => {
    component.ngOnInit();
    expect(consultSpy.getAll).toHaveBeenCalled();
    expect(consultSpy.getStats).toHaveBeenCalled();
    expect(component.consultations().length).toBe(3);
    expect(component.loading()).toBeFalse();
    expect(component.stats()?.total).toBe(3);
  });

  it('mine and overdue tabs use their dedicated endpoints', () => {
    consultSpy.getMine.and.returnValue(of([mockConsult({ id: 'm1' })]));
    consultSpy.getOverdue.and.returnValue(of([]));
    component.setTab('mine');
    expect(consultSpy.getMine).toHaveBeenCalled();
    expect(component.consultations()[0].id).toBe('m1');
    component.setTab('overdue');
    expect(consultSpy.getOverdue).toHaveBeenCalledWith('h1');
  });

  it('pending/active/completed tabs filter client-side', () => {
    component.ngOnInit();
    component.setTab('pending');
    expect(component.filtered().map((c) => c.id)).toEqual(['c1']);
    component.setTab('active');
    expect(component.filtered().map((c) => c.id)).toEqual(['c2']);
    component.setTab('completed');
    expect(component.filtered().map((c) => c.id)).toEqual(['c3']);
  });

  it('search filter matches patient, specialty, and consultant', () => {
    component.ngOnInit();
    component.searchTerm = 'cardio';
    component.applyFilter();
    expect(component.filtered().length).toBe(3);
    component.searchTerm = 'no-match';
    component.applyFilter();
    expect(component.filtered().length).toBe(0);
  });

  it('countByGroup prefers server stats and falls back to the loaded list', () => {
    component.ngOnInit();
    expect(component.countByGroup('total')).toBe(3);
    expect(component.countByGroup('pending')).toBe(1);
    component.stats.set(null);
    expect(component.countByGroup('total')).toBe(3); // falls back to list length
    expect(component.countByGroup('pending')).toBe(1); // REQUESTED rows
  });

  it('isOverdue is true only for open consults past their SLA', () => {
    const past = '2020-01-01T00:00:00';
    expect(component.isOverdue(mockConsult({ slaDueBy: past }))).toBeTrue();
    expect(component.isOverdue(mockConsult({ slaDueBy: past, status: 'COMPLETED' }))).toBeFalse();
    expect(component.isOverdue(mockConsult({ slaDueBy: undefined }))).toBeFalse();
    expect(component.isOverdue(mockConsult({ slaDueBy: '2099-01-01T00:00:00' }))).toBeFalse();
  });

  it('getTimelineEvents returns chronologically sorted events', () => {
    const events = component.getTimelineEvents(
      mockConsult({
        requestedAt: '2026-08-10T08:00:00',
        completedAt: '2026-08-12T09:00:00',
        assignedAt: '2026-08-10T09:00:00',
      }),
    );
    expect(events.map((e) => e.label)).toEqual(['Requested', 'Assigned', 'Completed']);
  });

  it('submitForm creates the consultation and reloads', () => {
    component.ngOnInit();
    consultSpy.create.and.returnValue(of(mockConsult()));
    component.openCreate();
    component.submitForm();
    expect(consultSpy.create).toHaveBeenCalled();
    expect(component.showModal()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('executeCancel requires a reason', () => {
    component.confirmCancel(mockConsult());
    component.cancelReason.set('   ');
    component.executeCancel();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(consultSpy.cancel).not.toHaveBeenCalled();
  });

  it('executeCancel cancels with the trimmed reason', () => {
    component.ngOnInit();
    consultSpy.cancel.and.returnValue(of(mockConsult({ status: 'CANCELLED' })));
    component.confirmCancel(component.consultations()[0]);
    component.cancelReason.set(' duplicate request ');
    component.executeCancel();
    expect(consultSpy.cancel).toHaveBeenCalledWith('c1', 'duplicate request');
    expect(component.showDeleteConfirm()).toBeFalse();
  });

  it('openAssign loads only active staff for the consultation hospital', () => {
    component.openAssign(mockConsult());
    expect(staffSpy.list).toHaveBeenCalledWith('h1');
    expect(component.assignStaff().length).toBe(1); // inactive filtered out
    expect(component.isReassign()).toBeFalse();
  });

  it('filteredAssignStaff narrows by specialty', () => {
    component.openAssign(mockConsult());
    component.assignSpecialtyFilter.set('cardio');
    expect(component.filteredAssignStaff().length).toBe(1);
    component.assignSpecialtyFilter.set('neuro');
    expect(component.filteredAssignStaff().length).toBe(0);
  });

  it('submitAssign requires a consultant selection', () => {
    component.openAssign(mockConsult());
    component.submitAssign();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(consultSpy.assign).not.toHaveBeenCalled();
  });

  it('submitAssign assigns and closes the modal', () => {
    component.ngOnInit();
    consultSpy.assign.and.returnValue(of(mockConsult({ status: 'ASSIGNED' })));
    component.openAssign(mockConsult());
    component.assignConsultantId.set('st1');
    component.submitAssign();
    expect(consultSpy.assign).toHaveBeenCalledWith('c1', 'st1', undefined);
    expect(component.showAssignModal()).toBeFalse();
  });

  it('reassign requires a reason before calling the service', () => {
    component.openAssign(mockConsult(), true);
    component.assignConsultantId.set('st1');
    component.assignNote.set('');
    component.submitAssign();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(consultSpy.reassign).not.toHaveBeenCalled();
  });

  it('submitSchedule requires a date and then schedules', () => {
    component.openSchedule(mockConsult());
    component.submitSchedule();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(consultSpy.schedule).not.toHaveBeenCalled();

    component.ngOnInit();
    consultSpy.schedule.and.returnValue(of(mockConsult({ status: 'SCHEDULED' })));
    component.openSchedule(mockConsult());
    component.scheduleForm.scheduledAt = '2026-08-20T10:00';
    component.submitSchedule();
    expect(consultSpy.schedule).toHaveBeenCalledWith('c1', '2026-08-20T10:00', undefined);
    expect(component.showScheduleModal()).toBeFalse();
  });

  it('acknowledge and start reload the list on success', () => {
    component.ngOnInit();
    consultSpy.acknowledge.and.returnValue(of(mockConsult({ status: 'ACKNOWLEDGED' })));
    consultSpy.start.and.returnValue(of(mockConsult({ status: 'IN_PROGRESS' })));
    consultSpy.getAll.calls.reset();
    component.acknowledgeConsultation(mockConsult());
    component.startConsultation(mockConsult());
    expect(consultSpy.acknowledge).toHaveBeenCalledWith('c1');
    expect(consultSpy.start).toHaveBeenCalledWith('c1');
    expect(consultSpy.getAll).toHaveBeenCalledTimes(2);
  });

  it('submitComplete requires recommendations', () => {
    component.openComplete(mockConsult());
    component.submitComplete();
    expect(toastSpy.error).toHaveBeenCalled();
    expect(consultSpy.complete).not.toHaveBeenCalled();
  });

  it('submitComplete sends the completion payload', () => {
    component.ngOnInit();
    consultSpy.complete.and.returnValue(of(mockConsult({ status: 'COMPLETED' })));
    component.openComplete(mockConsult());
    component.completeForm.recommendations = 'Start beta blockers';
    component.completeForm.followUpRequired = true;
    component.completeForm.followUpInstructions = 'Review in 2 weeks';
    component.submitComplete();
    const [id, payload] = consultSpy.complete.calls.mostRecent().args;
    expect(id).toBe('c1');
    expect(payload.recommendations).toBe('Start beta blockers');
    expect(payload.followUpRequired).toBeTrue();
    expect(component.showCompleteModal()).toBeFalse();
  });

  it('submitDecline requires a reason and then declines', () => {
    component.openDecline(mockConsult());
    component.submitDecline();
    expect(consultSpy.decline).not.toHaveBeenCalled();

    component.ngOnInit();
    consultSpy.decline.and.returnValue(of(mockConsult({ status: 'DECLINED' })));
    component.openDecline(mockConsult());
    component.declineReasonValue.set('Out of specialty scope');
    component.submitDecline();
    expect(consultSpy.decline).toHaveBeenCalledWith('c1', 'Out of specialty scope');
    expect(component.showDeclineModal()).toBeFalse();
  });

  it('maps statuses and urgencies to css classes', () => {
    expect(component.getStatusClass('REQUESTED')).toBe('status-requested');
    expect(component.getStatusClass('IN_PROGRESS')).toBe('status-progress');
    expect(component.getStatusClass('DECLINED')).toBe('status-cancelled');
    expect(component.getUrgencyClass('STAT')).toBe('urgency-stat');
    expect(component.getUrgencyClass('URGENT')).toBe('urgency-urgent');
    expect(component.getUrgencyClass('ROUTINE')).toBe('urgency-routine');
  });

  it('selecting a patient fills the form; clearing resets it', () => {
    const patient = { id: 'p9', firstName: 'Jane', lastName: 'Roe' } as PatientResponse;
    component.selectPatient(patient);
    expect(component.form.patientId).toBe('p9');
    expect(component.patientInitials(patient)).toBe('JR');
    component.clearPatient();
    expect(component.form.patientId).toBe('');
    expect(component.selectedPatient()).toBeNull();
  });
});
