import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { of, throwError } from 'rxjs';

import { ImagingComponent } from './imaging';
import {
  ImagingService,
  ImagingOrderResponse,
  ImagingReportResponse,
} from '../services/imaging.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

function mockOrder(overrides: Partial<ImagingOrderResponse> = {}): ImagingOrderResponse {
  return {
    id: 'o1',
    patientId: 'p1',
    patientDisplayName: 'John Doe',
    hospitalId: 'h1',
    modality: 'XRAY',
    studyType: 'Chest X-Ray',
    priority: 'ROUTINE',
    status: 'ORDERED',
    ...overrides,
  } as ImagingOrderResponse;
}

function mockReport(overrides: Partial<ImagingReportResponse> = {}): ImagingReportResponse {
  return {
    id: 'r1',
    imagingOrderId: 'o1',
    modality: 'XRAY',
    reportStatus: 'FINAL',
    ...overrides,
  } as ImagingReportResponse;
}

describe('ImagingComponent', () => {
  let component: ImagingComponent;
  let imagingSpy: jasmine.SpyObj<ImagingService>;
  let hospitalSpy: jasmine.SpyObj<HospitalService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let authSpy: jasmine.SpyObj<AuthService>;

  beforeEach(async () => {
    imagingSpy = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'createOrder',
      'updateOrder',
      'updateOrderStatus',
      'getReportsForOrder',
      'getReportsByHospital',
      'getLatestReportByOrder',
      'acknowledgeCriticalReport',
      'updateReportStatus',
      'createReport',
      'updateReport',
      'signReport',
    ]);
    imagingSpy.getAllOrders.and.returnValue(
      of([
        mockOrder(),
        mockOrder({ id: 'o2', status: 'COMPLETED' }),
        mockOrder({ id: 'o3', status: 'CANCELLED' }),
      ]),
    );
    hospitalSpy = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospitalSpy.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'City Hospital' } as HospitalResponse),
    );
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error']);
    authSpy = jasmine.createSpyObj('AuthService', ['getHospitalId', 'getUserProfile']);
    authSpy.getHospitalId.and.returnValue('h1');
    authSpy.getUserProfile.and.returnValue({ staffId: 'st1' } as never);
    const patientSpy = jasmine.createSpyObj('PatientService', ['list']);
    patientSpy.list.and.returnValue(of([]));
    const roleCtx = {
      isSuperAdmin: () => false,
      activeHospitalId: 'h1',
      hasAnyActiveRole: () => true,
    } as unknown as RoleContextService;

    await TestBed.configureTestingModule({
      imports: [ImagingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ImagingService, useValue: imagingSpy },
        { provide: HospitalService, useValue: hospitalSpy },
        { provide: PatientService, useValue: patientSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: AuthService, useValue: authSpy },
        { provide: RoleContextService, useValue: roleCtx },
      ],
    }).compileComponents();

    component = TestBed.createComponent(ImagingComponent).componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('loads orders and locks the hospital for non-super-admins on init', () => {
    component.ngOnInit();
    expect(imagingSpy.getAllOrders).toHaveBeenCalled();
    expect(component.orders().length).toBe(3);
    expect(component.loading()).toBeFalse();
    expect(hospitalSpy.getMyHospitalAsResponse).toHaveBeenCalled();
    expect(hospitalSpy.list).not.toHaveBeenCalled();
    expect(component.hospitalLocked).toBeTrue();
    expect(component.lockedHospitalName).toBe('City Hospital');
    expect(component.form.hospitalId).toBe('h1');
  });

  it('tab filters split orders by status group', () => {
    component.ngOnInit();
    component.setTab('ordered');
    expect(component.filtered().map((o) => o.id)).toEqual(['o1']);
    component.setTab('completed');
    expect(component.filtered().map((o) => o.id)).toEqual(['o2']);
    component.setTab('cancelled');
    expect(component.filtered().map((o) => o.id)).toEqual(['o3']);
    component.setTab('all');
    expect(component.filtered().length).toBe(3);
  });

  it('search filter matches patient, study, and modality', () => {
    component.ngOnInit();
    component.searchTerm = 'chest';
    component.applyFilter();
    expect(component.filtered().length).toBe(3);
    component.searchTerm = 'nothing-matches';
    component.applyFilter();
    expect(component.filtered().length).toBe(0);
  });

  it('exposes active/completed order counts', () => {
    component.ngOnInit();
    expect(component.activeOrderCount()).toBe(1);
    expect(component.completedOrderCount()).toBe(1);
  });

  it('viewDetail loads reports for the order', () => {
    imagingSpy.getReportsForOrder.and.returnValue(of([mockReport()]));
    const order = mockOrder();
    component.viewDetail(order);
    expect(component.selectedOrder()).toBe(order);
    expect(component.selectedOrderReports().length).toBe(1);
    component.closeDetail();
    expect(component.selectedOrder()).toBeNull();
  });

  it('submitForm creates an order and strips the empty laterality', () => {
    component.ngOnInit();
    imagingSpy.createOrder.and.returnValue(of(mockOrder()));
    component.openCreate();
    component.form.laterality = '';
    component.submitForm();
    const payload = imagingSpy.createOrder.calls.mostRecent().args[0];
    expect(payload.laterality).toBeUndefined();
    expect(component.showModal()).toBeFalse();
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('executeCancel flips the order to CANCELLED and reloads', () => {
    component.ngOnInit();
    imagingSpy.updateOrderStatus.and.returnValue(of(mockOrder({ status: 'CANCELLED' })));
    component.confirmCancel(component.orders()[0]);
    component.executeCancel();
    const [id, req] = imagingSpy.updateOrderStatus.calls.mostRecent().args;
    expect(id).toBe('o1');
    expect(req['status']).toBe('CANCELLED');
    expect(component.showDeleteConfirm()).toBeFalse();
  });

  it('setView(results) lazily loads reports scoped to the active hospital', () => {
    imagingSpy.getReportsByHospital.and.returnValue(of([mockReport()]));
    component.setView('results');
    const [hospitalId, params] = imagingSpy.getReportsByHospital.calls.mostRecent().args;
    expect(hospitalId).toBe('h1');
    expect(params?.status).toBe('FINAL');
    expect(component.reports().length).toBe(1);

    // Already loaded → switching back and forth does not re-fetch.
    imagingSpy.getReportsByHospital.calls.reset();
    component.setView('orders');
    component.setView('results');
    expect(imagingSpy.getReportsByHospital).not.toHaveBeenCalled();
  });

  it('visibleReports applies modality and critical-only filters', () => {
    component.reports.set([
      mockReport({ id: 'r1', modality: 'XRAY' }),
      mockReport({ id: 'r2', modality: 'CT', criticalResultFlaggedAt: '2026-08-18T10:00:00' }),
    ]);
    expect(component.visibleReports().length).toBe(2);
    component.onReportModalityChange('CT');
    expect(component.visibleReports().map((r) => r.id)).toEqual(['r2']);
    component.onReportModalityChange('');
    component.toggleCriticalOnly();
    expect(component.visibleReports().map((r) => r.id)).toEqual(['r2']);
  });

  it('flags critical and unacknowledged-critical reports', () => {
    const flagged = mockReport({ criticalResultFlaggedAt: '2026-08-18T10:00:00' });
    const acked = mockReport({
      criticalResultFlaggedAt: '2026-08-18T10:00:00',
      criticalResultAcknowledgedAt: '2026-08-18T11:00:00',
    });
    expect(component.isCritical(flagged)).toBeTrue();
    expect(component.isCriticalUnacked(flagged)).toBeTrue();
    expect(component.isCriticalUnacked(acked)).toBeFalse();
    expect(component.isCritical(mockReport())).toBeFalse();
  });

  it('acknowledgeCritical sends no staff id — the server stamps the caller', () => {
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    const updated = mockReport({ criticalResultAcknowledgedAt: '2026-08-18T11:00:00' });
    imagingSpy.acknowledgeCriticalReport.and.returnValue(of(updated));
    component.acknowledgeCritical(mockReport());
    expect(imagingSpy.acknowledgeCriticalReport).toHaveBeenCalledWith('r1');
    expect(component.selectedReport()).toBe(updated);
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('submitStatusUpdate voids the open report and closes the modal', () => {
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    const report = mockReport();
    const updated = mockReport({ reportStatus: 'CANCELLED' });
    imagingSpy.updateReportStatus.and.returnValue(of(updated));
    component.selectedReport.set(report);
    component.openStatusUpdate(report);
    component.statusForm.statusReason = 'study repeated';
    component.submitStatusUpdate();
    const [id, req] = imagingSpy.updateReportStatus.calls.mostRecent().args;
    expect(id).toBe('r1');
    expect(req.status).toBe('CANCELLED');
    expect(req.statusReason).toBe('study repeated');
    expect(component.selectedReport()).toBe(updated);
    expect(component.showStatusModal()).toBeFalse();
  });

  it('submitStatusUpdate refuses to void without a reason', () => {
    const report = mockReport();
    component.openStatusUpdate(report);
    component.statusForm.statusReason = '   ';
    component.submitStatusUpdate();
    expect(imagingSpy.updateReportStatus).not.toHaveBeenCalled();
    expect(toastSpy.error).toHaveBeenCalled();
  });

  /* ── Authoring (Tier 2 item 26) ── */

  it('openAuthorReport seeds the form from the order and defaults to PRELIMINARY', () => {
    component.openAuthorReport(mockOrder({ studyType: 'CT Head', bodyRegion: 'Head' }));
    expect(component.showReportModal()).toBeTrue();
    expect(component.reportEditId()).toBeNull();
    expect(component.reportForm.reportTitle).toBe('CT Head');
    expect(component.reportForm.bodyRegion).toBe('Head');
    expect(component.reportForm.reportStatus).toBe('PRELIMINARY');
  });

  it('openAuthorReport opens as ADDENDUM when the study already has a signed read', () => {
    component.openAuthorReport(mockOrder({ status: 'RESULTS_AVAILABLE' }), true);
    expect(component.reportForm.reportStatus).toBe('ADDENDUM');
  });

  it('openEditReport refuses a signed report rather than letting the server bounce it', () => {
    component.openEditReport(mockReport({ signed: true }));
    expect(component.showReportModal()).toBeFalse();
    expect(toastSpy.error).toHaveBeenCalled();
  });

  it('openEditReport falls back to PRELIMINARY for a status an author cannot assert', () => {
    component.openEditReport(mockReport({ reportStatus: 'FINAL', signed: false }));
    expect(component.reportForm.reportStatus).toBe('PRELIMINARY');
  });

  it('submitReport creates when there is no edit id and trims empties away', () => {
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    const saved = mockReport();
    imagingSpy.createReport.and.returnValue(of(saved));
    component.openAuthorReport(mockOrder());
    component.reportForm.impression = '  Acute appendicitis.  ';
    component.reportForm.findings = '   ';
    component.submitReport();
    const [req] = imagingSpy.createReport.calls.mostRecent().args;
    expect(req.imagingOrderId).toBe('o1');
    expect(req.impression).toBe('Acute appendicitis.');
    expect(req.findings).toBeUndefined();
    expect(component.showReportModal()).toBeFalse();
    expect(component.selectedReport()).toBe(saved);
  });

  it('submitReport updates when an edit id is set', () => {
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    imagingSpy.updateReport.and.returnValue(of(mockReport()));
    component.openEditReport(mockReport({ signed: false }));
    component.submitReport();
    expect(imagingSpy.updateReport).toHaveBeenCalled();
    expect(imagingSpy.createReport).not.toHaveBeenCalled();
  });

  it('signReport asks before signing and does nothing when declined', () => {
    spyOn(window, 'confirm').and.returnValue(false);
    component.signReport(mockReport());
    expect(imagingSpy.signReport).not.toHaveBeenCalled();
  });

  it('signReport signs and refreshes when confirmed', () => {
    spyOn(window, 'confirm').and.returnValue(true);
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    const signed = mockReport({ reportStatus: 'FINAL', signed: true });
    imagingSpy.signReport.and.returnValue(of(signed));
    component.signReport(mockReport());
    expect(imagingSpy.signReport).toHaveBeenCalledWith('r1');
    expect(component.selectedReport()).toBe(signed);
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('canAuthorForOrder only admits orders whose study has been acquired', () => {
    expect(component.canAuthorForOrder(mockOrder({ status: 'COMPLETED' }))).toBeTrue();
    expect(component.canAuthorForOrder(mockOrder({ status: 'IN_PROGRESS' }))).toBeTrue();
    expect(component.canAuthorForOrder(mockOrder({ status: 'ORDERED' }))).toBeFalse();
    expect(component.canAuthorForOrder(mockOrder({ status: 'CANCELLED' }))).toBeFalse();
  });

  it('viewReportForOrder opens the latest report and closes the order detail', () => {
    const report = mockReport();
    imagingSpy.getLatestReportByOrder.and.returnValue(of(report));
    component.selectedOrder.set(mockOrder());
    component.viewReportForOrder(mockOrder());
    expect(component.selectedOrder()).toBeNull();
    expect(component.selectedReport()).toBe(report);
  });

  it('viewReportForOrder surfaces a toast when no report exists', () => {
    imagingSpy.getLatestReportByOrder.and.returnValue(throwError(() => new Error('404')));
    component.viewReportForOrder(mockOrder());
    expect(toastSpy.error).toHaveBeenCalled();
    expect(component.reportLoading()).toBeFalse();
  });

  it('maps statuses and priorities to css classes', () => {
    expect(component.getStatusClass('ORDERED')).toBe('status-ordered');
    expect(component.getStatusClass('RESULTS_AVAILABLE')).toBe('status-completed');
    expect(component.getStatusClass('CANCELLED')).toBe('status-cancelled');
    expect(component.getPriorityClass('STAT')).toBe('priority-stat');
    expect(component.getPriorityClass('ROUTINE')).toBe('priority-routine');
    expect(component.getReportStatusClass('FINAL')).toBe('status-completed');
    expect(component.getReportStatusClass('DRAFT')).toBe('status-preliminary');
    expect(component.getReportStatusClass('CANCELLED')).toBe('status-cancelled');
  });
});
