import { formatDate } from '@angular/common';
import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabInstrumentsComponent } from './lab-instruments';
import { AuthService } from '../../auth/auth.service';
import { LabInstrumentResponse } from '../../services/lab-instrument.service';
import { PagedResponse } from '../../services/staff.service';
import { ToastService } from '../../core/toast.service';
import { ConfirmService } from '../../shared/confirm-dialog/confirm.service';

function mockInstrument(overrides: Partial<LabInstrumentResponse> = {}): LabInstrumentResponse {
  return {
    id: 'inst-1',
    name: 'Analyzer X',
    manufacturer: 'BioTech',
    modelNumber: 'BT-100',
    serialNumber: 'SN-0001',
    hospitalId: 'h-1',
    hospitalName: 'Test Hospital',
    departmentId: 'dept-1',
    departmentName: 'Lab',
    status: 'ACTIVE',
    installationDate: '2025-01-01',
    maintenanceOverdue: false,
    calibrationOverdue: false,
    createdAt: '2025-01-01T00:00:00Z',
    ...overrides,
  };
}

const PAGED: PagedResponse<LabInstrumentResponse> = {
  content: [
    mockInstrument(),
    mockInstrument({
      id: 'inst-2',
      name: 'Microscope Y',
      serialNumber: 'SN-0002',
      status: 'MAINTENANCE',
      maintenanceOverdue: true,
    }),
  ],
  totalElements: 2,
  totalPages: 1,
  number: 0,
  size: 20,
};

