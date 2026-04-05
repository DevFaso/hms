import { TestBed, ComponentFixture } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { LabInstrumentsComponent } from './lab-instruments';
import { AuthService } from '../../auth/auth.service';
import { LabInstrumentResponse } from '../../services/lab-instrument.service';
import { PagedResponse } from '../../services/staff.service';

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
        provideHttpClient(),
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

  it('should deactivate instrument', () => {
    fixture.detectChanges();
    flushInstruments();

    spyOn(globalThis, 'confirm').and.returnValue(true);

    const inst = component.instruments()[0];
    component.deactivate(inst);

    const req = httpMock.expectOne(
      (r) => r.url.includes('/lab/instruments/inst-1') && r.method === 'DELETE',
    );
    req.flush(null);

    // Reload triggered
    flushInstruments();
  });

  it('should not deactivate if user cancels confirm', () => {
    fixture.detectChanges();
    flushInstruments();

    spyOn(globalThis, 'confirm').and.returnValue(false);

    const inst = component.instruments()[0];
    component.deactivate(inst);

    // No HTTP request should fire
  });

  it('should handle deactivate error', () => {
    fixture.detectChanges();
    flushInstruments();

    spyOn(globalThis, 'confirm').and.returnValue(true);

    const inst = component.instruments()[0];
    component.deactivate(inst);

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
