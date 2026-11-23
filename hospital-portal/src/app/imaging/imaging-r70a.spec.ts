import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ImagingComponent } from './imaging';
import { ImagingOrderResponse, ImagingService } from '../services/imaging.service';
import { ImagingReadingService } from '../services/imaging-reading.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { EncounterResponse, EncounterService } from '../services/encounter.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

/**
 * QA D3154 (r70a): an imaging order placed from the portal names the visit it
 * was asked in, the patient's latest open visit here by default, as a lab
 * order does; a caregiver's view of the study then follows that visit.
 */
function visit(id: string, encounterDate: string, status = 'IN_PROGRESS'): EncounterResponse {
  return { id, encounterDate, status, patientId: 'p1' } as unknown as EncounterResponse;
}

describe('ImagingComponent — r70a (D3154: the order names its visit)', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imaging: jasmine.SpyObj<ImagingService>;
  let encounters: jasmine.SpyObj<EncounterService>;

  function setUp(visits: EncounterResponse[]): void {
    imaging = jasmine.createSpyObj('ImagingService', [
      'getAllOrders',
      'getReportsByHospital',
      'checkDuplicates',
      'createOrder',
      'updateOrder',
    ]);
    imaging.getAllOrders.and.returnValue(of([]));
    imaging.getReportsByHospital.and.returnValue(of([]));
    imaging.checkDuplicates.and.returnValue(of([]));
    imaging.createOrder.and.returnValue(of({ id: 'o-new' } as ImagingOrderResponse));
    imaging.updateOrder.and.returnValue(of({ id: 'o1' } as ImagingOrderResponse));
    encounters = jasmine.createSpyObj('EncounterService', ['list']);
    encounters.list.and.returnValue(of(visits));
    const reading = jasmine.createSpyObj('ImagingReadingService', ['toSign', 'readingContext']);
    reading.toSign.and.returnValue(of([]));
    reading.readingContext.and.returnValue(of(null));
    const hospital = jasmine.createSpyObj('HospitalService', ['list', 'getMyHospitalAsResponse']);
    hospital.getMyHospitalAsResponse.and.returnValue(
      of({ id: 'h1', name: 'CHU' } as HospitalResponse),
    );
    const auth = jasmine.createSpyObj('AuthService', ['getHospitalId', 'getUserProfile']);
    auth.getHospitalId.and.returnValue('h1');
    auth.getUserProfile.and.returnValue({ id: 'u-me' } as never);
    const patients = jasmine.createSpyObj('PatientService', ['list']);
    patients.list.and.returnValue(of([]));

    TestBed.configureTestingModule({
      imports: [ImagingComponent, TranslateModule.forRoot()],
      providers: [
        provideHttpClient(withXhr()),
        provideHttpClientTesting(),
        { provide: ImagingService, useValue: imaging },
        { provide: ImagingReadingService, useValue: reading },
        { provide: HospitalService, useValue: hospital },
        { provide: PatientService, useValue: patients },
        { provide: EncounterService, useValue: encounters },
        {
          provide: ToastService,
          useValue: jasmine.createSpyObj('ToastService', ['success', 'error', 'warning']),
        },
        { provide: AuthService, useValue: auth },
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { queryParamMap: convertToParamMap({}) } },
        },
        {
          provide: RoleContextService,
          useValue: {
            isSuperAdmin: () => false,
            globalView: () => false,
            selectedHospitalId: () => 'h1',
            activeHospitalId: 'h1',
            hasAnyActiveRole: (wanted: string[]) => wanted.includes('ROLE_DOCTOR'),
          } as unknown as RoleContextService,
        },
      ],
    });
    fixture = TestBed.createComponent(ImagingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const q = (testid: string): HTMLElement | null =>
    (fixture.nativeElement as HTMLElement).querySelector(`[data-testid="${testid}"]`);

  function pickPatient(): void {
    component.openCreate();
    component.onPatientPicked({ id: 'p1', firstName: 'Awa', lastName: 'Kabore' } as never);
    component.form.studyType = 'Radiographie thorax';
    fixture.detectChanges();
  }

  it('sends the latest open visit of the patient here, and offers only open visits', () => {
    setUp([
      visit('v-old', '2026-11-18T09:00:00'),
      visit('v-closed', '2026-11-21T07:00:00', 'COMPLETED'),
      visit('v-today', '2026-11-21T08:00:00'),
    ]);
    pickPatient();
    component.submitForm();

    expect(encounters.list).toHaveBeenCalledWith({ patientId: 'p1', hospitalId: 'h1' });
    expect(component.orderVisits().map((v) => v.id)).toEqual(['v-today', 'v-old']);
    expect(imaging.createOrder.calls.mostRecent().args[0].encounterId).toBe('v-today');
  });

  it('sends the visit the doctor picked', () => {
    setUp([visit('v-old', '2026-11-18T09:00:00'), visit('v-today', '2026-11-21T08:00:00')]);
    pickPatient();
    expect(q('img-visit-field')?.querySelectorAll('option').length).toBe(2);
    component.form.encounterId = 'v-old';
    component.submitForm();

    expect(imaging.createOrder.calls.mostRecent().args[0].encounterId).toBe('v-old');
  });

  it('says so when the patient has no open visit here, and sends none', () => {
    setUp([visit('v-closed', '2026-11-21T07:00:00', 'COMPLETED')]);
    pickPatient();

    expect(q('img-no-open-visit')).not.toBeNull();
    component.submitForm();
    expect(imaging.createOrder.calls.mostRecent().args[0].encounterId).toBeUndefined();
  });

  it('the visit field fits the order dialog at 1366, 768 and 390 px wide, long labels included', () => {
    const long = {
      departmentName: 'Médecine interne et maladies infectieuses tropicales '.repeat(3),
    };
    for (const visits of [
      [{ ...visit('v-today', '2026-11-21T08:00:00'), ...long } as EncounterResponse],
      [],
    ]) {
      TestBed.resetTestingModule();
      setUp(visits);
      pickPatient();
      const modal = q('img-visit-field')!.closest<HTMLElement>('.modal')!;
      for (const width of [1366, 768, 390]) {
        modal.style.width = `${Math.min(width - 32, 720)}px`;
        modal.style.maxWidth = `${width - 32}px`;
        fixture.detectChanges();
        const field = q('img-visit-field')!;
        expect(field.scrollWidth)
          .withContext(`visit field at ${width}px (${visits.length} visits)`)
          .toBeLessThanOrEqual(field.clientWidth + 1);
        expect(field.getBoundingClientRect().width)
          .withContext(`visit field box at ${width}px`)
          .toBeLessThanOrEqual(modal.getBoundingClientRect().width + 1);
      }
    }
  });

  it('an edit sends no visit: the server keeps the order its own', () => {
    setUp([visit('v-today', '2026-11-21T08:00:00')]);
    component.openEdit({
      id: 'o1',
      patientId: 'p1',
      hospitalId: 'h1',
      modality: 'XRAY',
      studyType: 'Radiographie thorax',
      status: 'ORDERED',
    } as ImagingOrderResponse);
    fixture.detectChanges();
    expect(q('img-visit-field')).toBeNull();
    component.submitForm();

    expect(encounters.list).not.toHaveBeenCalled();
    expect(imaging.updateOrder.calls.mostRecent().args[1].encounterId).toBeUndefined();
  });
});
