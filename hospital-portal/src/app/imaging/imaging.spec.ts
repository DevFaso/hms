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

  it('acknowledgeCritical requires a staff context', () => {
    authSpy.getUserProfile.and.returnValue(null);
    component.acknowledgeCritical(mockReport());
    expect(toastSpy.error).toHaveBeenCalled();
    expect(imagingSpy.acknowledgeCriticalReport).not.toHaveBeenCalled();
  });

  it('acknowledgeCritical acknowledges with the staff id and refreshes', () => {
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    const updated = mockReport({ criticalResultAcknowledgedAt: '2026-08-18T11:00:00' });
    imagingSpy.acknowledgeCriticalReport.and.returnValue(of(updated));
    component.acknowledgeCritical(mockReport());
    expect(imagingSpy.acknowledgeCriticalReport).toHaveBeenCalledWith('r1', 'st1');
    expect(component.selectedReport()).toBe(updated);
    expect(toastSpy.success).toHaveBeenCalled();
  });

  it('submitStatusUpdate patches the open report and closes the modal', () => {
    imagingSpy.getReportsByHospital.and.returnValue(of([]));
    const report = mockReport();
    const updated = mockReport({ reportStatus: 'AMENDED' });
    imagingSpy.updateReportStatus.and.returnValue(of(updated));
    component.selectedReport.set(report);
    component.openStatusUpdate(report);
    component.statusForm.status = 'AMENDED';
    component.statusForm.statusReason = 'typo fix';
    component.submitStatusUpdate();
    const [id, req] = imagingSpy.updateReportStatus.calls.mostRecent().args;
    expect(id).toBe('r1');
    expect(req.status).toBe('AMENDED');
    expect(req.statusReason).toBe('typo fix');
    expect(component.selectedReport()).toBe(updated);
    expect(component.showStatusModal()).toBeFalse();
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
