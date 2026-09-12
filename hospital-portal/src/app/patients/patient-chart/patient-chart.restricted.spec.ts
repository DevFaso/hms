import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { SimpleChange } from '@angular/core';
import { of } from 'rxjs';

import { PatientChartComponent } from './patient-chart.component';
import { PatientService, PatientTimeline } from '../../services/patient.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import { AuthService } from '../../auth/auth.service';

/**
 * E9 #64 — the timeline tells a clinician what it withheld and offers the
 * declaration; a declared session reloads the timeline with the reason
 * already stated.
 */
describe('PatientChartComponent — restricted rows', () => {
  let fixture: ComponentFixture<PatientChartComponent>;
  let component: PatientChartComponent;
  let patientService: jasmine.SpyObj<PatientService>;

  function timeline(restrictedRows: PatientTimeline['restrictedRows']): PatientTimeline {
    return {
      patientId: 'p-1',
      patientName: 'Awa Traoré',
      accessReason: 'Suivi clinique',
      entries: [],
      totalEntries: 0,
      generatedAt: '2026-09-12T10:00:00Z',
      restrictedRows,
    };
  }

  function setup(tl: PatientTimeline) {
    patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'getDoctorTimeline',
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
    ]);
    patientService.getDoctorTimeline.and.returnValue(of(tl));
    patientService.listAllergies.and.returnValue(of([]));
    patientService.listDiagnoses.and.returnValue(of([]));
    patientService.listChartUpdates.and.returnValue(of({ content: [], totalElements: 0 }));

    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_DOCTOR'],
          }),
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => ['ROLE_DOCTOR'],
            getHospitalId: () => 'h-1',
          },
        },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj<ToastService>('ToastService', [
            'success',
            'error',
            'info',
          ]),
        },
      ],
    });

    fixture = TestBed.createComponent(PatientChartComponent);
    component = fixture.componentInstance;
    component.patientId = 'p-1';
    fixture.detectChanges();
  }

  function loadTimelineWithReason(reason: string): void {
    component.setSection('timeline');
    component.timelineReason = reason;
    component.submitTimelineReason();
    fixture.detectChanges();
  }

  it('renders the withheld rows under the timeline and re-emits the open action', () => {
    setup(
      timeline([
        { hospitalId: 'h-2', hospitalName: 'CHU Yalgado', departmentName: 'Psychiatrie', count: 3 },
      ]),
    );
    const emitted = jasmine.createSpy('openRestricted');
    component.openRestricted.subscribe(emitted);

    loadTimelineWithReason('Suivi clinique');

    const block = fixture.nativeElement.querySelector('[data-testid="restricted-rows"]');
    expect(block).not.toBeNull();
    expect(block.querySelectorAll('[data-testid="restricted-row"]').length).toBe(1);
    (block.querySelector('[data-testid="restricted-open"]') as HTMLButtonElement).click();
    expect(emitted).toHaveBeenCalledTimes(1);
  });

  it('renders no restricted block when nothing was withheld', () => {
    setup(timeline([]));

    loadTimelineWithReason('Suivi clinique');

    expect(fixture.nativeElement.querySelector('[data-testid="restricted-rows"]')).toBeNull();
  });

  it('reloads a loaded timeline with the stated reason when access changes', () => {
    setup(timeline([]));
    loadTimelineWithReason('Urgence vitale');
    expect(patientService.getDoctorTimeline).toHaveBeenCalledTimes(1);

    component.refreshToken = 1;
    component.ngOnChanges({ refreshToken: new SimpleChange(0, 1, false) });

    expect(patientService.getDoctorTimeline).toHaveBeenCalledTimes(2);
    expect(patientService.getDoctorTimeline.calls.mostRecent().args).toEqual([
      'p-1',
      'Urgence vitale',
    ]);
  });

  it('does not ask for the timeline again when it was never loaded', () => {
    setup(timeline([]));

    component.refreshToken = 1;
    component.ngOnChanges({ refreshToken: new SimpleChange(0, 1, false) });

    expect(patientService.getDoctorTimeline).not.toHaveBeenCalled();
  });
});
