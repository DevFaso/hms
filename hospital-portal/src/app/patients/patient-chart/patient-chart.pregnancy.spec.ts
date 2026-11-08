import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of } from 'rxjs';

import { PatientChartComponent } from './patient-chart.component';
import { PatientService } from '../../services/patient.service';
import { MaternityService, MaternalHistoryResponse } from '../../services/maternity.service';
import { ToastService } from '../../core/toast.service';
import { RoleContextService } from '../../core/role-context.service';
import { roleContextStub } from '../../testing/role-context.stub';
import { AuthService } from '../../auth/auth.service';

/**
 * QA D1067: the D1005 chart line ("Grossesse en cours : 39 SA") stayed on the
 * chart of a woman who had delivered. QA D1082: a woman with no history made
 * every chart load log a 404; the endpoint now answers 204 (a null body).
 */
describe('PatientChartComponent — pregnancy line', () => {
  let fixture: ComponentFixture<PatientChartComponent>;

  /** An LMP about 39 weeks before today, so the line would show if nothing stopped it. */
  function history(overrides: Partial<MaternalHistoryResponse> = {}): MaternalHistoryResponse {
    const lmp = new Date();
    lmp.setDate(lmp.getDate() - 39 * 7 - 1);
    const pad = (n: number) => String(n).padStart(2, '0');
    return {
      id: 'mh-1',
      patientId: 'p-1',
      hospitalId: 'h-1',
      recordedDate: '2026-02-01T09:00:00',
      menstrualHistory: {
        lastMenstrualPeriod: `${lmp.getFullYear()}-${pad(lmp.getMonth() + 1)}-${pad(lmp.getDate())}`,
      },
      ...overrides,
    } as MaternalHistoryResponse;
  }

  function setup(current: Observable<MaternalHistoryResponse | null>): void {
    const patientService = jasmine.createSpyObj<PatientService>('PatientService', [
      'getDoctorTimeline',
      'listAllergies',
      'listDiagnoses',
      'listChartUpdates',
      'listLabResults',
    ]);
    patientService.listAllergies.and.returnValue(of({ items: [], withheld: false }));
    const maternity = jasmine.createSpyObj<MaternityService>('MaternityService', [
      'currentForPatient',
    ]);
    maternity.currentForPatient.and.returnValue(current);
    TestBed.configureTestingModule({
      imports: [PatientChartComponent, TranslateModule.forRoot()],
      providers: [
        provideRouter([]),
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: PatientService, useValue: patientService },
        { provide: MaternityService, useValue: maternity },
        {
          provide: RoleContextService,
          useValue: roleContextStub({
            superAdmin: false,
            hospitalId: 'h-1',
            roles: ['ROLE_MIDWIFE'],
          }),
        },
        {
          provide: AuthService,
          useValue: {
            isAuthenticated: () => true,
            getRoles: () => ['ROLE_MIDWIFE'],
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
    fixture.componentInstance.patientId = 'p-1';
    fixture.componentInstance.patientGender = 'FEMALE';
    fixture.detectChanges();
  }

  function line(): Element | null {
    return fixture.nativeElement.querySelector('[data-testid="chart-pregnancy"]');
  }

  it('shows the ongoing pregnancy (the D1005 line still works)', () => {
    setup(of(history()));
    expect(line()).not.toBeNull();
  });

  it('QA D1067: a delivered woman has no pregnancy in progress on her chart', () => {
    setup(of(history({ deliveredAt: '2026-10-30T17:12:00' })));
    expect(fixture.componentInstance.pregnancy()).toBeNull();
    expect(line()).toBeNull();
  });

  it('QA D1082: no history (204, a null body) shows no line and no error', () => {
    setup(of(null));
    expect(fixture.componentInstance.pregnancy()).toBeNull();
    expect(line()).toBeNull();
    expect(TestBed.inject(ToastService).error).not.toHaveBeenCalled();
  });
});
