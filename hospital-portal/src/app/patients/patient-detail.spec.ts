import { TestBed, ComponentFixture } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';
import { PatientDetailComponent } from './patient-detail';
import { PatientService } from '../services/patient.service';
import { VitalSignService, VitalSignResponse } from '../services/vital-sign.service';
import { EncounterService } from '../services/encounter.service';
import { AppointmentService } from '../services/appointment.service';
import { RecordSharingService } from '../services/record-sharing.service';
import { HospitalService } from '../services/hospital.service';
import { ToastService } from '../core/toast.service';
import { PermissionService } from '../core/permission.service';
import { RoleContextService } from '../core/role-context.service';

describe('PatientDetailComponent', () => {
  let component: PatientDetailComponent;
  let fixture: ComponentFixture<PatientDetailComponent>;
  let patientServiceSpy: jasmine.SpyObj<PatientService>;
  let vitalServiceSpy: jasmine.SpyObj<VitalSignService>;
  let encounterServiceSpy: jasmine.SpyObj<EncounterService>;
  let appointmentServiceSpy: jasmine.SpyObj<AppointmentService>;
  let sharingServiceSpy: jasmine.SpyObj<RecordSharingService>;
  let hospitalServiceSpy: jasmine.SpyObj<HospitalService>;
  let toastSpy: jasmine.SpyObj<ToastService>;
  let permissionSpy: jasmine.SpyObj<PermissionService>;
  let roleContextSpy: jasmine.SpyObj<RoleContextService>;

  const mockPatient = {
    id: 'p1',
    firstName: 'John',
    lastName: 'Doe',
    dateOfBirth: '1998-01-01',
    gender: 'MALE',
    medicalHistorySummary: null,
  } as any;

  beforeEach(async () => {
    patientServiceSpy = jasmine.createSpyObj('PatientService', ['getById']);
    vitalServiceSpy = jasmine.createSpyObj('VitalSignService', ['getRecent']);
    encounterServiceSpy = jasmine.createSpyObj('EncounterService', ['list']);
    appointmentServiceSpy = jasmine.createSpyObj('AppointmentService', ['list']);
    sharingServiceSpy = jasmine.createSpyObj('RecordSharingService', ['getShareResult']);
    hospitalServiceSpy = jasmine.createSpyObj('HospitalService', [
      'list',
      'getMyHospitalAsResponse',
    ]);
    toastSpy = jasmine.createSpyObj('ToastService', ['success', 'error', 'info', 'warn']);
    permissionSpy = jasmine.createSpyObj('PermissionService', [
      'hasPermission',
      'hasAnyPermission',
    ]);
    roleContextSpy = jasmine.createSpyObj('RoleContextService', ['isSuperAdmin'], {
      activeHospitalId: 'h1',
    });

    patientServiceSpy.getById.and.returnValue(of(mockPatient));
    vitalServiceSpy.getRecent.and.returnValue(of([]));
    encounterServiceSpy.list.and.returnValue(of([] as any));
    appointmentServiceSpy.list.and.returnValue(of([] as any));
    permissionSpy.hasPermission.and.returnValue(true);
    permissionSpy.hasAnyPermission.and.returnValue(true);
    roleContextSpy.isSuperAdmin.and.returnValue(false);

    await TestBed.configureTestingModule({
      imports: [PatientDetailComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => 'p1' } } },
        },
        { provide: PatientService, useValue: patientServiceSpy },
        { provide: VitalSignService, useValue: vitalServiceSpy },
        { provide: EncounterService, useValue: encounterServiceSpy },
        { provide: AppointmentService, useValue: appointmentServiceSpy },
        { provide: RecordSharingService, useValue: sharingServiceSpy },
        { provide: HospitalService, useValue: hospitalServiceSpy },
        { provide: ToastService, useValue: toastSpy },
        { provide: PermissionService, useValue: permissionSpy },
        { provide: RoleContextService, useValue: roleContextSpy },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PatientDetailComponent);
    component = fixture.componentInstance;
  });

  it('should create', () => {
    expect(component).toBeTruthy();
  });

  it('should load patient on init', () => {
    fixture.detectChanges();
    expect(patientServiceSpy.getById).toHaveBeenCalledWith('p1', 'h1');
    expect(component.patient()).toEqual(mockPatient);
  });

  it('should switch to vitals tab and load vitals', () => {
    fixture.detectChanges();
    component.setTab('vitals');
    expect(component.activeTab()).toBe('vitals');
    expect(vitalServiceSpy.getRecent).toHaveBeenCalledWith('p1');
  });

  describe('Vitals tab — no-metrics placeholder', () => {
    const vitalWithOnlyNotes: VitalSignResponse = {
      id: 'v1',
      patientId: 'p1',
      staffId: 's1',
      staffName: 'Nurse A',
      heartRate: null,
      systolicBp: null,
      diastolicBp: null,
      temperature: null,
      respiratoryRate: null,
      oxygenSaturation: null,
      weight: null,
      height: null,
      painLevel: null,
      notes: 'twisted',
      recordedAt: '2026-04-15T00:42:15',
      createdAt: '2026-04-15T00:42:15',
    };

    const vitalWithMetrics: VitalSignResponse = {
      id: 'v2',
      patientId: 'p1',
      staffId: 's1',
      staffName: 'Nurse A',
      heartRate: 72,
      systolicBp: 120,
      diastolicBp: 80,
      temperature: 36.5,
      respiratoryRate: 16,
      oxygenSaturation: 98,
      weight: 70,
      height: 175,
      painLevel: 2,
      notes: 'Normal vitals',
      recordedAt: '2026-04-15T01:00:00',
      createdAt: '2026-04-15T01:00:00',
    };

    it('should show no-metrics placeholder when vital has only notes', () => {
      vitalServiceSpy.getRecent.and.returnValue(of([vitalWithOnlyNotes]));
      fixture.detectChanges();
      component.setTab('vitals');
      fixture.detectChanges();

      const card = fixture.nativeElement.querySelector('.vital-card');
      expect(card).toBeTruthy();

      const noMetrics = card.querySelector('.no-metrics');
      expect(noMetrics).toBeTruthy();

      // Notes should still be displayed
      const notes = card.querySelector('.vital-notes:not(.no-metrics)');
      expect(notes).toBeTruthy();
      expect(notes.textContent).toContain('twisted');
    });

    it('should NOT show no-metrics placeholder when vital has numeric metrics', () => {
      vitalServiceSpy.getRecent.and.returnValue(of([vitalWithMetrics]));
      fixture.detectChanges();
      component.setTab('vitals');
      fixture.detectChanges();

      const card = fixture.nativeElement.querySelector('.vital-card');
      expect(card).toBeTruthy();

      const noMetrics = card.querySelector('.no-metrics');
      expect(noMetrics).toBeNull();

      // Metrics should exist
      const metrics = card.querySelectorAll('.vital-metric');
      expect(metrics.length).toBeGreaterThan(0);
    });

    it('should show empty state when no vitals exist', () => {
      vitalServiceSpy.getRecent.and.returnValue(of([]));
      fixture.detectChanges();
      component.setTab('vitals');
      fixture.detectChanges();

      const empty = fixture.nativeElement.querySelector('.empty-state');
      expect(empty).toBeTruthy();
    });
  });
});
