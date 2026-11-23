import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient, withXhr } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { ActivatedRoute, convertToParamMap } from '@angular/router';
import { TranslateModule } from '@ngx-translate/core';
import { Observable, of, throwError } from 'rxjs';

import { ImagingComponent } from './imaging';
import { ImagingOrderResponse, ImagingService } from '../services/imaging.service';
import {
  ImagingCatalogueExam,
  ImagingCatalogueService,
} from '../services/imaging-catalogue.service';
import { ImagingReadingService } from '../services/imaging-reading.service';
import { HospitalService, HospitalResponse } from '../services/hospital.service';
import { PatientService } from '../services/patient.service';
import { EncounterService } from '../services/encounter.service';
import { ClinicalRulesService } from '../services/clinical-rules.service';
import { ToastService } from '../core/toast.service';
import { RoleContextService } from '../core/role-context.service';
import { AuthService } from '../auth/auth.service';

/**
 * QA D3450 (High) / D3254 (r70): an imaging order names its exam in the hospital's catalogue
 * instead of free text, so it is priced by the price list and a family reader's access follows
 * the exam's flag. Free text stays, as the fallback for an exam the catalogue does not hold.
 */
describe('ImagingComponent — r70 (D3450 / D3254: the exam is picked from the catalogue)', () => {
  let fixture: ComponentFixture<ImagingComponent>;
  let component: ImagingComponent;
  let imaging: jasmine.SpyObj<ImagingService>;
  let catalogue: jasmine.SpyObj<ImagingCatalogueService>;

  const exam = (over: Partial<ImagingCatalogueExam> = {}): ImagingCatalogueExam => ({
    id: 'e-chest',
    code: 'RX-THORAX',
    label: 'Radiographie du thorax (face)',
    modality: 'XRAY',
    bodyRegion: 'Thorax',
    mayRevealPregnancy: false,
    namedAsPregnancyStudy: false,
    pregnancyFlagDecidedBy: null,
    pregnancyFlagDecidedAt: null,
    canEdit: false,
    ...over,
  });

  const obstetric = exam({
    id: 'e-obs',
    code: 'ECHO-T1',
    label: 'Échographie du 1er trimestre',
    modality: 'ULTRASOUND',
    bodyRegion: null,
    mayRevealPregnancy: true,
  });

  function setUp(exams: Observable<ImagingCatalogueExam[]>, roles = ['ROLE_DOCTOR']): void {
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
    catalogue = jasmine.createSpyObj('ImagingCatalogueService', ['list', 'update']);
    catalogue.list.and.returnValue(exams);
    const encounters = jasmine.createSpyObj('EncounterService', ['list']);
    encounters.list.and.returnValue(of([]));
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
        { provide: ImagingCatalogueService, useValue: catalogue },
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
          provide: ClinicalRulesService,
          useValue: {
            overviewSignal: () => null,
            statusAt: () => null,
            ensureLoaded: () => undefined,
          },
        },
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
            effectiveHospitalIdForRequest: () => null,
            hasAnyActiveRole: (wanted: string[]) => wanted.some((role) => roles.includes(role)),
          } as unknown as RoleContextService,
        },
      ],
    });
    fixture = TestBed.createComponent(ImagingComponent);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  const q = <T extends HTMLElement>(testid: string): T | null =>
    (fixture.nativeElement as HTMLElement).querySelector<T>(`[data-testid="${testid}"]`);

  function openOrderFor(): void {
    component.openCreate();
    component.onPatientPicked({ id: 'p1', firstName: 'Awa', lastName: 'Kabore' } as never);
    fixture.detectChanges();
  }

  function pick(id: string): void {
    const select = q<HTMLSelectElement>('img-catalogue-exam')!;
    select.value = id;
    select.dispatchEvent(new Event('change'));
    fixture.detectChanges();
  }

  it('offers the catalogue, free text first, and reads it each time the form opens', () => {
    setUp(of([exam(), obstetric]));
    openOrderFor();

    expect(catalogue.list).toHaveBeenCalledTimes(1);
    const options = Array.from(q<HTMLSelectElement>('img-catalogue-exam')!.options).map((o) =>
      o.textContent?.trim(),
    );
    expect(options).toEqual([
      'IMAGING_CATALOGUE.FREE_TEXT_OPTION',
      'Radiographie du thorax (face) (RX-THORAX)',
      'Échographie du 1er trimestre (ECHO-T1)',
    ]);
    component.closeModal();
    component.openCreate();
    expect(catalogue.list).toHaveBeenCalledTimes(2);
  });

  it('the r70 case: « Échographie du 1er trimestre » picked from the catalogue is sent by its id, with its name and modality', async () => {
    setUp(of([exam(), obstetric]));
    openOrderFor();
    pick('e-obs');
    await fixture.whenStable();
    fixture.detectChanges();

    expect(component.form.studyType).toBe('Échographie du 1er trimestre');
    expect(component.form.modality).toBe('ULTRASOUND');
    const modality = (fixture.nativeElement as HTMLElement).querySelector<HTMLSelectElement>(
      '#img-modality',
    )!;
    const study = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '#img-studyType',
    )!;
    expect(modality.disabled).withContext('the exam names its modality').toBeTrue();
    expect(study.readOnly).withContext('the name is the catalogue exam').toBeTrue();
    expect(q('img-catalogue-hint')?.textContent).toContain('IMAGING_CATALOGUE.PICK_HINT');

    component.submitForm();
    const sent = imaging.createOrder.calls.mostRecent().args[0];
    expect(sent.catalogueItemId).toBe('e-obs');
    expect(sent.studyType).toBe('Échographie du 1er trimestre');
    expect(sent.modality).toBe('ULTRASOUND');
  });

  it('fills the body region from the exam only when the orderer typed none', () => {
    setUp(of([exam(), obstetric]));
    openOrderFor();
    pick('e-chest');
    expect(component.form.bodyRegion).toBe('Thorax');

    component.form.bodyRegion = 'Hémithorax gauche';
    pick('e-obs');
    pick('e-chest');
    expect(component.form.bodyRegion).toBe('Hémithorax gauche');
  });

  it('free text stays the fallback: no catalogue id is sent, the study is typed, and the hint says what it costs', () => {
    setUp(of([exam()]));
    openOrderFor();
    pick('e-chest');
    pick('');

    expect(component.form.catalogueItemId).toBeUndefined();
    const study = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '#img-studyType',
    )!;
    expect(study.readOnly).toBeFalse();
    expect(q('img-catalogue-hint')?.textContent).toContain('IMAGING_CATALOGUE.FREE_TEXT_HINT');
    component.form.studyType = 'Examen rare';
    component.submitForm();
    const sent = imaging.createOrder.calls.mostRecent().args[0];
    expect(sent.catalogueItemId).toBeUndefined();
    expect(sent.studyType).toBe('Examen rare');
  });

  it('an empty catalogue or a failed read leaves the form usable in free text, and says why', () => {
    setUp(of([]));
    openOrderFor();
    expect(q('img-catalogue-hint')?.textContent).toContain('IMAGING_CATALOGUE.EMPTY_HINT');

    TestBed.resetTestingModule();
    setUp(throwError(() => ({ status: 403 })));
    openOrderFor();
    expect(q('img-catalogue-hint')?.textContent).toContain('IMAGING_CATALOGUE.ORDER_LOAD_FAILED');
    component.form.studyType = 'Radiographie thorax';
    component.submitForm();
    expect(imaging.createOrder).toHaveBeenCalled();
  });

  it('an edit opens on the order’s catalogue exam and sends it back', async () => {
    setUp(of([exam(), obstetric]));
    component.openEdit({
      id: 'o1',
      patientId: 'p1',
      hospitalId: 'h1',
      modality: 'XRAY',
      studyType: 'Radiographie du thorax (face)',
      catalogueItemId: 'e-chest',
      status: 'ORDERED',
    } as ImagingOrderResponse);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    expect(q<HTMLSelectElement>('img-catalogue-exam')!.value).toBe('e-chest');
    component.submitForm();
    expect(imaging.updateOrder.calls.mostRecent().args[1].catalogueItemId).toBe('e-chest');
  });

  it('an order whose exam was withdrawn from the catalogue stays editable, and is sent without an exam', async () => {
    setUp(of([exam()]));
    component.openEdit({
      id: 'o1',
      patientId: 'p1',
      hospitalId: 'h1',
      modality: 'ULTRASOUND',
      studyType: 'Échographie rénale',
      catalogueItemId: 'e-withdrawn',
      status: 'ORDERED',
    } as ImagingOrderResponse);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const study = (fixture.nativeElement as HTMLElement).querySelector<HTMLInputElement>(
      '#img-studyType',
    )!;
    expect(study.readOnly).toBeFalse();
    component.submitForm();
    const sent = imaging.updateOrder.calls.mostRecent().args[1];
    expect(sent.catalogueItemId)
      .withContext('the server keeps the exam it was placed with')
      .toBeUndefined();
    expect(sent.studyType).toBe('Échographie rénale');
  });

  it('the catalogue tab is offered to the hospital admin and the radiologist only', () => {
    setUp(of([exam()]));
    expect(q('imaging-catalogue-tab')).withContext('a doctor').toBeNull();

    TestBed.resetTestingModule();
    setUp(of([exam({ canEdit: true })]), ['ROLE_RADIOLOGIST']);
    q('imaging-catalogue-tab')!.click();
    fixture.detectChanges();
    expect(q('imaging-catalogue')).not.toBeNull();
    expect(q('catalogue-exam-RX-THORAX')).not.toBeNull();

    TestBed.resetTestingModule();
    setUp(of([exam({ canEdit: true })]), ['ROLE_HOSPITAL_ADMIN']);
    expect(q('imaging-catalogue-tab')).not.toBeNull();
  });

  it('the exam picker fits the order dialog at 1366, 1024, 768 and 390 px wide, a long exam name included', () => {
    setUp(
      of([
        exam({
          label:
            'Échographie obstétricale morphologique du deuxième trimestre avec doppler '.repeat(2),
        }),
      ]),
    );
    openOrderFor();
    pick('e-chest');
    const picker = q<HTMLSelectElement>('img-catalogue-exam')!;
    const modal = picker.closest<HTMLElement>('.modal')!;
    for (const width of [1366, 1024, 768, 390]) {
      modal.style.width = `${Math.min(width - 32, 720)}px`;
      modal.style.maxWidth = `${width - 32}px`;
      fixture.detectChanges();
      const field = picker.closest<HTMLElement>('.field')!;
      expect(field.scrollWidth)
        .withContext(`exam field at ${width}px`)
        .toBeLessThanOrEqual(field.clientWidth + 1);
      for (const part of [picker, field, q<HTMLElement>('img-catalogue-hint')!]) {
        expect(part.getBoundingClientRect().right)
          .withContext(`${part.tagName} at ${width}px`)
          .toBeLessThanOrEqual(modal.getBoundingClientRect().right + 1);
      }
    }
  });
});