describe('LabInstrumentsComponent', () => {
  let fixture: ComponentFixture<LabInstrumentsComponent>;
  let component: LabInstrumentsComponent;
  let httpMock: HttpTestingController;
  let authSpy: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    authSpy = jasmine.createSpyObj('AuthService', ['getRoles', 'getHospitalId']);
    authSpy.getRoles.and.returnValue(['ROLE_LAB_DIRECTOR']);
    authSpy.getHospitalId.and.returnValue('h-1');

    TestBed.configureTestingModule({
      imports: [LabInstrumentsComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: AuthService, useValue: authSpy },
      ],
    });

    fixture = TestBed.createComponent(LabInstrumentsComponent);
    component = fixture.componentInstance;
    httpMock = TestBed.inject(HttpTestingController);
  });

  afterEach(() => {
    httpMock.verify();
  });

  function flushInstruments(data: PagedResponse<LabInstrumentResponse> = PAGED): void {
    const req = httpMock.expectOne((r) => r.url.includes('/lab/instruments/hospital/h-1'));
    expect(req.request.method).toBe('GET');
    req.flush(data);
  }

  it('should create the component', () => {
    expect(component).toBeTruthy();
    fixture.detectChanges();
    flushInstruments();
  });

  it('should load instruments on init', () => {
    fixture.detectChanges();
    flushInstruments();

    expect(component.loading()).toBeFalse();
    expect(component.instruments().length).toBe(2);
    expect(component.totalElements()).toBe(2);
  });

  it('shows the next calibration and maintenance dates even when they are overdue', () => {
    fixture.detectChanges();
    flushInstruments({
      ...PAGED,
      content: [
        mockInstrument({
          nextCalibrationDate: '2025-03-15',
          nextMaintenanceDate: '2025-04-21',
          calibrationOverdue: true,
          maintenanceOverdue: true,
        }),
      ],
    });
    fixture.detectChanges();
    const row = (fixture.nativeElement as HTMLElement).querySelector('tbody tr')!;
    expect(row.textContent).toContain(formatDate('2025-03-15', 'mediumDate', 'en-US'));
    expect(row.textContent).toContain(formatDate('2025-04-21', 'mediumDate', 'en-US'));
    expect(row.textContent).toContain('LAB_INSTRUMENTS.OVERDUE');
  });

  it('should show loading state initially', () => {
    fixture.detectChanges();
    expect(component.loading()).toBeTrue();
    flushInstruments();
  });

  it('should handle load error', () => {
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/lab/instruments/hospital/h-1'));
    req.error(new ProgressEvent('error'));

    expect(component.loading()).toBeFalse();
    expect(component.error()).toBeTruthy();
    expect(component.instruments().length).toBe(0);
  });

  it('should set error if no hospital context', () => {
    authSpy.getHospitalId.and.returnValue(null);
    component.loadInstruments();

    expect(component.error()).toBeTruthy();
    expect(component.loading()).toBeFalse();
  });

  it('should allow management for lab director role', () => {
    expect(component.canManage()).toBeTrue();
    fixture.detectChanges();
    flushInstruments();
  });

  it('should not allow management for lab technician role', () => {
    authSpy.getRoles.and.returnValue(['ROLE_LAB_TECHNICIAN']);
    fixture = TestBed.createComponent(LabInstrumentsComponent);
    component = fixture.componentInstance;

    expect(component.canManage()).toBeFalse();
    fixture.detectChanges();
    const req = httpMock.expectOne((r) => r.url.includes('/lab/instruments/hospital/h-1'));
    req.flush(PAGED);
  });

  it('should open create form', () => {
    fixture.detectChanges();
    flushInstruments();

    component.openCreate();

    expect(component.showForm()).toBeTrue();
    expect(component.editingId()).toBeNull();
    expect(component.form.name).toBe('');
  });

  it('should open edit form with instrument data', () => {
    fixture.detectChanges();
    flushInstruments();

    const inst = component.instruments()[0];
    component.openEdit(inst);

    expect(component.showForm()).toBeTrue();
    expect(component.editingId()).toBe('inst-1');
    expect(component.form.name).toBe('Analyzer X');
    expect(component.form.serialNumber).toBe('SN-0001');
  });

  it('should cancel form', () => {
    fixture.detectChanges();
    flushInstruments();

    component.openCreate();
    component.form.name = 'Draft';
    component.cancelForm();

    expect(component.showForm()).toBeFalse();
    expect(component.editingId()).toBeNull();
    expect(component.form.name).toBe('');
  });

  // QA D1193 (round 22): an empty "Créer" posted and showed the server's raw
  // "ne doit pas être vide" (instruments) or a generic toast (inventory).
  it('QA D1193: an empty "Créer" sends nothing and names the missing fields', () => {
    fixture.detectChanges();
    flushInstruments();
    component.openCreate();
    fixture.detectChanges();
    const host = fixture.nativeElement as HTMLElement;
    host.querySelector<HTMLButtonElement>('button[type="submit"]')!.click();
    fixture.detectChanges();
    httpMock.expectNone((r) => r.url.includes('/lab/instruments') && r.method === 'POST');
    const reason = host.querySelector('[data-testid="submit-guard-reason"]')?.textContent ?? '';
    expect(reason).toContain('COMMON.MISSING_REQUIRED_FIELDS');
    host
      .querySelectorAll('input[required]')
      .forEach((f) => expect(f.getAttribute('aria-invalid')).toBe('true'));
  });

  it('should save new instrument', () => {
    fixture.detectChanges();
    flushInstruments();

    component.openCreate();
    component.form.name = 'New Instrument';
    component.form.serialNumber = 'SN-NEW';
    component.save();

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/instruments/hospital/h-1') && r.method === 'POST',
    );
    expect(req.request.body.name).toBe('New Instrument');
    req.flush(mockInstrument({ id: 'inst-new', name: 'New Instrument', serialNumber: 'SN-NEW' }));

    expect(component.showForm()).toBeFalse();
    expect(component.saving()).toBeFalse();

    // Reload triggered
    flushInstruments();
  });

  it('QA D4153: the form offers the five dates and sends the ones entered, an emptied one as no date', () => {
    fixture.detectChanges();
    flushInstruments();

    component.openCreate();
    fixture.detectChanges();
    const el: HTMLElement = fixture.nativeElement;
    for (const field of [
      'installationDate',
      'lastCalibrationDate',
      'nextCalibrationDate',
      'lastMaintenanceDate',
      'nextMaintenanceDate',
    ]) {
      const input = el.querySelector<HTMLInputElement>(`[data-testid="instrument-${field}"]`);
      expect(input?.type).withContext(field).toBe('date');
    }

    component.form.name = 'Ionogramme';
    component.form.serialNumber = 'SN-DATES';
    component.form.installationDate = '2026-01-15';
    component.form.nextCalibrationDate = '2026-12-01';
    component.form.lastMaintenanceDate = '';
    component.save();

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/instruments/hospital/h-1') && r.method === 'POST',
    );
    expect(req.request.body.installationDate).toBe('2026-01-15');
    expect(req.request.body.nextCalibrationDate).toBe('2026-12-01');
    expect(req.request.body.lastMaintenanceDate).toBeUndefined();
    req.flush(mockInstrument({ id: 'inst-dates' }));
    flushInstruments();
  });

  it('should save updated instrument', () => {
    fixture.detectChanges();
    flushInstruments();

    const inst = component.instruments()[0];
    component.openEdit(inst);
    component.form.name = 'Updated Analyzer';
    component.save();

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/instruments/inst-1') && r.method === 'PUT',
    );
    expect(req.request.body.name).toBe('Updated Analyzer');
    req.flush(mockInstrument({ name: 'Updated Analyzer' }));

    expect(component.showForm()).toBeFalse();
    flushInstruments();
  });

  it('should handle save error', () => {
    fixture.detectChanges();
    flushInstruments();

    component.openCreate();
    component.form.name = 'Fail';
    component.form.serialNumber = 'SN-FAIL';
    component.save();

    const req = httpMock.expectOne((r) => r.method === 'POST');
    req.error(new ProgressEvent('error'));

    expect(component.saving()).toBeFalse();
  });

  it('QA D879: a duplicate serial shows the server message, not the generic toast', () => {
    const toast = TestBed.inject(ToastService);
    spyOn(toast, 'error');
    fixture.detectChanges();
    flushInstruments();

    component.openCreate();
    component.form.name = 'Analyseur QA';
    component.form.serialNumber = 'QA-R13-SN-1001';
    component.save();

    const req = httpMock.expectOne((r) => r.method === 'POST');
    req.flush(
      { message: 'Un instrument porte déjà ce numéro de série dans cet établissement.' },
      { status: 409, statusText: 'Conflict' },
    );

    expect(toast.error).toHaveBeenCalledWith(
      'Un instrument porte déjà ce numéro de série dans cet établissement.',
    );
    expect(component.saving()).toBeFalse();
  });

  it('QA D879: a server failure keeps the generic toast, never a 5xx body', () => {
    const toast = TestBed.inject(ToastService);
    spyOn(toast, 'error');
    fixture.detectChanges();
    flushInstruments();

    component.openCreate();
    component.form.name = 'Analyseur QA';
    component.form.serialNumber = 'QA-R13-SN-1002';
    component.save();

    httpMock
      .expectOne((r) => r.method === 'POST')
      .flush(
        { message: 'NullPointerException at ...' },
        { status: 500, statusText: 'Server Error' },
      );

    expect(toast.error).toHaveBeenCalledWith('LAB_INSTRUMENTS.SAVE_ERROR');
  });

  it('should deactivate instrument', async () => {
    fixture.detectChanges();
    flushInstruments();

    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);

    const inst = component.instruments()[0];
    await component.deactivate(inst);

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/instruments/inst-1') && r.method === 'DELETE',
    );
    req.flush(null);

    // Reload triggered
    flushInstruments();
  });

  it('should not deactivate if user cancels confirm', async () => {
    fixture.detectChanges();
    flushInstruments();

    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(false);

    const inst = component.instruments()[0];
    await component.deactivate(inst);

    // No HTTP request should fire
  });

  it('should handle deactivate error', async () => {
    fixture.detectChanges();
    flushInstruments();

    spyOn(TestBed.inject(ConfirmService), 'ask').and.resolveTo(true);

    const inst = component.instruments()[0];
    await component.deactivate(inst);

    const req = httpMock.expectOne((r) => r.method === 'DELETE');
    req.error(new ProgressEvent('error'));

    // Component stays operational
    expect(component.instruments().length).toBe(2);
  });

  it('should not save when no hospitalId', () => {
    fixture.detectChanges();
    flushInstruments();

    authSpy.getHospitalId.and.returnValue(null);
    component.openCreate();
    component.save();

    // No HTTP request
    expect(component.saving()).toBeFalse();
  });

  it('should refresh on reload', () => {
    fixture.detectChanges();
    flushInstruments();

    component.loadInstruments();
    flushInstruments();

    expect(component.instruments().length).toBe(2);
  });
});
